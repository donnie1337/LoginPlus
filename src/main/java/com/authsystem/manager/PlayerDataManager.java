package com.authsystem.manager;

import com.authsystem.AuthSystem;
import com.authsystem.util.PasswordUtils;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/**
 * Guarda os dados de cadastro (senha com hash + salt) em playerdata.yml,
 * dentro da pasta de dados do plugin. Simples e sem dependencias externas;
 * se o servidor crescer muito, considere migrar para SQLite/MySQL.
 */
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
            try {
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Nao foi possivel criar playerdata.yml", e);
            }
        }
        data = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Nao foi possivel salvar playerdata.yml", e);
        }
    }

    private String key(String username) {
        return "players." + username.toLowerCase();
    }

    public boolean isRegistered(String username) {
        return data.contains(key(username) + ".senha");
    }

    public void register(String username, String password) {
        String salt = PasswordUtils.generateSalt();
        String hash = PasswordUtils.hash(password, salt);
        data.set(key(username) + ".senha", hash);
        data.set(key(username) + ".salt", salt);
        data.set(key(username) + ".registrado-em", System.currentTimeMillis());
        save();
    }

    public boolean checkPassword(String username, String password) {
        String salt = data.getString(key(username) + ".salt");
        String hash = data.getString(key(username) + ".senha");
        if (salt == null || hash == null) {
            return false;
        }
        return PasswordUtils.verify(password, salt, hash);
    }

    public void changePassword(String username, String newPassword) {
        register(username, newPassword);
    }
}
