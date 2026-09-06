package com.authsystem.util;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import javax.crypto.Cipher;

/** Performs the actual Mojang cryptographic session verification. */
public final class PremiumLoginVerifier {
    private static final Logger LOGGER = Logger.getLogger("AuthSystem");
    private final KeyPair keyPair;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    public PremiumLoginVerifier() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(1024, random);
            keyPair = generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA is unavailable", e);
        }
    }

    public java.security.PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    public byte[] start(String connectionKey, String username) {
        byte[] token = new byte[4];
        random.nextBytes(token);
        pending.put(connectionKey, new Pending(username, token));
        return token;
    }

    public boolean hasPending(String connectionKey) {
        return pending.containsKey(connectionKey);
    }

    public String getPendingUsername(String connectionKey) {
        Pending p = pending.get(connectionKey);
        return p == null ? null : p.username();
    }

    public void remove(String connectionKey) {
        pending.remove(connectionKey);
    }

    public byte[] decrypt(byte[] encrypted) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
        return cipher.doFinal(encrypted);
    }

    /** Validates the client response token without consuming the pending session. */
    public boolean validateToken(String connectionKey, byte[] encryptedToken) {
        Pending p = pending.get(connectionKey);
        if (p == null || encryptedToken == null) {
            return false;
        }
        try {
            byte[] token = decrypt(encryptedToken);
            return Arrays.equals(token, p.verifyToken());
        } catch (GeneralSecurityException e) {
            LOGGER.fine("Invalid premium verify token: " + e.getMessage());
            return false;
        }
    }

    public CompletableFuture<Optional<UUID>> verify(String connectionKey, byte[] sharedSecret) {
        Pending p = pending.remove(connectionKey);
        if (p == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (sharedSecret == null || sharedSecret.length != 16) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                String serverHash = serverHash(sharedSecret);
                return hasJoined(p.username(), serverHash);
            } catch (Exception e) {
                LOGGER.warning("Premium verification failed for " + p.username() + ": " + e.getMessage());
                return Optional.empty();
            }
        });
    }

    private String serverHash(byte[] sharedSecret) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(sharedSecret);
        digest.update(keyPair.getPublic().getEncoded());
        return new java.math.BigInteger(digest.digest()).toString(16);
    }

    private Optional<UUID> hasJoined(String username, String serverHash) throws Exception {
        String encodedName = URLEncoder.encode(username, StandardCharsets.UTF_8);
        String encodedHash = URLEncoder.encode(serverHash, StandardCharsets.UTF_8);
        URI uri = URI.create("https://sessionserver.mojang.com/session/minecraft/hasJoined?username="
                + encodedName + "&serverId=" + encodedHash);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try {
            if (connection.getResponseCode() != 200) {
                return Optional.empty();
            }
            try (InputStream input = connection.getInputStream()) {
                String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                String marker = "\"id\":\"";
                int start = body.indexOf(marker);
                if (start < 0) {
                    return Optional.empty();
                }
                start += marker.length();
                int end = body.indexOf('"', start);
                if (end < 0) {
                    return Optional.empty();
                }
                String raw = body.substring(start, end).replace("-", "").toLowerCase(Locale.ROOT);
                if (!raw.matches("[0-9a-f]{32}")) {
                    return Optional.empty();
                }
                String uuid = raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-"
                        + raw.substring(12, 16) + "-" + raw.substring(16, 20) + "-" + raw.substring(20);
                return Optional.of(UUID.fromString(uuid));
            }
        } finally {
            connection.disconnect();
        }
    }

    private record Pending(String username, byte[] verifyToken) {}
}
