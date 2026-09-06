package com.authsystem.commands;

import com.authsystem.AuthSystem;
import com.authsystem.util.PasswordUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RegisterCommand implements CommandExecutor {
    private final AuthSystem plugin;
    private final Set<UUID> registrosEmAndamento = ConcurrentHashMap.newKeySet();

    public RegisterCommand(AuthSystem plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Este comando só pode ser usado dentro do jogo.");
            return true;
        }
        if (plugin.getSessionManager().isAuthenticated(player)) {
            player.sendMessage(ChatColor.YELLOW + "Você já está logado.");
            return true;
        }
        if (plugin.getSessionManager().isPremium(player)) {
            player.sendMessage(ChatColor.YELLOW + "Sua conta original já foi verificada automaticamente. Não é preciso se registrar.");
            return true;
        }
        if (plugin.getPlayerDataManager().isRegistered(player.getName())) {
            player.sendMessage(ChatColor.RED + "Você já possui uma conta registrada. Use /login <senha>.");
            return true;
        }
        if (args.length != 2) {
            player.sendMessage(ChatColor.RED + "Uso correto: /registro <senha> <confirmar-senha>");
            return true;
        }
        String senha = args[0];
        String confirmar = args[1];
        int minSenha = plugin.getConfig().getInt("minimo-caracteres-senha", PasswordUtils.DEFAULT_MIN_PASSWORD_LENGTH);
        int maxSenha = plugin.getConfig().getInt("maximo-caracteres-senha", PasswordUtils.DEFAULT_MAX_PASSWORD_LENGTH);
        String erroSenha = PasswordUtils.validatePassword(senha, minSenha, maxSenha);
        if (erroSenha != null) {
            player.sendMessage(ChatColor.RED + erroSenha);
            return true;
        }
        if (!senha.equals(confirmar)) {
            player.sendMessage(ChatColor.RED + "As senhas não coincidem.");
            return true;
        }

        String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                ? player.getAddress().getAddress().getHostAddress() : null;
        if (ip == null || ip.isBlank()) {
            player.sendMessage(ChatColor.RED + "Não foi possível identificar seu IP. Tente entrar novamente.");
            return true;
        }

        UUID playerId = player.getUniqueId();
        if (!registrosEmAndamento.add(playerId)) {
            player.sendMessage(ChatColor.YELLOW + "Seu registro já está sendo processado. Aguarde um instante.");
            return true;
        }

        String username = player.getName();
        // O PBKDF2 de 600k iteracoes e CPU-bound: mantenha todo o calculo fora da thread principal.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String salt = PasswordUtils.generateSalt();
            String hash = PasswordUtils.hash(senha, salt, PasswordUtils.CURRENT_ITERATIONS);

            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (!player.isOnline() || !player.getUniqueId().equals(playerId)) return;
                    if (plugin.getSessionManager().isAuthenticated(player)) return;
                    if (plugin.getPlayerDataManager().isRegistered(username)) {
                        player.sendMessage(ChatColor.RED + "Você já possui uma conta registrada. Use /login <senha>.");
                        return;
                    }

                    if (!plugin.getPlayerDataManager().registerHashed(
                            username, salt, hash, PasswordUtils.CURRENT_ITERATIONS, ip, playerId)) {
                        player.sendMessage(ChatColor.RED + "Não foi possível concluir o registro. Tente novamente.");
                        return;
                    }

                    int limiteContas = plugin.getConfig().getInt("max-contas-por-ip", 1);
                    if (!plugin.getSessionManager().tryRegisterAuthenticatedIp(ip, playerId, limiteContas)) {
                        player.sendMessage(ChatColor.RED + "Este IP já atingiu o limite de " + limiteContas
                                + " conta(s) conectada(s) ao mesmo tempo. Sua conta foi registrada, mas você precisa sair com a outra conta antes de jogar.");
                        return;
                    }

                    plugin.getSessionManager().setAuthenticated(player, true);
                    plugin.getSessionManager().cancelTimeout(player);
                    player.sendMessage(ChatColor.GREEN + "Registro concluído com sucesso! Você já está logado.");
                } finally {
                    registrosEmAndamento.remove(playerId);
                }
            });
        });

        player.sendMessage(ChatColor.YELLOW + "Processando seu registro com segurança...");
        return true;
    }
}
