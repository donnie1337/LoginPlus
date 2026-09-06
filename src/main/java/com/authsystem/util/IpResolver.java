package com.authsystem.util;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;

import java.net.InetSocketAddress;

/** Centraliza a leitura do endereco de rede visto pelo servidor. */
public final class IpResolver {

    private IpResolver() {
    }

    public static String getPlayerIp(Player player) {
        if (player == null) {
            return null;
        }
        InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) {
            return null;
        }
        String ip = address.getAddress().getHostAddress();
        return ip == null || ip.isBlank() ? null : ip;
    }
}
