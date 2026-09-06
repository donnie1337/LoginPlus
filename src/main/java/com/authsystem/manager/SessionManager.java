package com.authsystem.manager;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Guarda as sessoes de autenticacao e o limite de contas autenticadas por IP. */
public class SessionManager {
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Set<UUID> premium = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> timeoutTasks = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> contasAutenticadasPorIp = new ConcurrentHashMap<>();

    public boolean isAuthenticated(Player player) { return authenticated.contains(player.getUniqueId()); }
    public void setAuthenticated(Player player, boolean value) { if (value) authenticated.add(player.getUniqueId()); else authenticated.remove(player.getUniqueId()); }
    public boolean isPremium(Player player) { return premium.contains(player.getUniqueId()); }
    public void markPremium(UUID uuid) { premium.add(uuid); }
    public void setTimeoutTask(Player player, BukkitTask task) { cancelTimeout(player); timeoutTasks.put(player.getUniqueId(), task); }
    public void cancelTimeout(Player player) { BukkitTask task = timeoutTasks.remove(player.getUniqueId()); if (task != null) task.cancel(); }

    /** Reserva atomicamente uma vaga de autenticacao para o IP. */
    public synchronized boolean tryRegisterAuthenticatedIp(String ip, UUID uuid, int limite) {
        if (ip == null || ip.isBlank() || limite <= 0) return true;
        Set<UUID> contas = contasAutenticadasPorIp.computeIfAbsent(ip, chave -> ConcurrentHashMap.newKeySet());
        if (contas.contains(uuid)) return true;
        if (contas.size() >= limite) return false;
        contas.add(uuid);
        return true;
    }

    public synchronized void unregisterAuthenticatedIp(String ip, UUID uuid) {
        if (ip == null || ip.isBlank()) return;
        Set<UUID> contas = contasAutenticadasPorIp.get(ip);
        if (contas == null) return;
        contas.remove(uuid);
        if (contas.isEmpty()) contasAutenticadasPorIp.remove(ip, contas);
    }

    public int countAuthenticatedFromIp(String ip) {
        Set<UUID> contas = contasAutenticadasPorIp.get(ip);
        return contas == null ? 0 : contas.size();
    }

    public void clear(Player player) {
        UUID uuid = player.getUniqueId();
        authenticated.remove(uuid);
        premium.remove(uuid);
        cancelTimeout(player);
        String ip = player.getAddress() == null || player.getAddress().getAddress() == null ? null : player.getAddress().getAddress().getHostAddress();
        unregisterAuthenticatedIp(ip, uuid);
    }
}
