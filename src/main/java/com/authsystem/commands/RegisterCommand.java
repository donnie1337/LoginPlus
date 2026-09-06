package com.authsystem.commands;

import com.authsystem.AuthSystem;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class RegisterCommand implements CommandExecutor {

    private final AuthSystem plugin;

    public RegisterCommand(AuthSystem plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Este comando so pode ser usado dentro do jogo.");
            return true;
        }

        if (plugin.getSessionManager().isPremium(player)) {
            player.sendMessage(ChatColor.YELLOW + "Sua conta original ja foi verificada automaticamente. Nao e preciso se registrar.");
            return true;
        }

        if (plugin.getPlayerDataManager().isRegistered(player.getName())) {
            player.sendMessage(ChatColor.RED + "Voce ja possui uma conta registrada. Use /login <senha>.");
            return true;
        }

        if (args.length != 2) {
            player.sendMessage(ChatColor.RED + "Uso correto: /registro <senha> <confirmar-senha>");
            return true;
        }

        String senha = args[0];
        String confirmar = args[1];

        int tamanhoMinimo = plugin.getConfig().getInt("tamanho-minimo-senha", 4);
        if (senha.length() < tamanhoMinimo) {
            player.sendMessage(ChatColor.RED + "Sua senha precisa ter pelo menos " + tamanhoMinimo + " caracteres.");
            return true;
        }

        if (!senha.equals(confirmar)) {
            player.sendMessage(ChatColor.RED + "As senhas nao coincidem.");
            return true;
        }

        plugin.getPlayerDataManager().register(player.getName(), senha);
        plugin.getSessionManager().setAuthenticated(player, true);
        plugin.getSessionManager().cancelTimeout(player);
        player.sendMessage(ChatColor.GREEN + "Registro concluido com sucesso! Voce ja esta logado.");

        return true;
    }
}
