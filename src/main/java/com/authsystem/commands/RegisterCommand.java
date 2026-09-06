package com.authsystem.commands;

import com.authsystem.AuthSystem;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class RegisterCommand implements CommandExecutor {

    private static final int MIN_PASSWORD_LENGTH = 7;
    private static final int MAX_PASSWORD_LENGTH = 16;

    private final AuthSystem plugin;

    public RegisterCommand(AuthSystem plugin) {
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

        if (senha.length() < MIN_PASSWORD_LENGTH) {
            player.sendMessage(ChatColor.RED + "Sua senha precisa ter pelo menos " + MIN_PASSWORD_LENGTH + " caracteres.");
            return true;
        }

        if (senha.length() > MAX_PASSWORD_LENGTH) {
            player.sendMessage(ChatColor.RED + "Sua senha pode ter no máximo " + MAX_PASSWORD_LENGTH + " caracteres.");
            return true;
        }

        if (!senha.matches(".*[A-Za-z].*") || !senha.matches(".*[0-9].*")) {
            player.sendMessage(ChatColor.RED + "Sua senha precisa conter pelo menos uma letra e um número.");
            return true;
        }

        if (!senha.equals(confirmar)) {
            player.sendMessage(ChatColor.RED + "As senhas não coincidem.");
            return true;
        }

        String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                ? player.getAddress().getAddress().getHostAddress()
                : null;
        int limiteContas = plugin.getConfig().getInt("max-contas-por-ip", 1);

        if (limiteContas > 0 && ip != null
                && plugin.getPlayerDataManager().countAccountsForIp(ip) >= limiteContas) {
            player.sendMessage(ChatColor.RED + "Este IP já atingiu o limite de " + limiteContas + " conta(s).");
            return true;
        }

        plugin.getPlayerDataManager().register(player.getName(), senha, ip);
        plugin.getSessionManager().setAuthenticated(player, true);
        plugin.getSessionManager().cancelTimeout(player);
        player.sendMessage(ChatColor.GREEN + "Registro concluído com sucesso! Você já está logado.");

        return true;
    }
}
