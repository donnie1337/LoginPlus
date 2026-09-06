package com.authsystem.manager;

import com.authsystem.AuthSystem;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/** Guarda os IPs usados por contas premium identificadas pelo UUID da Mojang. */
public final class PremiumAccountManager {
    private final AuthSystem plugin;
    private final File file;
    private final Object ioLock = new Object();
    private final AtomicLong dataVersion = new AtomicLong();
    private FileConfiguration data;
    private boolean asyncSaveScheduled;
    private volatile boolean shuttingDown;

    public PremiumAccountManager(AuthSystem plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "premiumdata.yml");
        load();
    }

    private void load() {
        if (!file.exists()) {
            plugin.getDataFolder().mkdirs();
            try {
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Nao foi possivel criar premiumdata.yml", e);
            }
        }
        data = YamlConfiguration.loadConfiguration(file);
    }

    private String key(UUID uuid) {
        return "players." + uuid;
    }

    public synchronized boolean canUseIp(UUID uuid, String ip, int limiteIps) {
        if (uuid == null || ip == null || ip.isBlank() || limiteIps <= 0) return true;
        List<String> ips = getIps(uuid);
        return ips.contains(ip) || ips.size() < limiteIps;
    }

    public synchronized void addIp(UUID uuid, String ip) {
        if (uuid == null || ip == null || ip.isBlank()) return;
        String base = key(uuid);
        List<String> ips = getIps(uuid);
        if (ips.contains(ip)) return;
        ips.add(ip);
        data.set(base + ".ips", ips);
        scheduleAsyncSave();
    }

    private List<String> getIps(UUID uuid) {
        List<String> ips = new ArrayList<>();
        for (String ip : data.getStringList(key(uuid) + ".ips")) {
            if (ip != null && !ip.isBlank() && !ips.contains(ip)) ips.add(ip);
        }
        return ips;
    }

    /** Salva imediatamente e impede qualquer snapshot async antigo de sobrescrever o estado final. */
    public void shutdown() {
        synchronized (this) {
            shuttingDown = true;
            asyncSaveScheduled = false;
        }
        save();
    }

    /** Salva imediatamente; usado no desligamento para garantir que o estado em memoria seja persistido. */
    public void save() {
        final String snapshot;
        synchronized (this) {
            snapshot = data.saveToString();
        }
        synchronized (ioLock) {
            try {
                Files.writeString(file.toPath(), snapshot, StandardCharsets.UTF_8);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Nao foi possivel salvar premiumdata.yml", e);
            }
        }
    }

    /** Persiste snapshots em ordem e cancela qualquer gravacao ao iniciar o desligamento. */
    private synchronized void scheduleAsyncSave() {
        if (shuttingDown) return;
        dataVersion.incrementAndGet();
        if (asyncSaveScheduled) return;
        asyncSaveScheduled = true;
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, this::flushAsync, 1L);
    }

    private void flushAsync() {
        while (true) {
            final long snapshotVersion;
            final String snapshot;
            synchronized (this) {
                if (shuttingDown) {
                    asyncSaveScheduled = false;
                    return;
                }
                snapshotVersion = dataVersion.get();
                snapshot = data.saveToString();
            }

            synchronized (ioLock) {
                synchronized (this) {
                    if (shuttingDown) {
                        asyncSaveScheduled = false;
                        return;
                    }
                }
                try {
                    Files.writeString(file.toPath(), snapshot, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    plugin.getLogger().log(Level.SEVERE, "Nao foi possivel salvar premiumdata.yml", e);
                    break;
                }
            }

            synchronized (this) {
                if (shuttingDown) {
                    asyncSaveScheduled = false;
                    return;
                }
                if (snapshotVersion == dataVersion.get()) {
                    asyncSaveScheduled = false;
                    return;
                }
            }
        }

        synchronized (this) {
            asyncSaveScheduled = false;
        }
    }
}
