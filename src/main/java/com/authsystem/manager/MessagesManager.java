package com.authsystem.manager;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/** Carrega os textos dos Titles e das mensagens de chat. */
public final class MessagesManager {
    private final YamlConfiguration titles;
    private final YamlConfiguration chat;

    public MessagesManager(JavaPlugin plugin) {
        File titlesFile = new File(plugin.getDataFolder(), "mensagens/titulos.yml");
        if (!titlesFile.exists()) {
            plugin.saveResource("mensagens/titulos.yml", false);
        }
        this.titles = YamlConfiguration.loadConfiguration(titlesFile);

        File chatFile = new File(plugin.getDataFolder(), "mensagens/chat.yml");
        if (!chatFile.exists()) {
            plugin.saveResource("mensagens/chat.yml", false);
        }
        this.chat = YamlConfiguration.loadConfiguration(chatFile);
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

    public String getChat(String path, String fallback) {
        String value = chat.getString(path, fallback);
        return color(value == null ? fallback : value);
    }

    public String getChat(String path, String fallback, String... replacements) {
        String value = getChat(path, fallback);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            value = value.replace(replacements[i], replacements[i + 1]);
        }
        return value;
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }
}
