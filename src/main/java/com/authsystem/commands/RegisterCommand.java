package com.authsystem.commands;

import com.authsystem.AuthSystem;
import com.authsystem.util.PasswordUtils;
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
        String erroSenha = PasswordUtils.validatePassword(senha);

        if (erroSenha != null) {
            player.sendMessage(ChatColor.RED + erroSenha);
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
