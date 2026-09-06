package com.authsystem.commands;

import com.authsystem.AuthSystem;
import com.authsystem.util.IpResolver;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class LoginCommand implements CommandExecutor {
    private final AuthSystem plugin;
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

        String senha = args[0];
        String ip = IpResolver.getPlayerIp(player);
        if (ip == null) {
            player.sendMessage(ChatColor.RED + "Não foi possível identificar seu IP. Tente entrar novamente.");
            return true;
        }

        if (plugin.getPlayerDataManager().checkPassword(player.getName(), senha)) {
            int limiteIps = plugin.getConfig().getInt("max-ips-por-conta", 1);
            if (!plugin.getPlayerDataManager().canUseIp(player.getName(), ip, limiteIps)) {
                player.sendMessage(ChatColor.RED + "Esta conta já atingiu o limite de " + limiteIps + " IP(s) permitido(s).");
                return true;
            }
            int limiteContas = plugin.getConfig().getInt("max-contas-por-ip", 1);
            if (!plugin.getSessionManager().tryRegisterAuthenticatedIp(ip, player.getUniqueId(), limiteContas)) {
                player.sendMessage(ChatColor.RED + "Este IP já atingiu o limite de " + limiteContas + " conta(s) conectada(s) ao mesmo tempo.");
                return true;
            }
            if (plugin.getPlayerDataManager().needsPasswordUpgrade(player.getName())) {
                plugin.getPlayerDataManager().upgradePassword(player.getName(), senha);
            }
            plugin.getPlayerDataManager().addIp(player.getName(), ip);
            plugin.getSessionManager().setAuthenticated(player, true);
            plugin.getSessionManager().cancelTimeout(player);
            plugin.getLoginProtection().limparAoLogar(ip);
            player.sendMessage(ChatColor.GREEN + "Login efetuado com sucesso! Bem-vindo(a) de volta.");
            return true;
        }

        int max = Math.max(1, plugin.getConfig().getInt("max-tentativas-login", 3));
        long minutosBloqueio = Math.max(1L, plugin.getConfig().getLong("bloqueio-apos-exceder-tentativas-minutos", 5));
        int tentativas = plugin.getLoginProtection().registrarErro(ip, max, minutosBloqueio * 60_000L);
        if (tentativas > max) player.kickPlayer(ChatColor.RED + "Muitas tentativas de senha incorreta. Tente novamente mais tarde.");
        else player.sendMessage(ChatColor.RED + "Senha incorreta! (" + tentativas + "/" + max + ")");
        return true;
    }
}
