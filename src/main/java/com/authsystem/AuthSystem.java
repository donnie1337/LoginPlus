package com.authsystem;

import com.authsystem.commands.LoginCommand;
import com.authsystem.commands.RegisterCommand;
import com.authsystem.listeners.AntiBypassListener;
import com.authsystem.listeners.AuthListener;
import com.authsystem.listeners.PremiumVerificationListener;
import com.authsystem.manager.LoginProtection;
import com.authsystem.manager.MessagesManager;
import com.authsystem.manager.MojangRateLimiter;
import com.authsystem.manager.PlayerDataManager;
import com.authsystem.manager.PremiumAccountManager;
import com.authsystem.manager.SessionManager;
import com.authsystem.util.HashProcessingLimiter;
import com.authsystem.util.PremiumAuthenticator;
import com.authsystem.util.PremiumLoginVerifier;
import com.authsystem.util.PasswordUtils;
import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.Locale;

public class AuthSystem extends JavaPlugin {
    private static final int DEFAULT_MAX_ACCOUNTS_PER_IP = 1;
    private static final int DEFAULT_MAX_IPS_PER_ACCOUNT = 1;
    private static final int DEFAULT_MAX_MOJANG_CHECKS_PER_MINUTE = 30;
    private static final int DEFAULT_MAX_PBKDF2_CONCURRENT = 2;
    private static final int DEFAULT_MAX_PREMIUM_CHECKS_CONCURRENT = 4;
    private static final int DEFAULT_MAX_PENDING_PREMIUM_GLOBAL = 100;
    private static final int DEFAULT_MAX_PENDING_PREMIUM_PER_IP = 3;
    private static final String DEFAULT_PREMIUM_FAILURE_ACTION = "kick";

    private PlayerDataManager playerDataManager;
    private SessionManager sessionManager;
    private LoginProtection loginProtection;
    private PremiumAuthenticator premiumAuthenticator;
    private PremiumAccountManager premiumAccountManager;
    private MojangRateLimiter mojangRateLimiter;
    private MessagesManager messagesManager;
    private HashProcessingLimiter hashProcessingLimiter;
    private BukkitTask securityCleanupTask;
    private PremiumLoginVerifier premiumLoginVerifier;
    private RegisterCommand registerCommand;
    private PremiumVerificationListener premiumVerificationListener;

