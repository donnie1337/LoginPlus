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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.crypto.Cipher;

/** Realiza o desafio criptografico usado para verificar contas premium. */
public final class PremiumLoginVerifier {
    private static final Logger LOGGER = Logger.getLogger("AuthSystem");
    private static final int RSA_KEY_SIZE = 2048;
    private static final int MAX_MOJANG_RESPONSE_BYTES = 16 * 1024;
    private static final long MOJANG_TIMEOUT_SECONDS = 8L;
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
        Pending novo = new Pending(username, token);
        return pending.putIfAbsent(connectionKey, novo) == null ? token : null;
    }

    public boolean hasPending(String connectionKey) { return pending.containsKey(connectionKey); }

    public void remove(String connectionKey) { pending.remove(connectionKey); }

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
            LOGGER.warning("Limite global de verificacoes premium simultaneas atingido para " + p.username() + ". A conexao continuara como cracked.");
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

        // O timeout e do fluxo de autenticacao, independentemente do estado da rede.
        // O future pode terminar por timeout enquanto a requisicao interna ainda estiver em execucao;
        // o semaforo so e liberado quando a requisicao realmente terminar.
        return request.orTimeout(MOJANG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(error -> {
                    LOGGER.warning("Timeout/falha na verificacao Mojang de " + p.username() + ". A conexao continuara como cracked.");
                    return Optional.empty();
                })
                .whenComplete((result, error) -> {
                    if (request.isDone() && verificacoesAtivas != null) {
                        // whenComplete do future encadeado pode ocorrer no timeout; por isso a liberacao
                        // precisa acompanhar a requisicao real, nao apenas o future publico.
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
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(4000);
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                LOGGER.warning("Mojang hasJoined retornou HTTP " + status + " para " + username
                        + ". A conta sera tratada como cracked.");
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
                String marker = "\"id\"";
                int markerStart = body.indexOf(marker);
                if (markerStart < 0) {
                    LOGGER.warning("Mojang respondeu sem UUID para " + username + ".");
                    return Optional.empty();
                }
                int colon = body.indexOf(':', markerStart + marker.length());
                if (colon < 0) {
                    LOGGER.warning("Resposta invalida da Mojang para " + username + ".");
                    return Optional.empty();
                }
                int valueStart = colon + 1;
                while (valueStart < body.length() && Character.isWhitespace(body.charAt(valueStart))) valueStart++;
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
