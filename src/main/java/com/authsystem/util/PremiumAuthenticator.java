package com.authsystem.util;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Guarda por pouco tempo as sessoes premium verificadas criptograficamente. */
public final class PremiumAuthenticator {
    private static final long VERIFIED_TTL_MS = 60_000L;
    private final ConcurrentHashMap<String, VerifiedSession> verified = new ConcurrentHashMap<>();

    /**
     * Registra a prova premium vinculando-a ao nickname, IP e UUID apresentados na conexao.
     * O UUID e parte da chave para impedir que uma prova de uma sessao seja reutilizada por outra identidade.
     */
    public void markVerified(String username, String ip, UUID playerUuid, UUID mojangUuid) {
        if (username == null || ip == null || playerUuid == null || mojangUuid == null) {
            return;
        }
        if (!playerUuid.equals(mojangUuid)) {
            return;
        }
        verified.put(key(username, ip, playerUuid), new VerifiedSession(mojangUuid, System.currentTimeMillis()));
    }

    /** Consome a prova premium somente para a mesma identidade que concluiu o desafio criptografico. */
    public UUID consumeVerified(String username, String ip, UUID playerUuid) {
        if (username == null || ip == null || playerUuid == null) {
            return null;
        }
        String key = key(username, ip, playerUuid);
        VerifiedSession session = verified.remove(key);
        if (session == null || System.currentTimeMillis() - session.timestamp() > VERIFIED_TTL_MS) {
            return null;
        }
        if (!playerUuid.equals(session.mojangUuid())) {
            return null;
        }
        return session.mojangUuid();
    }

    public void clear(String username, String ip, UUID playerUuid) {
        if (username != null && ip != null && playerUuid != null) {
            verified.remove(key(username, ip, playerUuid));
        }
    }

    /** Remove provas premium que ultrapassaram o TTL. */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        verified.entrySet().removeIf(entry -> now - entry.getValue().timestamp() > VERIFIED_TTL_MS);
    }

    private static String key(String username, String ip, UUID playerUuid) {
        return username.toLowerCase(Locale.ROOT) + "|" + ip + "|" + playerUuid;
    }

    private record VerifiedSession(UUID mojangUuid, long timestamp) {}
}
