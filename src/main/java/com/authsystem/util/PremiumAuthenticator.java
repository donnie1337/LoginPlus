package com.authsystem.util;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Guarda por pouco tempo as sessoes premium verificadas criptograficamente. */
public final class PremiumAuthenticator {
    private static final long VERIFIED_TTL_MS = 60_000L;
    private final ConcurrentHashMap<String, VerifiedSession> verified = new ConcurrentHashMap<>();

    /**
     * Registra a prova premium vinculando-a ao nickname, IP e UUID da conexao.
     * O UUID confirmado pela Mojang fica armazenado separadamente, pois em offline-mode
     * o UUID exposto pelo servidor pode ser diferente do UUID oficial da conta.
     */
    public void markVerified(String username, String ip, UUID connectionUuid, UUID mojangUuid) {
        if (username == null || ip == null || connectionUuid == null || mojangUuid == null) {
            return;
        }
        verified.put(key(username, ip, connectionUuid), new VerifiedSession(mojangUuid, System.currentTimeMillis()));
    }

    /** Consome a prova premium somente para a mesma identidade da conexao que concluiu o desafio. */
    public UUID consumeVerified(String username, String ip, UUID connectionUuid) {
        if (username == null || ip == null || connectionUuid == null) {
            return null;
        }
        String key = key(username, ip, connectionUuid);
        VerifiedSession session = verified.remove(key);
        if (session == null || System.currentTimeMillis() - session.timestamp() > VERIFIED_TTL_MS) {
            return null;
        }
        return session.mojangUuid();
    }

    public void clear(String username, String ip, UUID connectionUuid) {
        if (username != null && ip != null && connectionUuid != null) {
            verified.remove(key(username, ip, connectionUuid));
        }
    }

    /** Remove provas premium que ultrapassaram o TTL. */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        verified.entrySet().removeIf(entry -> now - entry.getValue().timestamp() > VERIFIED_TTL_MS);
    }

    private static String key(String username, String ip, UUID connectionUuid) {
        return username.toLowerCase(Locale.ROOT) + "|" + ip + "|" + connectionUuid;
    }

    private record VerifiedSession(UUID mojangUuid, long timestamp) {}
}
