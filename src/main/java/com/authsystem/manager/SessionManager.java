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
    private final Map<UUID, String> ipAutenticadoPorConta = new ConcurrentHashMap<>();

    public boolean isAuthenticated(Player player) { return authenticated.contains(player.getUniqueId()); }
    public void setAuthenticated(Player player, boolean value) {
        if (value) authenticated.add(player.getUniqueId());
        else authenticated.remove(player.getUniqueId());
    }
    public boolean isPremium(Player player) { return premium.contains(player.getUniqueId()); }
    public void markPremium(UUID uuid) { premium.add(uuid); }
    public void setTimeoutTask(Player player, BukkitTask task) { cancelTimeout(player); timeoutTasks.put(player.getUniqueId(), task); }
    public void cancelTimeout(Player player) { BukkitTask task = timeoutTasks.remove(player.getUniqueId()); if (task != null) task.cancel(); }

    /** Reserva atomicamente uma vaga de autenticacao para o IP sem destruir a reserva atual em caso de recusa. */
    public synchronized boolean tryRegisterAuthenticatedIp(String ip, UUID uuid, int limite) {
        if (ip == null || ip.isBlank() || uuid == null || limite <= 0) return true;
        String ipAtual = ipAutenticadoPorConta.get(uuid);
        if (ip.equals(ipAtual)) return true;

        Set<UUID> novoConjunto = contasAutenticadasPorIp.computeIfAbsent(ip, chave -> ConcurrentHashMap.newKeySet());
        if (!novoConjunto.contains(uuid) && novoConjunto.size() >= limite) return false;

        if (ipAtual != null) unregisterAuthenticatedIp(ipAtual, uuid);
        novoConjunto.add(uuid);
        ipAutenticadoPorConta.put(uuid, ip);
        return true;
    }

    public synchronized void unregisterAuthenticatedIp(String ip, UUID uuid) {
        if (uuid == null) return;
        if (ip == null || ip.isBlank()) ip = ipAutenticadoPorConta.get(uuid);
        if (ip == null || ip.isBlank()) return;
        Set<UUID> contas = contasAutenticadasPorIp.get(ip);
        if (contas != null) {
            contas.remove(uuid);
            if (contas.isEmpty()) contasAutenticadasPorIp.remove(ip, contas);
        }
        ipAutenticadoPorConta.remove(uuid, ip);
    }

    public int countAuthenticatedFromIp(String ip) {
        Set<UUID> contas = contasAutenticadasPorIp.get(ip);
        return contas == null ? 0 : contas.size();
    }

    /** Retorna true quando ja existe outra conta autenticada usando o mesmo IP. */
    public boolean hasOtherAuthenticatedFromIp(String ip, UUID uuid) {
        if (ip == null || ip.isBlank()) return false;
        Set<UUID> contas = contasAutenticadasPorIp.get(ip);
        if (contas == null || contas.isEmpty()) return false;
        if (uuid == null) return !contas.isEmpty();
        for (UUID conta : contas) {
            if (!conta.equals(uuid)) return true;
        }
        return false;
    }

    public void clear(Player player) {
        UUID uuid = player.getUniqueId();
        authenticated.remove(uuid);
        premium.remove(uuid);
        cancelTimeout(player);
        unregisterAuthenticatedIp(ipAutenticadoPorConta.get(uuid), uuid);
    }
}
