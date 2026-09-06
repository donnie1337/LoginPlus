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

import java.io.File;

public class AuthSystem extends JavaPlugin {
    private static final int DEFAULT_MAX_ACCOUNTS_PER_IP = 1;
    private static final int DEFAULT_MAX_IPS_PER_ACCOUNT = 1;

    private PlayerDataManager playerDataManager;
    private SessionManager sessionManager;
    private LoginProtection loginProtection;
    private PremiumAuthenticator premiumAuthenticator;
    private MessagesManager messagesManager;

    @Override
    public void onEnable() {
        File arquivoConfig = new File(getDataFolder(), "config.yml");
        if (!arquivoConfig.exists()) {
            saveDefaultConfig();
        } else {
            reloadConfig();
        }

        ensureConfigDefaults();
        validarConfiguracoes();
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
        getLogger().info("Senha: " + getConfig().getInt("minimo-caracteres-senha", PasswordUtils.DEFAULT_MIN_PASSWORD_LENGTH)
                + " a " + getConfig().getInt("maximo-caracteres-senha", PasswordUtils.DEFAULT_MAX_PASSWORD_LENGTH) + " caracteres.");
        getLogger().info("Limite de contas autenticadas por IP: " + getConfig().getInt("max-contas-por-ip", DEFAULT_MAX_ACCOUNTS_PER_IP));
        getLogger().info("Limite de IPs por conta: " + getConfig().getInt("max-ips-por-conta", DEFAULT_MAX_IPS_PER_ACCOUNT));
    }

    /** Adiciona apenas opcoes ausentes, preservando tudo que o servidor ja configurou. */
    private void ensureConfigDefaults() {
        getConfig().addDefault("tempo-limite-login-segundos", 60);
        getConfig().addDefault("max-tentativas-login", 3);
        getConfig().addDefault("bloqueio-apos-exceder-tentativas-minutos", 5);
        getConfig().addDefault("minimo-caracteres-senha", PasswordUtils.DEFAULT_MIN_PASSWORD_LENGTH);
        getConfig().addDefault("maximo-caracteres-senha", PasswordUtils.DEFAULT_MAX_PASSWORD_LENGTH);
        getConfig().addDefault("max-contas-por-ip", DEFAULT_MAX_ACCOUNTS_PER_IP);
        getConfig().addDefault("max-ips-por-conta", DEFAULT_MAX_IPS_PER_ACCOUNT);

        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    /** Corrige valores invalidos da configuracao antes de iniciar o fluxo de autenticacao. */
    private void validarConfiguracoes() {
        int minimoSenha = getConfig().getInt("minimo-caracteres-senha", PasswordUtils.DEFAULT_MIN_PASSWORD_LENGTH);
        int maximoSenha = getConfig().getInt("maximo-caracteres-senha", PasswordUtils.DEFAULT_MAX_PASSWORD_LENGTH);
        int maxContasIp = getConfig().getInt("max-contas-por-ip", DEFAULT_MAX_ACCOUNTS_PER_IP);
        int maxIpsConta = getConfig().getInt("max-ips-por-conta", DEFAULT_MAX_IPS_PER_ACCOUNT);

        if (minimoSenha < 1) {
            minimoSenha = PasswordUtils.DEFAULT_MIN_PASSWORD_LENGTH;
            getConfig().set("minimo-caracteres-senha", minimoSenha);
            getLogger().warning("minimo-caracteres-senha invalido. Usando " + minimoSenha + ".");
        }

        if (maximoSenha < minimoSenha) {
            maximoSenha = Math.max(PasswordUtils.DEFAULT_MAX_PASSWORD_LENGTH, minimoSenha);
            getConfig().set("maximo-caracteres-senha", maximoSenha);
            getLogger().warning("maximo-caracteres-senha menor que o minimo. Usando " + maximoSenha + ".");
        }

        if (maxContasIp < 0) {
            getConfig().set("max-contas-por-ip", DEFAULT_MAX_ACCOUNTS_PER_IP);
            getLogger().warning("max-contas-por-ip invalido. Usando " + DEFAULT_MAX_ACCOUNTS_PER_IP + ".");
        }

        if (maxIpsConta < 0) {
            getConfig().set("max-ips-por-conta", DEFAULT_MAX_IPS_PER_ACCOUNT);
            getLogger().warning("max-ips-por-conta invalido. Usando " + DEFAULT_MAX_IPS_PER_ACCOUNT + ".");
        }

        saveConfig();
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
