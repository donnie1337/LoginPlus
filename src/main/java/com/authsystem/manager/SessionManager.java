package com.authsystem.manager;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guarda em memoria quem esta logado, quem foi identificado como conta original,
 * localizacao de congelamento e tarefas de timeout enquanto o jogador esta online.
 */
public class SessionManager {

    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Set<UUID> premium = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Location> frozenLocation = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> timeoutTasks = new ConcurrentHashMap<>();

    public boolean isAuthenticated(Player player) {
        return authenticated.contains(player.getUniqueId());
    }

    public void setAuthenticated(Player player, boolean value) {
        if (value) {
            authenticated.add(player.getUniqueId());
        } else {
            authenticated.remove(player.getUniqueId());
        }
    }

    public boolean isPremium(Player player) {
        return premium.contains(player.getUniqueId());
    }

    public void markPremium(UUID uuid) {
        premium.add(uuid);
    }

    public void setFrozenLocation(Player player, Location loc) {
        frozenLocation.put(player.getUniqueId(), loc);
    }

    public Location getFrozenLocation(Player player) {
        return frozenLocation.get(player.getUniqueId());
    }

    public void setTimeoutTask(Player player, BukkitTask task) {
        cancelTimeout(player);
        timeoutTasks.put(player.getUniqueId(), task);
    }

    public void cancelTimeout(Player player) {
        BukkitTask task = timeoutTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    public void clear(Player player) {
        UUID uuid = player.getUniqueId();
        authenticated.remove(uuid);
        premium.remove(uuid);
        frozenLocation.remove(uuid);
        cancelTimeout(player);
    }
}
