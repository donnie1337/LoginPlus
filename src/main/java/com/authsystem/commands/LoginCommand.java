package com.authsystem.commands;

import com.authsystem.AuthSystem;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class LoginCommand implements CommandExecutor {

    private final AuthSystem plugin;

    public LoginCommand(AuthSystem plugin) {
        this.plugin = plugin;
    }

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
        String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                ? player.getAddress().getAddress().getHostAddress()
                : null;

        if (ip == null || ip.isBlank()) {
            player.sendMessage(ChatColor.RED + "Não foi possível identificar seu IP. Tente entrar novamente.");
            return true;
        }

        if (plugin.getPlayerDataManager().checkPassword(player.getName(), senha)) {
            int limiteIps = plugin.getConfig().getInt("max-ips-por-conta", 1);

            if (!plugin.getPlayerDataManager().canUseIp(player.getName(), ip, limiteIps)) {
                player.sendMessage(ChatColor.RED + "Esta conta já atingiu o limite de " + limiteIps + " IP(s) permitido(s).");
                return true;
            }

            plugin.getPlayerDataManager().addIp(player.getName(), ip);
            plugin.getSessionManager().setAuthenticated(player, true);
            plugin.getSessionManager().cancelTimeout(player);
            plugin.getLoginProtection().limparAoLogar(ip);
            player.sendMessage(ChatColor.GREEN + "Login efetuado com sucesso! Bem-vindo(a) de volta.");
            return true;
        }

        // A contagem de erros fica presa ao IP, entao sair e entrar novamente nao zera as tentativas.
        int max = Math.max(1, plugin.getConfig().getInt("max-tentativas-login", 3));
        long minutosBloqueio = Math.max(1L, plugin.getConfig().getLong("bloqueio-apos-exceder-tentativas-minutos", 5));
        long bloqueioMs = minutosBloqueio * 60_000L;
        int tentativas = plugin.getLoginProtection().registrarErro(ip, max, bloqueioMs);

        if (tentativas > max) {
            player.kickPlayer(ChatColor.RED + "Muitas tentativas de senha incorreta. Tente novamente mais tarde.");
        } else {
            player.sendMessage(ChatColor.RED + "Senha incorreta! (" + tentativas + "/" + max + ")");
        }

        return true;
    }
}
