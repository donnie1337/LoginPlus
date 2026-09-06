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

        /*
         * IMPORTANT: the server is running with online-mode=false.
         * In this mode AsyncPlayerPreLoginEvent#getUniqueId() is the server's
         * offline UUID, while Mojang's hasJoined response contains the real
         * premium UUID. Comparing the two UUIDs would therefore reject every
         * correctly authenticated premium player.
         *
         * The premium identity is already cryptographically verified by the
         * Encryption Response + verify token + Mojang hasJoined challenge.
         * The username and IP are also bound to this short-lived verification
         * entry, so the offline UUID must not be used as a second check here.
         */
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
