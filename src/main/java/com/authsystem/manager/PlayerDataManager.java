package com.authsystem.manager;

import com.authsystem.AuthSystem;
import com.authsystem.util.PasswordUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

    /** Conta quantas contas cadastradas estao vinculadas ao IP informado. */
    public synchronized int countAccountsForIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return 0;
        }

        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) {
            return 0;
        }

        int total = 0;
        for (String username : players.getKeys(false)) {
            List<String> ips = getIps(username);
            if (ips.contains(ip)) {
                total++;
            }
        }
        return total;
    }

    /**
     * Verifica se a conta pode ser utilizada a partir do IP informado.
     * Mantem compatibilidade com contas antigas que possuem apenas o campo "ip".
     */
    public synchronized boolean canUseIp(String username, String ip, int limiteIps) {
        if (ip == null || ip.isBlank() || limiteIps <= 0) {
            return true;
        }

        List<String> ips = getIps(username);
        if (ips.contains(ip)) {
            return true;
        }
        return ips.size() < limiteIps;
    }

    /** Adiciona o IP a lista de IPs permitidos da conta, sem duplicar. */
    public synchronized void addIp(String username, String ip) {
        if (ip == null || ip.isBlank()) {
            return;
        }

        String base = key(username);
        List<String> ips = getIps(username);
        if (!ips.contains(ip)) {
            ips.add(ip);
            data.set(base + ".ips", ips);
        }

        // Mantem o campo antigo para compatibilidade com dados ja existentes.
        if (!data.contains(base + ".ip")) {
            data.set(base + ".ip", ip);
        }
        save();
    }

    /** Retorna todos os IPs conhecidos da conta, incluindo o formato antigo. */
    private List<String> getIps(String username) {
        String base = key(username);
        List<String> ips = new ArrayList<>();

        String ipAntigo = data.getString(base + ".ip");
        if (ipAntigo != null && !ipAntigo.isBlank()) {
            ips.add(ipAntigo);
        }

        List<String> ipsSalvos = data.getStringList(base + ".ips");
        for (String ip : ipsSalvos) {
            if (ip != null && !ip.isBlank() && !ips.contains(ip)) {
                ips.add(ip);
            }
        }
        return ips;
    }

    /**
     * Registra a conta e aplica os limites configurados no servidor.
     * A validacao aqui evita que futuras chamadas internas contornem as regras do comando.
     */
    public synchronized boolean register(String username, String password, String ip) {
        if (username == null || username.isBlank() || isRegistered(username)) {
            return false;
        }

        int minSenha = plugin.getConfig().getInt("minimo-caracteres-senha", PasswordUtils.DEFAULT_MIN_PASSWORD_LENGTH);
        int maxSenha = plugin.getConfig().getInt("maximo-caracteres-senha", PasswordUtils.DEFAULT_MAX_PASSWORD_LENGTH);
        if (PasswordUtils.validatePassword(password, minSenha, maxSenha) != null) {
            return false;
        }

        int limiteContas = plugin.getConfig().getInt("max-contas-por-ip", 1);
        if (limiteContas > 0) {
            if (ip == null || ip.isBlank()) {
                return false;
            }

            if (countAccountsForIp(ip) >= limiteContas) {
                return false;
            }
        }

        String salt = PasswordUtils.generateSalt();
        String hash = PasswordUtils.hash(password, salt);
        data.set(key(username) + ".senha", hash);
        data.set(key(username) + ".salt", salt);
        data.set(key(username) + ".ip", ip);
        if (ip != null && !ip.isBlank()) {
            data.set(key(username) + ".ips", List.of(ip));
        }
        data.set(key(username) + ".registrado-em", System.currentTimeMillis());
        save();
        return true;
    }

    public boolean checkPassword(String username, String password) {
        String salt = data.getString(key(username) + ".salt");
        String hash = data.getString(key(username) + ".senha");
        if (salt == null || hash == null) {
            return false;
        }
        return PasswordUtils.verify(password, salt, hash);
    }
}
