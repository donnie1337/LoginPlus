package com.authsystem.manager;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/** Carrega os textos dos Titles de autenticacao sem depender de configuracao externa obrigatoria. */
public final class MessagesManager {
    private final YamlConfiguration titles;

    public MessagesManager(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "mensagens/titulos.yml");
        if (!file.exists()) {
            plugin.saveResource("mensagens/titulos.yml", false);
        }
        this.titles = YamlConfiguration.loadConfiguration(file);
    }

    public String getTitleBemVindo() {
        return color(titles.getString("bem-vindo", "&a&lBEM-VINDO!"));
    }

    public String getTitleRegistro() {
        return color(titles.getString("registro", "&eUse: /registro <senha> <senha>"));
    }

    public String getTitleLogin() {
        return color(titles.getString("login", "&eUse: /login <senha>"));
    }

    public String getTitlePremium() {
        return color(titles.getString("premium", "&eConta original detectada."));
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }
}
