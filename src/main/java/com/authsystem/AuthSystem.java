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
    }

    private void ensureConfigDefaults() {
        if (!getConfig().contains("max-contas-por-ip")) {
            getConfig().set("max-contas-por-ip", 1);
            saveConfig();
        }
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
