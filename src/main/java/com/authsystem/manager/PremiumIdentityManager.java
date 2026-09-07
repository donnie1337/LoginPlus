package com.authsystem.manager;

import java.util.Locale;
import java.util.UUID;

/** Persiste a identidade premium confirmada para impedir o uso cracked do mesmo nickname. */
public final class PremiumIdentityManager {
    private final PlayerDataManager playerDataManager;

    public PremiumIdentityManager(PlayerDataManager playerDataManager) {
        this.playerDataManager = playerDataManager;
    }

    public boolean isPremium(String username) {
        return playerDataManager.isPremiumIdentity(username);
    }

    public UUID getUuid(String username) {
        return playerDataManager.getPremiumUuid(username);
    }

    public boolean matches(String username, UUID uuid) {
        if (username == null || uuid == null) return false;
        UUID registered = getUuid(username);
        return registered != null && registered.equals(uuid);
    }

    public void mark(String username, UUID uuid, String ip) {
        if (username == null || username.isBlank() || uuid == null) return;
        playerDataManager.markPremiumIdentity(username, uuid, ip);
    }

    public String key(String username) {
        return username == null ? "" : username.toLowerCase(Locale.ROOT);
    }
}
