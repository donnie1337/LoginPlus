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
        String ip = player.getAddress() != null
                ? player.getAddress().getAddress().getHostAddress()
                : "desconhecido";

        if (plugin.getPlayerDataManager().checkPassword(player.getName(), senha)) {
            plugin.getSessionManager().setAuthenticated(player, true);
            plugin.getSessionManager().cancelTimeout(player);
            plugin.getLoginProtection().limparAoLogar(ip);
            player.sendMessage(ChatColor.GREEN + "Login efetuado com sucesso! Bem-vindo(a) de volta.");
            return true;
        }

        // Anti-bypass: a contagem de erros fica presa ao IP (não à sessão),
        // então sair e entrar de novo no servidor não reseta as tentativas.
        int max = plugin.getConfig().getInt("max-tentativas-login", 3);
        long bloqueioMs = plugin.getConfig().getInt("bloqueio-apos-exceder-tentativas-minutos", 5) * 60_000L;
        int tentativas = plugin.getLoginProtection().registrarErro(ip, max, bloqueioMs);

        if (tentativas > max) {
            player.kickPlayer(ChatColor.RED + "Muitas tentativas de senha incorreta. Tente novamente mais tarde.");
        } else {
            player.sendMessage(ChatColor.RED + "Senha incorreta! (" + tentativas + "/" + max + ")");
        }

        return true;
    }
}
