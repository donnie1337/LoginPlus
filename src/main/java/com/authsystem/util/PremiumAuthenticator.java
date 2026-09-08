package com.authsystem.util;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Guarda por pouco tempo as sessoes premium verificadas criptograficamente. */
public final class PremiumAuthenticator {
    private static final long VERIFIED_TTL_MS = 60_000L;
    private final ConcurrentHashMap<String, VerifiedSession> verified = new ConcurrentHashMap<>();

    public void markVerified(String username, String ip, UUID mojangUuid) {
        if (username == null || ip == null || mojangUuid == null) {
            return;
        }
        verified.put(key(username, ip), new VerifiedSession(mojangUuid, System.currentTimeMillis()));
    }

    /** Consome a prova premium quando o jogador realmente entra no servidor. */
    public UUID consumeVerified(String username, String ip) {
        if (username == null || ip == null) {
            return null;
        }
        String key = key(username, ip);
        VerifiedSession session = verified.remove(key);
        if (session == null || System.currentTimeMillis() - session.timestamp() > VERIFIED_TTL_MS) {
            return null;
        }
        return session.mojangUuid();
    }

    public void clear(String username, String ip) {
        if (username != null && ip != null) {
            verified.remove(key(username, ip));
        }
    }

    /** Remove provas premium que ultrapassaram o TTL. */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        verified.entrySet().removeIf(entry -> now - entry.getValue().timestamp() > VERIFIED_TTL_MS);
    }

    private static String key(String username, String ip) {
        return username.toLowerCase(Locale.ROOT) + "|" + ip;
    }

    private record VerifiedSession(UUID mojangUuid, long timestamp) {}
}
