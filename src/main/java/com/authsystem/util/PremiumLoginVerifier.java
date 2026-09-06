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
                LOGGER.info("Verificando conta premium " + p.username() + " na Mojang (serverId=" + serverHash + ")");
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
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                LOGGER.warning("Mojang hasJoined retornou HTTP " + status + " para " + username
                        + ". A conta sera tratada como cracked.");
                return Optional.empty();
            }
            try (InputStream input = connection.getInputStream()) {
                String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);

                // Mojang's JSON response may contain spaces around ':' (for example
                // \"id\" : \"...\"). Do not depend on one exact JSON formatting.
                String marker = "\"id\"";
                int markerStart = body.indexOf(marker);
                if (markerStart < 0) {
                    LOGGER.warning("Mojang respondeu sem UUID para " + username + ". Resposta recebida: " + body);
                    return Optional.empty();
                }
                int colon = body.indexOf(':', markerStart + marker.length());
                if (colon < 0) {
                    LOGGER.warning("Resposta invalida da Mojang para " + username + ".");
                    return Optional.empty();
                }
                int valueStart = colon + 1;
                while (valueStart < body.length() && Character.isWhitespace(body.charAt(valueStart))) {
                    valueStart++;
                }
                if (valueStart >= body.length() || body.charAt(valueStart) != '"') {
                    LOGGER.warning("Resposta invalida da Mojang para " + username + ".");
                    return Optional.empty();
                }
                valueStart++;
                int end = body.indexOf('"', valueStart);
                if (end < 0) {
                    LOGGER.warning("Resposta invalida da Mojang para " + username + ".");
                    return Optional.empty();
                }
                String raw = body.substring(valueStart, end).replace("-", "").toLowerCase(Locale.ROOT);
                if (!raw.matches("[0-9a-f]{32}")) {
                    LOGGER.warning("UUID invalido retornado pela Mojang para " + username + ".");
                    return Optional.empty();
                }
                String uuid = raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-"
                        + raw.substring(12, 16) + "-" + raw.substring(16, 20) + "-" + raw.substring(20);
                LOGGER.info("Mojang confirmou a identidade premium de " + username + ".");
                return Optional.of(UUID.fromString(uuid));
            }
        } finally {
            connection.disconnect();
        }
    }

    private record Pending(String username, byte[] verifyToken) {}
}
