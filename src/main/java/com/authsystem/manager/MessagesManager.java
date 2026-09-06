package com.authsystem.manager;

import com.authsystem.AuthSystem;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/** Carrega os textos dos Titles de autenticacao a partir de mensagens/titulos.yml. */
public class MessagesManager {
    private final AuthSystem plugin;
    private final File file;
    private FileConfiguration config;

    public MessagesManager(AuthSystem plugin) {
        this.plugin = plugin;
        File pasta = new File(plugin.getDataFolder(), "mensagens");
        if (!pasta.exists()) {
            pasta.mkdirs();
        }

        this.file = new File(pasta, "titulos.yml");
        criarArquivoSeNecessario();
        reload();
    }

    private void criarArquivoSeNecessario() {
        if (file.exists()) {
            return;
        }

        try {
            plugin.saveResource("mensagens/titulos.yml", false);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(Level.SEVERE, "Não foi possível encontrar mensagens/titulos.yml no plugin.", e);
        }
    }

    public void reload() {
        config = YamlConfiguration.loadConfiguration(file);
    }

    public String getTitleBemVindo() {
        return formatar(config.getString("bem-vindo", "&aBem-vindo"));
    }

    public String getTitleRegistro() {
        return formatar(config.getString("registro", "&eFaça o registro"));
    }

    public String getTitleLogin() {
        return formatar(config.getString("login", "&eFaça o login"));
    }

    private String formatar(String texto) {
        return ChatColor.translateAlternateColorCodes('&', texto);
    }
}
