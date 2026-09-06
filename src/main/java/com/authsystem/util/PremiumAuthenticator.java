package com.authsystem.util;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds short-lived, cryptographically verified premium sessions.
 * A name lookup alone never marks a player as premium.
 */
public final class PremiumAuthenticator {
    private static final long VERIFIED_TTL_MS = 60_000L;
    private final ConcurrentHashMap<String, VerifiedSession> verified = new ConcurrentHashMap<>();

    public void markVerified(String username, UUID mojangUuid) {
        verified.put(username.toLowerCase(Locale.ROOT),
                new VerifiedSession(mojangUuid, System.currentTimeMillis()));
    }

    public UUID consumeVerified(String username) {
        String key = username.toLowerCase(Locale.ROOT);
        VerifiedSession session = verified.remove(key);
        if (session == null) {
            return null;
        }
        if (System.currentTimeMillis() - session.timestamp() > VERIFIED_TTL_MS) {
            return null;
        }
        return session.mojangUuid();
    }

    public void clear(String username) {
        if (username != null) {
            verified.remove(username.toLowerCase(Locale.ROOT));
        }
    }

    private record VerifiedSession(UUID mojangUuid, long timestamp) {}
}
