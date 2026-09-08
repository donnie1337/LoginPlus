package com.authsystem.util;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Guarda por pouco tempo as sessoes premium verificadas criptograficamente. */
public final class PremiumAuthenticator {
    private static final long VERIFIED_TTL_MS = 60_000L;
    private final ConcurrentHashMap<String, VerifiedSession> verified = new ConcurrentHashMap<>();

    /**
     * Registra a prova premium vinculando-a ao nickname, IP e UUID apresentado no handshake.
     * Em offline-mode, o UUID recebido no Login Start pode ser o UUID oficial do cliente,
     * enquanto o Bukkit usa o UUID OfflinePlayer. Por isso tambem registramos a mesma prova
     * sob o UUID deterministico que o Bukkit atribui ao jogador.
     */
    public void markVerified(String username, String ip, UUID connectionUuid, UUID mojangUuid) {
        if (username == null || ip == null || connectionUuid == null || mojangUuid == null) {
            return;
        }

        long now = System.currentTimeMillis();
        verified.put(key(username, ip, connectionUuid), new VerifiedSession(mojangUuid, now));

        UUID offlineUuid = offlinePlayerUuid(username);
        if (offlineUuid != null && !offlineUuid.equals(connectionUuid)) {
            verified.put(key(username, ip, offlineUuid), new VerifiedSession(mojangUuid, now));
        }
    }

    /** Consome a prova premium somente para a identidade da conexao usada pelo Bukkit. */
    public UUID consumeVerified(String username, String ip, UUID connectionUuid) {
        if (username == null || ip == null || connectionUuid == null) {
            return null;
        }
        String key = key(username, ip, connectionUuid);
        VerifiedSession session = verified.remove(key);
        if (session == null || System.currentTimeMillis() - session.timestamp() > VERIFIED_TTL_MS) {
            return null;
        }
        removeOtherKeys(username, ip, connectionUuid);
        return session.mojangUuid();
    }

    public void clear(String username, String ip, UUID connectionUuid) {
        if (username == null || ip == null) return;
        if (connectionUuid != null) verified.remove(key(username, ip, connectionUuid));
        UUID offlineUuid = offlinePlayerUuid(username);
        if (offlineUuid != null) verified.remove(key(username, ip, offlineUuid));
    }

    /** Remove provas premium que ultrapassaram o TTL. */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        verified.entrySet().removeIf(entry -> now - entry.getValue().timestamp() > VERIFIED_TTL_MS);
    }

    private void removeOtherKeys(String username, String ip, UUID consumedUuid) {
        UUID offlineUuid = offlinePlayerUuid(username);
        if (offlineUuid != null && !offlineUuid.equals(consumedUuid)) {
            verified.remove(key(username, ip, offlineUuid));
        }
    }

    /** Replica a regra de UUID usada pelo servidor em offline-mode: OfflinePlayer:<nome>. */
    private static UUID offlinePlayerUuid(String username) {
        if (username == null || username.isBlank()) return null;
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }

    private static String key(String username, String ip, UUID connectionUuid) {
        return username.toLowerCase(Locale.ROOT) + "|" + ip + "|" + connectionUuid;
    }

    private record VerifiedSession(UUID mojangUuid, long timestamp) {}
}
