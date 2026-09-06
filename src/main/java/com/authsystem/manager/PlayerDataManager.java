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
            plugin.getDataFolder().mkdirs();
            try { file.createNewFile(); }
            catch (IOException e) { plugin.getLogger().log(Level.SEVERE, "Nao foi possivel criar playerdata.yml", e); }
        }
        data = YamlConfiguration.loadConfiguration(file);
    }

    /** Fecha o agendamento async e grava o estado mais recente sem permitir que um snapshot antigo o sobrescreva. */
    public void shutdown() {
        synchronized (this) {
            shuttingDown = true;
            dataVersion.incrementAndGet();
        }
        save();
    }

    /** Salva imediatamente, serializando a escrita com qualquer save async em andamento. */
    public void save() {
        final String snapshot;
        synchronized (this) { snapshot = data.saveToString(); }
        synchronized (ioLock) {
            try { Files.writeString(file.toPath(), snapshot, StandardCharsets.UTF_8); }
            catch (IOException e) { plugin.getLogger().log(Level.SEVERE, "Nao foi possivel salvar playerdata.yml", e); }
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
                    // O shutdown pode ter ocorrido enquanto este snapshot aguardava o lock de I/O.
                    // Nesse caso, nunca permita que um snapshot antigo sobrescreva o save final.
                    if (shuttingDown) {
                        asyncSaveScheduled = false;
                        return;
                    }
                }
                try {
                    Files.writeString(file.toPath(), snapshot, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    plugin.getLogger().log(Level.SEVERE, "Nao foi possivel salvar playerdata.yml", e);
                    break;
                }
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

        synchronized (this) { asyncSaveScheduled = false; }
    }

    private String key(String username) { return "players." + username.toLowerCase(Locale.ROOT); }
    public synchronized boolean isRegistered(String username) { return username != null && data.contains(key(username) + ".senha"); }

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

    /**
     * Mantido por compatibilidade com a API interna. O fluxo normal de registro usa
     * registerHashed(), que calcula PBKDF2 fora da thread principal.
     */
    public synchronized boolean register(String username, String password, String ip, UUID uuid) {
        if (password == null) return false;
        String salt = PasswordUtils.generateSalt();
        int iterations = plugin.getPasswordIterations();
        String hash = PasswordUtils.hash(password, salt, iterations);
        return registerHashed(username, salt, hash, iterations, ip, uuid);
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

    /** Atualiza o hash somente se a versão calculada for pelo menos tão forte quanto a atual. */
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

    /**
     * Mantido por compatibilidade. Para o fluxo de login, prefira upgradePasswordHash()
     * com o PBKDF2 calculado assincronamente pelo comando.
     */
    public synchronized void upgradePassword(String username, String password) {
        if (password == null || !isRegistered(username)) return;
        String salt = PasswordUtils.generateSalt();
        int iterations = plugin.getPasswordIterations();
        String hash = PasswordUtils.hash(password, salt, iterations);
        upgradePasswordHash(username, salt, hash, iterations);
    }
}
