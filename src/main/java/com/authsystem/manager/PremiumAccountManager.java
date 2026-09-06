package com.authsystem.manager;

import com.authsystem.AuthSystem;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/** Guarda os IPs usados por contas premium identificadas pelo UUID da Mojang. */
public final class PremiumAccountManager {
    private final AuthSystem plugin;
    private final File file;
    private FileConfiguration data;

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
        if (uuid == null || ip == null || ip.isBlank() || limiteIps <= 0) {
            return true;
        }
        List<String> ips = getIps(uuid);
        return ips.contains(ip) || ips.size() < limiteIps;
    }

    public synchronized void addIp(UUID uuid, String ip) {
        if (uuid == null || ip == null || ip.isBlank()) {
            return;
        }
        String base = key(uuid);
        List<String> ips = getIps(uuid);
        if (ips.contains(ip)) {
            return;
        }
        ips.add(ip);
        data.set(base + ".ips", ips);
        save();
    }

    private List<String> getIps(UUID uuid) {
        List<String> ips = new ArrayList<>();
        for (String ip : data.getStringList(key(uuid) + ".ips")) {
            if (ip != null && !ip.isBlank() && !ips.contains(ip)) {
                ips.add(ip);
            }
        }
        return ips;
    }

    public synchronized void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Nao foi possivel salvar premiumdata.yml", e);
        }
    }
}
