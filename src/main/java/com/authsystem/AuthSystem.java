package com.authsystem;

import com.authsystem.commands.LoginCommand;
import com.authsystem.commands.RegisterCommand;
import com.authsystem.listeners.AntiBypassListener;
import com.authsystem.listeners.AuthListener;
import com.authsystem.manager.LoginProtection;
import com.authsystem.manager.PlayerDataManager;
import com.authsystem.manager.SessionManager;
import org.bukkit.plugin.java.JavaPlugin;

public class AuthSystem extends JavaPlugin {

    private static AuthSystem instance;

    private PlayerDataManager playerDataManager;
    private SessionManager sessionManager;
    private LoginProtection loginProtection;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        this.playerDataManager = new PlayerDataManager(this);
        this.sessionManager = new SessionManager();
        this.loginProtection = new LoginProtection();

        getCommand("login").setExecutor(new LoginCommand(this));
        getCommand("registro").setExecutor(new RegisterCommand(this));

        getServer().getPluginManager().registerEvents(new AuthListener(this), this);
        getServer().getPluginManager().registerEvents(new AntiBypassListener(this), this);

        getLogger().info("AuthSystem ativado com sucesso!");
    }

    @Override
    public void onDisable() {
        if (playerDataManager != null) {
            playerDataManager.save();
        }
        getLogger().info("AuthSystem desativado.");
    }

    public static AuthSystem getInstance() {
        return instance;
    }

    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public LoginProtection getLoginProtection() {
        return loginProtection;
    }
}
