package com.authsystem;

import com.authsystem.commands.LoginCommand;
import com.authsystem.commands.RegisterCommand;
import com.authsystem.listeners.AntiBypassListener;
import com.authsystem.listeners.AuthListener;
import com.authsystem.listeners.PremiumVerificationListener;
import com.authsystem.manager.LoginProtection;
import com.authsystem.manager.MessagesManager;
import com.authsystem.manager.PlayerDataManager;
import com.authsystem.manager.SessionManager;
import com.authsystem.util.PremiumAuthenticator;
import com.authsystem.util.PremiumLoginVerifier;
import com.authsystem.util.PasswordUtils;
import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.plugin.java.JavaPlugin;

public class AuthSystem extends JavaPlugin {
    private PlayerDataManager playerDataManager;
    private SessionManager sessionManager;
    private LoginProtection loginProtection;
    private PremiumAuthenticator premiumAuthenticator;
    private MessagesManager messagesManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureConfigDefaults();
        saveResource("mensagens/titulos.yml", false);

        this.playerDataManager = new PlayerDataManager(this);
        this.sessionManager = new SessionManager();
        this.loginProtection = new LoginProtection();
        this.premiumAuthenticator = new PremiumAuthenticator();
        this.messagesManager = new MessagesManager(this);
        PremiumLoginVerifier premiumLoginVerifier = new PremiumLoginVerifier();

        getCommand("login").setExecutor(new LoginCommand(this));
        getCommand("registro").setExecutor(new RegisterCommand(this));

        getServer().getPluginManager().registerEvents(new AuthListener(this), this);
        getServer().getPluginManager().registerEvents(new AntiBypassListener(this), this);

        PacketEvents.getAPI().getEventManager().registerListener(
                new PremiumVerificationListener(this, premiumLoginVerifier, premiumAuthenticator));

        getLogger().info("AuthSystem ativado com autenticacao premium criptografica!");
        getLogger().info("Senha: " + getConfig().getInt("minimo-caracteres-senha", 7)
                + " a " + getConfig().getInt("maximo-caracteres-senha", 16) + " caracteres.");
        getLogger().info("Limite de contas por IP: " + getConfig().getInt("max-contas-por-ip", 1));
        getLogger().info("Limite de IPs por conta: " + getConfig().getInt("max-ips-por-conta", 1));
    }

    /**
     * Cria e atualiza as opcoes novas no config.yml sem apagar configuracoes existentes.
     * Tambem migra configuracoes antigas que foram substituidas.
     */
    private void ensureConfigDefaults() {
        getConfig().addDefault("tempo-limite-login-segundos", 60);
        getConfig().addDefault("max-tentativas-login", 3);
        getConfig().addDefault("bloqueio-apos-exceder-tentativas-minutos", 5);
        getConfig().addDefault("minimo-caracteres-senha", PasswordUtils.DEFAULT_MIN_PASSWORD_LENGTH);
        getConfig().addDefault("maximo-caracteres-senha", PasswordUtils.DEFAULT_MAX_PASSWORD_LENGTH);
        getConfig().addDefault("max-contas-por-ip", 1);
        getConfig().addDefault("max-ips-por-conta", 1);

        // Se o servidor ainda usa a configuracao antiga, migra para os limites atuais.
        if (getConfig().contains("tamanho-minimo-senha")) {
            getConfig().set("minimo-caracteres-senha", PasswordUtils.DEFAULT_MIN_PASSWORD_LENGTH);
            getConfig().set("maximo-caracteres-senha", PasswordUtils.DEFAULT_MAX_PASSWORD_LENGTH);
        }

        getConfig().set("tamanho-minimo-senha", null);
        getConfig().options().copyDefaults(true);
        saveConfig();
        reloadConfig();
    }

    @Override
    public void onDisable() {
        if (playerDataManager != null) {
            playerDataManager.save();
        }
        getLogger().info("AuthSystem desativado.");
    }

    public PlayerDataManager getPlayerDataManager() { return playerDataManager; }
    public SessionManager getSessionManager() { return sessionManager; }
    public LoginProtection getLoginProtection() { return loginProtection; }
    public PremiumAuthenticator getPremiumAuthenticator() { return premiumAuthenticator; }
    public MessagesManager getMessagesManager() { return messagesManager; }
}
