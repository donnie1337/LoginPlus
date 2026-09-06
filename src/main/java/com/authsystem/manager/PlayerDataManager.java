package com.authsystem.manager;

import com.authsystem.AuthSystem;
import com.authsystem.util.PasswordUtils;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/** Guarda os dados de cadastro das contas, incluindo UUID, data de registro e IPs usados. */
public class PlayerDataManager {
    private static final DateTimeFormatter FORMATO_REGISTRO = DateTimeFormatter.ofPattern("ddMMyyyyHH");

    private final AuthSystem plugin;
    private final File file;
    private final Object ioLock = new Object();
    private final AtomicLong dataVersion = new AtomicLong();
    private FileConfiguration data;
    private boolean asyncSaveScheduled;
    private volatile boolean shuttingDown;

    public PlayerDataManager(AuthSystem plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "playerdata.yml");
        load();
    }

    private void load() {
        if (!file.exists()) {
            try {
                File parent = file.getParentFile();
                if (parent != null) Files.createDirectories(parent.toPath());
                Files.createFile(file.toPath());
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Nao foi possivel criar playerdata.yml", e);
            }
        }
        data = YamlConfiguration.loadConfiguration(file);
    }

    private String key(String username) {
        return "contas." + username.toLowerCase(Locale.ROOT);
    }

    public synchronized boolean isRegistered(String username) {
        return username != null && data.contains(key(username));
    }

    public synchronized boolean hasPassword(String username) {
        return isRegistered(username) && data.getString(key(username) + ".senha") != null;
    }

    public synchronized UUID getUuid(String username) {
        String value = data.getString(key(username) + ".uuid");
        if (value == null) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException e) { return null; }
    }

    public synchronized List<String> getIpsPublic(String username) {
        return new ArrayList<>(getIps(username));
    }

    private List<String> getIps(String username) {
        String base = key(username);
        List<String> ips = new ArrayList<>();
        String ipAntigo = data.getString(base + ".ip");
        if (ipAntigo != null && !ipAntigo.isBlank()) ips.add(ipAntigo);
        for (String ip : data.getStringList(base + ".ips")) {
            if (ip != null && !ip.isBlank() && !ips.contains(ip)) ips.add(ip);
        }
        return ips;
    }

    public synchronized boolean register(String username, String password, String ip, UUID uuid) {
        if (password == null) return false;
        String salt = PasswordUtils.generateSalt();
        String hash = PasswordUtils.hash(password, salt, plugin.getPasswordIterations());
        return registerHashed(username, salt, hash, plugin.getPasswordIterations(), ip, uuid);
    }

    public synchronized boolean registerHashed(String username, String salt, String hash, int iterations, String ip, UUID uuid) {
        if (username == null || username.isBlank() || isRegistered(username) || ip == null || ip.isBlank()
                || uuid == null || salt == null || hash == null || iterations < 1) return false;
        String base = key(username);
        data.set(base + ".senha", hash);
        data.set(base + ".salt", salt);
        data.set(base + ".iteracoes", iterations);
        data.set(base + ".uuid", uuid.toString());
        data.set(base + ".ip", ip);
        data.set(base + ".ips", List.of(ip));
        data.set(base + ".registrado-em", FORMATO_REGISTRO.format(LocalDateTime.now()));
        scheduleAsyncSave();
        return true;
    }

    public synchronized boolean checkPassword(String username, String password) {
        PasswordData passwordData = getPasswordData(username);
        return passwordData != null && PasswordUtils.verify(password, passwordData.salt(), passwordData.hash(), passwordData.iterations());
    }

    public synchronized boolean needsPasswordUpgrade(String username) {
        return data.getInt(key(username) + ".iteracoes", PasswordUtils.LEGACY_ITERATIONS) < plugin.getPasswordIterations();
    }

    public synchronized void upgradePasswordHash(String username, String salt, String hash, int iterations) {
        if (username == null || salt == null || hash == null || iterations < 1 || !isRegistered(username)) return;
        String base = key(username);
        data.set(base + ".salt", salt);
        data.set(base + ".senha", hash);
        data.set(base + ".iteracoes", iterations);
        scheduleAsyncSave();
    }

    public synchronized void upgradePassword(String username, String password) {
        if (password == null || !isRegistered(username)) return;
        String salt = PasswordUtils.generateSalt();
        String hash = PasswordUtils.hash(password, salt, plugin.getPasswordIterations());
        upgradePasswordHash(username, salt, hash, plugin.getPasswordIterations());
    }

    private PasswordData getPasswordData(String username) {
        if (username == null) return null;
        String base = key(username);
        String hash = data.getString(base + ".senha");
        String salt = data.getString(base + ".salt");
        if (hash == null || salt == null) return null;
        int iterations = data.getInt(base + ".iteracoes", PasswordUtils.LEGACY_ITERATIONS);
        return new PasswordData(hash, salt, iterations);
    }

    private record PasswordData(String hash, String salt, int iterations) {}

    private void scheduleAsyncSave() {
        if (shuttingDown || asyncSaveScheduled) return;
        asyncSaveScheduled = true;
        long version = dataVersion.incrementAndGet();
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> saveAsync(version), 1L);
    }

    private void saveAsync(long version) {
        FileConfiguration snapshot;
        synchronized (this) {
            if (shuttingDown) return;
            snapshot = new YamlConfiguration();
            for (String key : data.getKeys(true)) {
                if (!data.isConfigurationSection(key)) snapshot.set(key, data.get(key));
            }
            asyncSaveScheduled = false;
        }
        synchronized (ioLock) {
            try { snapshot.save(file); }
            catch (IOException e) { plugin.getLogger().log(Level.SEVERE, "Nao foi possivel salvar playerdata.yml", e); }
        }
        synchronized (this) {
            if (!shuttingDown && version < dataVersion.get()) scheduleAsyncSave();
        }
    }

    public void shutdown() {
        shuttingDown = true;
        synchronized (this) {
            try { data.save(file); }
            catch (IOException e) { plugin.getLogger().log(Level.SEVERE, "Nao foi possivel salvar playerdata.yml no shutdown", e); }
        }
    }
}
