package com.authsystem.util;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;

/** Realiza o desafio criptografico usado para verificar contas premium. */
public final class PremiumLoginVerifier {
    private static final Logger LOGGER = Logger.getLogger("AuthSystem");
    private static final int RSA_KEY_SIZE = 2048;
    private static final int MAX_MOJANG_RESPONSE_BYTES = 16 * 1024;
    private static final long MOJANG_TIMEOUT_SECONDS = 8L;
    private static final long PENDING_TTL_MS = 20_000L;
    private static final Pattern MOJANG_UUID_PATTERN = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([0-9a-fA-F-]{32,36})\\\"");
    private final KeyPair keyPair;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final Semaphore verificacoesAtivas;

    public PremiumLoginVerifier(int maxConcurrentChecks) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_SIZE, random);
            keyPair = generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA indisponivel", e);
        }
        verificacoesAtivas = maxConcurrentChecks > 0 ? new Semaphore(maxConcurrentChecks) : null;
    }

    public java.security.PublicKey getPublicKey() { return keyPair.getPublic(); }

    /** Inicia um desafio apenas se a conexao ainda nao possuir outro desafio pendente. */
    public byte[] start(String connectionKey, String username) {
        byte[] token = new byte[4];
        random.nextBytes(token);
        Pending novo = new Pending(username, token, System.currentTimeMillis());
        return pending.putIfAbsent(connectionKey, novo) == null ? token : null;
    }

    public boolean hasPending(String connectionKey) { return pending.containsKey(connectionKey); }

    public void remove(String connectionKey) { pending.remove(connectionKey); }

    /** Remove desafios abandonados para impedir crescimento indefinido do mapa em conexoes maliciosas. */
    public void cleanupExpired() {
        long agora = System.currentTimeMillis();
        pending.entrySet().removeIf(entry -> agora - entry.getValue().createdAt() >= PENDING_TTL_MS);
    }

    public byte[] decrypt(byte[] encrypted) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
        return cipher.doFinal(encrypted);
    }

    public boolean validateToken(String connectionKey, byte[] encryptedToken) {
        Pending p = pending.get(connectionKey);
        if (p == null || encryptedToken == null) return false;
        try {
            byte[] token = decrypt(encryptedToken);
            return MessageDigest.isEqual(token, p.verifyToken());
        } catch (GeneralSecurityException e) {
            LOGGER.fine("Token de verificacao premium invalido: " + e.getMessage());
            return false;
        }
    }

    public CompletableFuture<Optional<UUID>> verify(String connectionKey, byte[] sharedSecret) {
        Pending p = pending.remove(connectionKey);
        if (p == null || sharedSecret == null || sharedSecret.length != 16) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        if (verificacoesAtivas != null && !verificacoesAtivas.tryAcquire()) {
            LOGGER.warning("Limite global de verificacoes premium simultaneas atingido para " + p.username() + ".");
            return CompletableFuture.completedFuture(Optional.empty());
        }

        CompletableFuture<Optional<UUID>> request = CompletableFuture.supplyAsync(() -> {
            try {
                String serverHash = serverHash(sharedSecret);
                LOGGER.info("Verificando conta premium " + p.username() + " na Mojang (serverId=" + serverHash + ")");
                return hasJoined(p.username(), serverHash);
            } catch (Exception e) {
                LOGGER.warning("Falha na verificacao premium de " + p.username() + ": " + e.getMessage());
                return Optional.empty();
            }
        });

        if (verificacoesAtivas != null) request.whenComplete((result, error) -> verificacoesAtivas.release());

        CompletableFuture<Optional<UUID>> timeout = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(MOJANG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .execute(() -> timeout.complete(Optional.empty()));

        return request.applyToEither(timeout, value -> value)
                .exceptionally(error -> {
                    LOGGER.warning("Timeout/falha na verificacao Mojang de " + p.username() + ".");
                    return Optional.empty();
                });
    }

    /**
     * Calcula o serverId no formato assinado hexadecimal esperado pelo protocolo
     * do Minecraft. BigInteger(byte[]) preserva a representacao signed two's-complement.
     */
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
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(4000);
        connection.setRequestProperty("User-Agent", "AuthSystem/1.1.3");
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                LOGGER.warning("Mojang hasJoined retornou HTTP " + status + " para " + username + ".");
                return Optional.empty();
            }
            int contentLength = connection.getContentLength();
            if (contentLength > MAX_MOJANG_RESPONSE_BYTES) {
                LOGGER.warning("Resposta da Mojang excedeu o limite permitido para " + username + ".");
                return Optional.empty();
            }
            try (InputStream input = connection.getInputStream()) {
                byte[] bodyBytes = input.readNBytes(MAX_MOJANG_RESPONSE_BYTES + 1);
                if (bodyBytes.length > MAX_MOJANG_RESPONSE_BYTES) {
                    LOGGER.warning("Resposta da Mojang excedeu o limite permitido para " + username + ".");
                    return Optional.empty();
                }
                String body = new String(bodyBytes, StandardCharsets.UTF_8);
                Matcher matcher = MOJANG_UUID_PATTERN.matcher(body);
                if (!matcher.find()) {
                    LOGGER.warning("Mojang respondeu sem UUID valido para " + username + ".");
                    return Optional.empty();
                }
                String raw = matcher.group(1).replace("-", "");
                if (!raw.matches("[0-9a-fA-F]{32}")) {
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

    private record Pending(String username, byte[] verifyToken, long createdAt) {}
}
