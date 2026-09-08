package com.authsystem.commands;

import com.authsystem.AuthSystem;
import com.authsystem.manager.PlayerDataManager.PasswordData;
import com.authsystem.util.IpResolver;
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

public class LoginCommand implements CommandExecutor {
    private final AuthSystem plugin;
    private final Set<UUID> verificacoesEmAndamento = ConcurrentHashMap.newKeySet();

    public LoginCommand(AuthSystem plugin) { this.plugin = plugin; }

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
            player.sendMessage(ChatColor.YELLOW + "Sua conta original já foi verificada automaticamente. Não é preciso usar /login.");
            return true;
        }
        if (!plugin.getPlayerDataManager().isRegistered(player.getName())) {
            player.sendMessage(ChatColor.RED + "Você ainda não tem conta. Use /registro <senha> <confirmar-senha>.");
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(ChatColor.RED + "Uso correto: /login <senha>");
            return true;
        }

        String ip = IpResolver.getPlayerIp(player);
        if (ip == null) {
            player.sendMessage(ChatColor.RED + "Não foi possível identificar seu IP. Tente entrar novamente.");
            return true;
        }

        String username = player.getName();
        if (plugin.getLoginProtection().estaBloqueado(ip)) {
            player.sendMessage(ChatColor.RED + "Este IP está temporariamente bloqueado por excesso de tentativas. Tente novamente mais tarde.");
            return true;
        }
        if (plugin.getLoginProtection().estaBloqueadoConta(username)) {
            long restante = plugin.getLoginProtection().segundosRestantesConta(username);
            player.sendMessage(ChatColor.RED + "Esta conta está temporariamente bloqueada por excesso de tentativas. Tente novamente em " + restante + " segundos.");
            return true;
        }

        UUID playerId = player.getUniqueId();
        if (!verificacoesEmAndamento.add(playerId)) {
            player.sendMessage(ChatColor.YELLOW + "Sua senha já está sendo verificada. Aguarde um instante.");
            return true;
        }

        int maxProcessamentos = Math.max(1, plugin.getConfig().getInt("seguranca.max-processamentos-pbkdf2-simultaneos", 2));
        if (!plugin.getHashProcessingLimiter().tryAcquire(maxProcessamentos)) {
            verificacoesEmAndamento.remove(playerId);
            player.sendMessage(ChatColor.RED + "O servidor está processando muitas senhas no momento. Aguarde alguns segundos e tente novamente.");
            return true;
        }

        String senha = args[0];
        PasswordData passwordData = plugin.getPlayerDataManager().getPasswordData(username);
        if (passwordData == null) {
            plugin.getHashProcessingLimiter().release();
            verificacoesEmAndamento.remove(playerId);
            player.sendMessage(ChatColor.RED + "Não foi possível verificar sua conta. Tente novamente.");
            return true;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                boolean senhaCorreta = PasswordUtils.verify(
                        senha,
                        passwordData.salt(),
                        passwordData.hash(),
                        passwordData.iterations()
                );

                int currentIterations = plugin.getPasswordIterations();
                if (senhaCorreta && passwordData.iterations() < currentIterations) {
                    String novoSalt = PasswordUtils.generateSalt();
                    String novoHash = PasswordUtils.hash(senha, novoSalt, currentIterations);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.getPlayerDataManager().upgradePasswordHash(username, novoSalt, novoHash, currentIterations);
                    });
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (!player.isOnline() || !player.getUniqueId().equals(playerId)) return;
                        if (plugin.getSessionManager().isAuthenticated(player)) return;

                        if (senhaCorreta) {
                            concluirLogin(player, username, ip);
                        } else {
                            registrarFalha(player, username, ip);
                        }
                    } finally {
                        verificacoesEmAndamento.remove(playerId);
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Falha ao processar login de " + username + ": " + e.getMessage());
                verificacoesEmAndamento.remove(playerId);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && !plugin.getSessionManager().isAuthenticated(player)) {
                        player.sendMessage(ChatColor.RED + "Não foi possível verificar sua senha. Tente novamente.");
                    }
                });
            } finally {
                plugin.getHashProcessingLimiter().release();
            }
        });

        player.sendMessage(ChatColor.YELLOW + "Verificando sua senha...");
        return true;
    }

    private void concluirLogin(Player player, String username, String ip) {
        int limiteContas = plugin.getConfig().getInt("max-contas-por-ip", 1);
        if (!plugin.getSessionManager().tryRegisterAuthenticatedIp(ip, player.getUniqueId(), limiteContas)) {
            player.sendMessage(ChatColor.RED + "Este IP já atingiu o limite de " + limiteContas + " conta(s) conectada(s) ao mesmo tempo.");
            return;
        }

        int limiteIps = plugin.getConfig().getInt("max-ips-por-conta", 1);
        if (!plugin.getPlayerDataManager().tryAddIp(username, ip, limiteIps)) {
            plugin.getSessionManager().unregisterAuthenticatedIp(ip, player.getUniqueId());
            player.sendMessage(ChatColor.RED + "Esta conta já atingiu o limite de " + limiteIps + " IP(s) permitido(s).");
            return;
        }

        plugin.getSessionManager().setAuthenticated(player, true);
        plugin.getSessionManager().cancelTimeout(player);
        plugin.getLoginProtection().limparAoLogar(ip);
        plugin.getLoginProtection().limparContaAoLogar(username);
        player.sendMessage(ChatColor.GREEN + "Login efetuado com sucesso! Bem-vindo(a) de volta.");
    }

    private void registrarFalha(Player player, String username, String ip) {
        int max = Math.max(1, plugin.getConfig().getInt("max-tentativas-login", 3));
        long minutosBloqueio = Math.max(1L, plugin.getConfig().getLong("bloqueio-apos-exceder-tentativas-minutos", 5));
        long bloqueioMs = minutosBloqueio * 60_000L;
        int tentativasIp = plugin.getLoginProtection().registrarErro(ip, max, bloqueioMs);
        int tentativasConta = plugin.getLoginProtection().registrarErroConta(username, max, bloqueioMs);
        int tentativas = Math.max(tentativasIp, tentativasConta);
        if (tentativas > max) player.kickPlayer(ChatColor.RED + "Muitas tentativas de senha incorreta. Tente novamente mais tarde.");
        else player.sendMessage(ChatColor.RED + "Senha incorreta! (" + tentativas + "/" + max + ")");
    }
}
