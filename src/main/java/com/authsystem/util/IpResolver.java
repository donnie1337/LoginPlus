package com.authsystem.util;

import com.github.retrooper.packetevents.protocol.player.User;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;

/** Centraliza a leitura do endereco de rede visto pelo servidor. */
public final class IpResolver {
    private IpResolver() {}

    public static String getPlayerIp(Player player) {
        if (player == null) return null;
        InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) return null;
        String ip = address.getAddress().getHostAddress();
        return ip == null || ip.isBlank() ? null : ip;
    }

    /** Retorna o IP visto pelo PacketEvents sem lançar NPE quando o endereco ainda nao existe. */
    public static String getUserIp(User user) {
        if (user == null) return null;
        InetSocketAddress address = user.getAddress();
        if (address == null || address.getAddress() == null) return null;
        String ip = address.getAddress().getHostAddress();
        return ip == null || ip.isBlank() ? null : ip;
    }
}