    @Override
    public void onEnable() {
        File arquivoConfig = new File(getDataFolder(), "config.yml");
        if (!arquivoConfig.exists()) saveDefaultConfig(); else reloadConfig();
        ensureConfigDefaults();
        validarConfiguracoes();

        playerDataManager = new PlayerDataManager(this);
        sessionManager = new SessionManager();
        loginProtection = new LoginProtection();
        premiumAuthenticator = new PremiumAuthenticator();
        premiumAccountManager = new PremiumAccountManager(this);
        mojangRateLimiter = new MojangRateLimiter();
        messagesManager = new MessagesManager(this);
        hashProcessingLimiter = new HashProcessingLimiter();
        premiumLoginVerifier = new PremiumLoginVerifier(
                getConfig().getInt("seguranca.max-verificacoes-premium-simultaneas", DEFAULT_MAX_PREMIUM_CHECKS_CONCURRENT));

        getCommand("login").setExecutor(new LoginCommand(this));
        registerCommand = new RegisterCommand(this);
        getCommand("registro").setExecutor(registerCommand);
        getServer().getPluginManager().registerEvents(new AuthListener(this), this);
        getServer().getPluginManager().registerEvents(new AntiBypassListener(this), this);
        premiumVerificationListener = new PremiumVerificationListener(this, premiumLoginVerifier, premiumAuthenticator,
                getConfig().getInt("seguranca.max-handshakes-premium-pendentes", DEFAULT_MAX_PENDING_PREMIUM_GLOBAL),
                getConfig().getInt("seguranca.max-handshakes-premium-por-ip", DEFAULT_MAX_PENDING_PREMIUM_PER_IP));
        PacketEvents.getAPI().getEventManager().registerListener(premiumVerificationListener);

        securityCleanupTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            loginProtection.cleanupExpired();
            registerCommand.cleanupExpired();
            premiumLoginVerifier.cleanupExpired();
            premiumVerificationListener.cleanupExpired();
            mojangRateLimiter.cleanupExpired();
        }, 20L * 60L, 20L * 60L);

        getLogger().info("AuthSystem ativado com autenticacao premium criptografica!");
        getLogger().info("Senha: " + getConfig().getInt("minimo-caracteres-senha", 7) + " a " + getConfig().getInt("maximo-caracteres-senha", 16) + " caracteres.");
        getLogger().info("PBKDF2-HMAC-SHA256: " + getPasswordIterations() + " iteracoes (configuravel de " + PasswordUtils.MIN_ITERATIONS + " a " + PasswordUtils.MAX_ITERATIONS + ").");
        getLogger().info("Limite de contas autenticadas por IP: " + getConfig().getInt("max-contas-por-ip", DEFAULT_MAX_ACCOUNTS_PER_IP));
        getLogger().info("Limite de IPs por conta: " + getConfig().getInt("max-ips-por-conta", DEFAULT_MAX_IPS_PER_ACCOUNT));
        getLogger().info("Limite de verificacoes Mojang por IP: " + getConfig().getInt("max-verificacoes-mojang-por-minuto", DEFAULT_MAX_MOJANG_CHECKS_PER_MINUTE));
        getLogger().info("Limite global de PBKDF2 simultaneos: " + getConfig().getInt("seguranca.max-processamentos-pbkdf2-simultaneos", DEFAULT_MAX_PBKDF2_CONCURRENT));
        getLogger().info("Limite global de verificacoes premium simultaneas: " + getConfig().getInt("seguranca.max-verificacoes-premium-simultaneas", DEFAULT_MAX_PREMIUM_CHECKS_CONCURRENT));
        getLogger().info("Limite global de handshakes premium pendentes: " + getConfig().getInt("seguranca.max-handshakes-premium-pendentes", DEFAULT_MAX_PENDING_PREMIUM_GLOBAL));
        getLogger().info("Limite de handshakes premium por IP: " + getConfig().getInt("seguranca.max-handshakes-premium-por-ip", DEFAULT_MAX_PENDING_PREMIUM_PER_IP));
        getLogger().info("Falha na verificacao premium: " + getPremiumFailureAction());
    }

    private void ensureConfigDefaults() {
        getConfig().addDefault("tempo-limite-login-segundos", 60);
        getConfig().addDefault("max-tentativas-login", 3);
        getConfig().addDefault("bloqueio-apos-exceder-tentativas-minutos", 5);
        getConfig().addDefault("minimo-caracteres-senha", PasswordUtils.DEFAULT_MIN_PASSWORD_LENGTH);
        getConfig().addDefault("maximo-caracteres-senha", PasswordUtils.DEFAULT_MAX_PASSWORD_LENGTH);
        getConfig().addDefault("max-contas-por-ip", DEFAULT_MAX_ACCOUNTS_PER_IP);
        getConfig().addDefault("max-ips-por-conta", DEFAULT_MAX_IPS_PER_ACCOUNT);
        getConfig().addDefault("max-verificacoes-mojang-por-minuto", DEFAULT_MAX_MOJANG_CHECKS_PER_MINUTE);
        getConfig().addDefault("bloquear-conexao-quando-limite-ip-atingido", true);
        getConfig().addDefault("registro.max-tentativas-por-ip", 3);
        getConfig().addDefault("registro.janela-minutos", 5);
        getConfig().addDefault("registro.cooldown-segundos", 30);
        getConfig().addDefault("seguranca.pbkdf2-iteracoes", PasswordUtils.DEFAULT_ITERATIONS);
        getConfig().addDefault("seguranca.max-processamentos-pbkdf2-simultaneos", DEFAULT_MAX_PBKDF2_CONCURRENT);
        getConfig().addDefault("seguranca.max-verificacoes-premium-simultaneas", DEFAULT_MAX_PREMIUM_CHECKS_CONCURRENT);
        getConfig().addDefault("seguranca.max-handshakes-premium-pendentes", DEFAULT_MAX_PENDING_PREMIUM_GLOBAL);
        getConfig().addDefault("seguranca.max-handshakes-premium-por-ip", DEFAULT_MAX_PENDING_PREMIUM_PER_IP);
        getConfig().addDefault("seguranca.acao-falha-verificacao-premium", DEFAULT_PREMIUM_FAILURE_ACTION);
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    private void validarConfiguracoes() {
        int minimoSenha = getConfig().getInt("minimo-caracteres-senha", PasswordUtils.DEFAULT_MIN_PASSWORD_LENGTH);
        int maximoSenha = getConfig().getInt("maximo-caracteres-senha", PasswordUtils.DEFAULT_MAX_PASSWORD_LENGTH);
        int maxContasIp = getConfig().getInt("max-contas-por-ip", DEFAULT_MAX_ACCOUNTS_PER_IP);
        int maxIpsConta = getConfig().getInt("max-ips-por-conta", DEFAULT_MAX_IPS_PER_ACCOUNT);
        int maxConsultasMojang = getConfig().getInt("max-verificacoes-mojang-por-minuto", DEFAULT_MAX_MOJANG_CHECKS_PER_MINUTE);
        int pbkdf2Iteracoes = getConfig().getInt("seguranca.pbkdf2-iteracoes", PasswordUtils.DEFAULT_ITERATIONS);
        int maxPbkdf2 = getConfig().getInt("seguranca.max-processamentos-pbkdf2-simultaneos", DEFAULT_MAX_PBKDF2_CONCURRENT);
        int maxPremium = getConfig().getInt("seguranca.max-verificacoes-premium-simultaneas", DEFAULT_MAX_PREMIUM_CHECKS_CONCURRENT);
        int maxPendingGlobal = getConfig().getInt("seguranca.max-handshakes-premium-pendentes", DEFAULT_MAX_PENDING_PREMIUM_GLOBAL);
        int maxPendingPerIp = getConfig().getInt("seguranca.max-handshakes-premium-por-ip", DEFAULT_MAX_PENDING_PREMIUM_PER_IP);
        String premiumFailureAction = getConfig().getString("seguranca.acao-falha-verificacao-premium", DEFAULT_PREMIUM_FAILURE_ACTION);

        if (minimoSenha < 1) { minimoSenha = PasswordUtils.DEFAULT_MIN_PASSWORD_LENGTH; getConfig().set("minimo-caracteres-senha", minimoSenha); }
        if (maximoSenha < minimoSenha) { maximoSenha = Math.max(PasswordUtils.DEFAULT_MAX_PASSWORD_LENGTH, minimoSenha); getConfig().set("maximo-caracteres-senha", maximoSenha); }
        if (maxContasIp < 0) getConfig().set("max-contas-por-ip", DEFAULT_MAX_ACCOUNTS_PER_IP);
        if (maxIpsConta < 0) getConfig().set("max-ips-por-conta", DEFAULT_MAX_IPS_PER_ACCOUNT);
        if (maxConsultasMojang < 0) getConfig().set("max-verificacoes-mojang-por-minuto", DEFAULT_MAX_MOJANG_CHECKS_PER_MINUTE);
        if (pbkdf2Iteracoes < PasswordUtils.MIN_ITERATIONS || pbkdf2Iteracoes > PasswordUtils.MAX_ITERATIONS) {
            getConfig().set("seguranca.pbkdf2-iteracoes", PasswordUtils.DEFAULT_ITERATIONS);
        }
        if (maxPbkdf2 < 1) getConfig().set("seguranca.max-processamentos-pbkdf2-simultaneos", DEFAULT_MAX_PBKDF2_CONCURRENT);
        if (maxPremium < 1) getConfig().set("seguranca.max-verificacoes-premium-simultaneas", DEFAULT_MAX_PREMIUM_CHECKS_CONCURRENT);
        if (maxPendingGlobal < 1) getConfig().set("seguranca.max-handshakes-premium-pendentes", DEFAULT_MAX_PENDING_PREMIUM_GLOBAL);
        if (maxPendingPerIp < 1) getConfig().set("seguranca.max-handshakes-premium-por-ip", DEFAULT_MAX_PENDING_PREMIUM_PER_IP);
        if (premiumFailureAction == null || (!premiumFailureAction.equalsIgnoreCase("cracked") && !premiumFailureAction.equalsIgnoreCase("kick"))) {
            getConfig().set("seguranca.acao-falha-verificacao-premium", DEFAULT_PREMIUM_FAILURE_ACTION);
        }
        saveConfig();
    }

    @Override
    public void onDisable() {
        if (securityCleanupTask != null) securityCleanupTask.cancel();
        if (playerDataManager != null) playerDataManager.shutdown();
        if (premiumAccountManager != null) premiumAccountManager.shutdown();
        getLogger().info("AuthSystem desativado.");
    }

    public int getPasswordIterations() {
        return Math.max(PasswordUtils.MIN_ITERATIONS,
                Math.min(PasswordUtils.MAX_ITERATIONS,
                        getConfig().getInt("seguranca.pbkdf2-iteracoes", PasswordUtils.DEFAULT_ITERATIONS)));
    }

    public int getLoginTimeoutSeconds() {
        return Math.max(1, getConfig().getInt("tempo-limite-login-segundos", 60));
    }

    public String getPremiumFailureAction() {
        String action = getConfig().getString("seguranca.acao-falha-verificacao-premium", DEFAULT_PREMIUM_FAILURE_ACTION);
        if (action == null) return DEFAULT_PREMIUM_FAILURE_ACTION;
        action = action.trim().toLowerCase(Locale.ROOT);
        return action.equals("kick") ? "kick" : DEFAULT_PREMIUM_FAILURE_ACTION;
    }

    public boolean isAuthenticated(Player player) {
        return player != null && sessionManager != null && sessionManager.isAuthenticated(player);
    }

    public PlayerDataManager getPlayerDataManager() { return playerDataManager; }
    public SessionManager getSessionManager() { return sessionManager; }
    public LoginProtection getLoginProtection() { return loginProtection; }
    public PremiumAuthenticator getPremiumAuthenticator() { return premiumAuthenticator; }
    public PremiumAccountManager getPremiumAccountManager() { return premiumAccountManager; }
    public MojangRateLimiter getMojangRateLimiter() { return mojangRateLimiter; }
    public MessagesManager getMessagesManager() { return messagesManager; }
    public HashProcessingLimiter getHashProcessingLimiter() { return hashProcessingLimiter; }
}
