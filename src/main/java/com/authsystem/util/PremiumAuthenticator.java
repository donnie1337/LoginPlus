package com.authsystem.util;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Holds short-lived, cryptographically verified premium sessions. */
public final class PremiumAuthenticator {
    private static final long VERIFIED_TTL_MS = 60_000L;
    private final ConcurrentHashMap<String, VerifiedSession> verified = new ConcurrentHashMap<>();

    public void markVerified(String username, String ip, UUID mojangUuid) {
        verified.put(key(username, ip), new VerifiedSession(mojangUuid, System.currentTimeMillis()));
    }

    public UUID consumeVerified(String username, String ip, UUID clientUuid) {
        VerifiedSession session = verified.remove(key(username, ip));
        if (session == null || System.currentTimeMillis() - session.timestamp() > VERIFIED_TTL_MS) {
            return null;
        }
        if (clientUuid != null && !session.mojangUuid().equals(clientUuid)) {
            return null;
        }
        return session.mojangUuid();
    }

    public void clear(String username, String ip) {
        if (username != null && ip != null) verified.remove(key(username, ip));
    }

    private static String key(String username, String ip) {
        return username.toLowerCase(Locale.ROOT) + "|" + ip;
    }

    private record VerifiedSession(UUID mojangUuid, long timestamp) {}
}
