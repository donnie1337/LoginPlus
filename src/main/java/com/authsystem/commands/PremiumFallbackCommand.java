package com.authsystem.commands;

import com.authsystem.AuthSystem;
import com.authsystem.util.IpResolver;
import com.authsystem.util.PasswordUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Permite ao dono premium definir uma senha local de contingencia apos auto-login verificado. */
public final class PremiumFallbackCommand implements CommandExecutor {
    private final AuthSystem plugin;
    private final Set<UUID> processing = ConcurrentHashMap.newKeySet();

    public PremiumFallbackCommand(AuthSystem plugin) { this.plugin = plugin; }

    private String msg(String path, String fallback, String... replacements) {
        return plugin.getMessagesManager().getChat(path, fallback, replacements);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Este comando so pode ser usado por jogadores.");
            return true;
        }
        if (!plugin.getSessionManager().isAuthenticated(player) || !plugin.getSessionManager().isPremium(player)) {
            player.sendMessage(msg("premium-fallback.nao-premium", "&cEntre pelo auto-login premium verificado antes de definir a senha de contingencia."));
            return true;
        }
        if (args.length != 2) {
            player.sendMessage(msg("premium-fallback.uso", "&eUso: /premiumfallback <senha> <confirmar-senha>"));
            return true;
        }

        int min = plugin.getConfig().getInt("minimo-caracteres-senha", PasswordUtils.DEFAULT_MIN_PASSWORD_LENGTH);
        int max = plugin.getConfig().getInt("maximo-caracteres-senha", PasswordUtils.DEFAULT_MAX_PASSWORD_LENGTH);
        String validation = PasswordUtils.validatePassword(args[0], min, max);
        if (validation != null) {
            player.sendMessage(msg("premium-fallback.senha-invalida", "&c{erro}", "{erro}", validation));
            return true;
        }
        if (!args[0].equals(args[1])) {
            player.sendMessage(msg("premium-fallback.senha-diferente", "&cAs senhas nao coincidem."));
            return true;
        }

        String username = player.getName();
        String ip = IpResolver.getPlayerIp(player);
        if (ip == null) {
            player.sendMessage(msg("premium-fallback.erro", "&cNao foi possivel identificar seu IP. Tente novamente."));
            return true;
        }

        UUID playerId = player.getUniqueId();
        if (!processing.add(playerId)) {
            player.sendMessage(msg("premium-fallback.processando", "&eJa estou processando sua solicitacao."));
            return true;
        }
        int maxConcurrent = Math.max(1, plugin.getConfig().getInt("seguranca.max-processamentos-pbkdf2-simultaneos", 2));
        if (!plugin.getHashProcessingLimiter().tryAcquire(maxConcurrent)) {
            processing.remove(playerId);
            player.sendMessage(msg("premium-fallback.ocupado", "&cO servidor esta processando muitas senhas. Tente novamente em instantes."));
            return true;
        }

        String password = args[0];
        int iterations = plugin.getPasswordIterations();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String salt = PasswordUtils.generateSalt();
                String hash = PasswordUtils.hash(password, salt, iterations);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (!player.isOnline() || !player.getUniqueId().equals(playerId)
                                || !plugin.getSessionManager().isAuthenticated(player)
                                || !plugin.getSessionManager().isPremium(player)) return;
                        if (!plugin.getPlayerDataManager().setPremiumFallbackPassword(username, salt, hash, iterations, ip)) {
                            player.sendMessage(msg("premium-fallback.erro", "&cNao foi possivel salvar a senha de contingencia."));
                            return;
                        }
                        player.sendMessage(msg("premium-fallback.sucesso", "&aSenha de contingencia salva. Se a verificacao da Mojang ficar indisponivel, voce podera usar /login."));
                    } finally {
                        processing.remove(playerId);
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Falha ao salvar senha de contingencia de " + username + ": " + e.getMessage());
                processing.remove(playerId);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) player.sendMessage(msg("premium-fallback.erro", "&cNao foi possivel salvar a senha de contingencia."));
                });
            } finally {
                plugin.getHashProcessingLimiter().release();
            }
        });
        player.sendMessage(msg("premium-fallback.processando", "&eProcessando a senha de contingencia com seguranca..."));
        return true;
    }
}
