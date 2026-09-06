package com.authsystem.manager;

import com.authsystem.AuthSystem;
import com.authsystem.util.PasswordUtils;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/** Guarda os dados de cadastro das contas e os IPs usados por cada conta. */
public class PlayerDataManager {
    private final AuthSystem plugin;
    private final File file;
    private FileConfiguration data;

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

    public synchronized void save() {
        try { data.save(file); }
        catch (IOException e) { plugin.getLogger().log(Level.SEVERE, "Nao foi possivel salvar playerdata.yml", e); }
    }

    private String key(String username) { return "players." + username.toLowerCase(); }
    public synchronized boolean isRegistered(String username) { return username != null && data.contains(key(username) + ".senha"); }

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
        save();
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

    public synchronized boolean register(String username, String password, String ip) {
        if (username == null || username.isBlank() || isRegistered(username) || ip == null || ip.isBlank()) return false;
        int minSenha = plugin.getConfig().getInt("minimo-caracteres-senha", PasswordUtils.DEFAULT_MIN_PASSWORD_LENGTH);
        int maxSenha = plugin.getConfig().getInt("maximo-caracteres-senha", PasswordUtils.DEFAULT_MAX_PASSWORD_LENGTH);
        if (PasswordUtils.validatePassword(password, minSenha, maxSenha) != null) return false;
        String salt = PasswordUtils.generateSalt();
        String base = key(username);
        data.set(base + ".senha", PasswordUtils.hash(password, salt));
        data.set(base + ".salt", salt);
        data.set(base + ".iteracoes", PasswordUtils.CURRENT_ITERATIONS);
        data.set(base + ".ip", ip);
        data.set(base + ".ips", List.of(ip));
        data.set(base + ".registrado-em", System.currentTimeMillis());
        save();
        return true;
    }

    public synchronized boolean checkPassword(String username, String password) {
        String salt = data.getString(key(username) + ".salt");
        String hash = data.getString(key(username) + ".senha");
        if (salt == null || hash == null) return false;
        int iteracoes = data.getInt(key(username) + ".iteracoes", PasswordUtils.LEGACY_ITERATIONS);
        return PasswordUtils.verify(password, salt, hash, iteracoes);
    }

    public synchronized boolean needsPasswordUpgrade(String username) {
        return data.getInt(key(username) + ".iteracoes", PasswordUtils.LEGACY_ITERATIONS) < PasswordUtils.CURRENT_ITERATIONS;
    }

    public synchronized void upgradePassword(String username, String password) {
        if (password == null || !isRegistered(username)) return;
        String salt = PasswordUtils.generateSalt();
        String base = key(username);
        data.set(base + ".salt", salt);
        data.set(base + ".senha", PasswordUtils.hash(password, salt));
        data.set(base + ".iteracoes", PasswordUtils.CURRENT_ITERATIONS);
        save();
    }
}
