package com.authsystem.manager;

import com.authsystem.AuthSystem;
import com.authsystem.util.PasswordUtils;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/** Guarda os dados de cadastro das contas, incluindo UUID, data de registro, IPs e identidade premium confirmada. */
public class PlayerDataManager {
    private static final DateTimeFormatter FORMATO_REGISTRO = DateTimeFormatter.ofPattern("ddMMyyyyHH");

    private final AuthSystem plugin;
    private final File file;
    private final File tempFile;
    private final Object ioLock = new Object();
    private final AtomicLong dataVersion = new AtomicLong();
    private FileConfiguration data;
    private boolean asyncSaveScheduled;
    private volatile boolean shuttingDown;

    public PlayerDataManager(AuthSystem plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "playerdata.yml");
        this.tempFile = new File(plugin.getDataFolder(), "playerdata.yml.tmp");
        load();
    }

    private void load() {
        if (!file.exists()) {
            plugin.getDataFolder().mkdirs();
            try { file.createNewFile(); }
            catch (IOException e) { plugin.getLogger().log(Level.SEVERE, "Nao foi possivel criar playerdata.yml", e); }
        }
        data = YamlConfiguration.loadConfiguration(file);
    }

    public void shutdown() {
        synchronized (this) {
            shuttingDown = true;
            dataVersion.incrementAndGet();
        }
        save();
    }

    public void save() {
        final String snapshot;
        synchronized (this) { snapshot = data.saveToString(); }
        synchronized (ioLock) { writeAtomically(snapshot); }
    }

    /** Grava o snapshot em arquivo temporario e troca o arquivo final de forma atomica sempre que suportado. */
    private void writeAtomically(String snapshot) {
        try {
            Files.writeString(tempFile.toPath(), snapshot, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Nao foi possivel salvar playerdata.yml", e);
            try { Files.deleteIfExists(tempFile.toPath()); }
            catch (IOException ignored) { }
        }
    }

    private synchronized void scheduleAsyncSave() {
        if (shuttingDown) return;
        dataVersion.incrementAndGet();
        if (asyncSaveScheduled) return;
        asyncSaveScheduled = true;
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, this::flushAsync, 1L);
    }

    private void flushAsync() {
        while (true) {
            final String snapshot;
            final long snapshotVersion;
            synchronized (this) {
                if (shuttingDown) {
                    asyncSaveScheduled = false;
                    return;
                }
                snapshot = data.saveToString();
                snapshotVersion = dataVersion.get();
            }
            synchronized (ioLock) {
                synchronized (this) {
                    if (shuttingDown) {
                        asyncSaveScheduled = false;
                        return;
                    }
                }
                writeAtomically(snapshot);
            }
            synchronized (this) {
                if (shuttingDown) {
                    asyncSaveScheduled = false;
                    return;
                }
                if (dataVersion.get() == snapshotVersion) {
                    asyncSaveScheduled = false;
                    return;
                }
            }
        }
    }

    private String key(String username) { return "players." + username.toLowerCase(Locale.ROOT); }
    public synchronized boolean isRegistered(String username) { return username != null && data.contains(key(username) + ".senha"); }

    /** Retorna true quando o nickname foi reservado pelo CargoPlus para uma identidade administrativa. */
    public synchronized boolean isProtectedIdentity(String username) {
        return username != null && data.getBoolean(key(username) + ".protegida", false);
    }

    /** Reserva um nickname para impedir que uma conta nova seja criada com ele. */
    public synchronized void protectIdentity(String username, UUID uuid) {
        if (username == null || username.isBlank() || uuid == null) return;
        String base = key(username);
        if (data.getBoolean(base + ".protegida", false)) return;
        data.set(base + ".protegida", true);
        data.set(base + ".uuid-protegido", uuid.toString());
        data.set(base + ".protegida-em", FORMATO_REGISTRO.format(LocalDateTime.now()));
        scheduleAsyncSave();
    }

    /** Retorna true quando o nickname ja foi confirmado como conta premium pela Mojang. */
    public synchronized boolean isPremiumIdentity(String username) {
        return username != null && data.getBoolean(key(username) + ".premium", false);
    }

    /** Retorna o UUID premium confirmado para o nickname. */
    public synchronized UUID getPremiumUuid(String username) {
        if (!isPremiumIdentity(username)) return null;
        String value = data.getString(key(username) + ".premium-uuid");
        if (value == null || value.isBlank()) return null;
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    /** Marca o nickname como premium somente depois de uma verificacao criptografica bem-sucedida. */
    public synchronized void markPremiumIdentity(String username, UUID uuid, String ip) {
        if (username == null || username.isBlank() || uuid == null) return;
        String base = key(username);
        UUID existing = getPremiumUuid(username);
        if (existing != null && !existing.equals(uuid)) {
            plugin.getLogger().warning("Tentativa de associar UUID premium diferente ao nickname " + username + ". Operacao ignorada.");
            return;
        }
        data.set(base + ".premium", true);
        data.set(base + ".premium-uuid", uuid.toString());
        if (ip != null && !ip.isBlank()) data.set(base + ".premium-ip", ip);
        data.set(base + ".premium-verificado-em", FORMATO_REGISTRO.format(LocalDateTime.now()));
        scheduleAsyncSave();
    }

    public synchronized PasswordData getPasswordData(String username) {
        if (username == null) return null;
        String base = key(username);
        String salt = data.getString(base + ".salt");
        String hash = data.getString(base + ".senha");
        if (salt == null || hash == null) return null;
        int iteracoes = data.getInt(base + ".iteracoes", PasswordUtils.LEGACY_ITERATIONS);
        return new PasswordData(salt, hash, iteracoes);
    }

    public record PasswordData(String salt, String hash, int iterations) {}

    public synchronized boolean canUseIp(String username, String ip, int limiteIps) {
        if (ip == null || ip.isBlank() || limiteIps <= 0) return true;
        List<String> ips = getIps(username);
        return ips.contains(ip) || ips.size() < limiteIps;
    }

    /** Verifica e registra o IP na mesma operacao, evitando corrida entre logins simultaneos. */
    public synchronized boolean tryAddIp(String username, String ip, int limiteIps) {
        if (username == null || username.isBlank() || ip == null || ip.isBlank()) return false;
        List<String> ips = getIps(username);
        if (ips.contains(ip)) return true;
        if (limiteIps > 0 && ips.size() >= limiteIps) return false;
        String base = key(username);
        ips.add(ip);
        data.set(base + ".ips", ips);
        if (!data.contains(base + ".ip")) data.set(base + ".ip", ip);
        scheduleAsyncSave();
        return true;
    }

    public synchronized void addIp(String username, String ip) {
        if (username == null || ip == null || ip.isBlank()) return;
        String base = key(username);
        List<String> ips = getIps(username);
        if (!ips.contains(ip)) {
            ips.add(ip);
            data.set(base + ".ips", ips);
        }
        if (!data.contains(base + ".ip")) data.set(base + ".ip", ip);
        scheduleAsyncSave();
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
        int iterations = plugin.getPasswordIterations();
        String hash = PasswordUtils.hash(password, salt, iterations);
        return registerHashed(username, salt, hash, iterations, ip, uuid);
    }

    public synchronized boolean registerHashed(String username, String salt, String hash, int iterations, String ip, UUID uuid) {
        if (username == null || username.isBlank() || isRegistered(username) || isPremiumIdentity(username) || isProtectedIdentity(username)
                || ip == null || ip.isBlank() || uuid == null || salt == null || hash == null || iterations < 1) return false;
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
        int currentIterations = data.getInt(base + ".iteracoes", PasswordUtils.LEGACY_ITERATIONS);
        if (iterations < currentIterations) return;
        data.set(base + ".salt", salt);
        data.set(base + ".senha", hash);
        data.set(base + ".iteracoes", iterations);
        scheduleAsyncSave();
    }

    public synchronized void upgradePassword(String username, String password) {
        if (password == null || !isRegistered(username)) return;
        String salt = PasswordUtils.generateSalt();
        int iterations = plugin.getPasswordIterations();
        String hash = PasswordUtils.hash(password, salt, iterations);
        upgradePasswordHash(username, salt, hash, iterations);
    }
}
