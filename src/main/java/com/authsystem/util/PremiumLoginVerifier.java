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
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;

/** Verifica a prova criptografica premium e distingue conta cracked de falha de rede. */
public final class PremiumLoginVerifier {
    private static final Logger LOGGER = Logger.getLogger("LoginPlus");
    private static final int RSA_KEY_SIZE = 2048;
    private static final int MAX_REQUEST_WORKERS = 8;
    private static final int MAX_MOJANG_RESPONSE_BYTES = 16 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 3_000;
    private static final long MOJANG_TIMEOUT_SECONDS = 12L;
    private static final long PENDING_TTL_MS = 20_000L;
    private static final Pattern MOJANG_UUID_PATTERN = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([0-9a-fA-F-]{32,36})\\\"");
    private final KeyPair keyPair;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ExecutorService requestExecutor;
    private final java.util.concurrent.Semaphore verificationsActive;

    public enum Status {
        VERIFIED,
        NOT_PREMIUM,
        PREMIUM_REQUIRES_AUTHENTICATION,
        UNAVAILABLE
    }

    public record Result(Status status, UUID uuid, String detail) {
        private static Result verified(UUID uuid) { return new Result(Status.VERIFIED, uuid, "verified"); }
        private static Result of(Status status, String detail) { return new Result(status, null, detail); }
    }

    public PremiumLoginVerifier(int maxConcurrentChecks) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_SIZE, random);
            keyPair = generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA indisponivel", e);
        }

        int workers = Math.max(1, Math.min(maxConcurrentChecks, MAX_REQUEST_WORKERS));
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "LoginPlus-Mojang-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        // Isola chamadas bloqueantes/DNS do ForkJoinPool compartilhado por outros plugins.
        requestExecutor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workers), factory, new ThreadPoolExecutor.AbortPolicy());
        verificationsActive = new java.util.concurrent.Semaphore(workers);
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

    /** Remove desafios abandonados para impedir crescimento indefinido do mapa. */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(entry -> now - entry.getValue().createdAt() >= PENDING_TTL_MS);
    }

    public byte[] decrypt(byte[] encrypted) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
        return cipher.doFinal(encrypted);
    }

    public boolean validateToken(String connectionKey, byte[] encryptedToken) {
        Pending value = pending.get(connectionKey);
        if (value == null || encryptedToken == null) return false;
        try {
            return MessageDigest.isEqual(decrypt(encryptedToken), value.verifyToken());
        } catch (GeneralSecurityException e) {
            LOGGER.fine("Token premium invalido: " + e.getMessage());
            return false;
        }
    }

    /**
     * Resultado positivo exige hasJoined (prova de posse da sessao).
     * Quando hasJoined responde 204, consulta o perfil: um nickname premium
     * nao pode cair no fluxo cracked apenas por nao ter apresentado uma sessao.
     * Falhas de rede nunca sao classificadas como conta nao premium.
     */
    public CompletableFuture<Result> verify(String connectionKey, byte[] sharedSecret) {
        Pending value = pending.remove(connectionKey);
        if (value == null || sharedSecret == null || sharedSecret.length != 16) {
            return CompletableFuture.completedFuture(Result.of(Status.UNAVAILABLE, "handshake_incompleto"));
        }
        if (!verificationsActive.tryAcquire()) {
            return CompletableFuture.completedFuture(Result.of(Status.UNAVAILABLE, "limite_de_consultas"));
        }

        CompletableFuture<Result> request;
        try {
            request = CompletableFuture.supplyAsync(() -> verifyNow(value.username(), sharedSecret), requestExecutor);
        } catch (RuntimeException rejected) {
            verificationsActive.release();
            return CompletableFuture.completedFuture(Result.of(Status.UNAVAILABLE, "fila_de_consultas_cheia"));
        }
        request.whenComplete((result, error) -> verificationsActive.release());

        CompletableFuture<Result> timeout = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(MOJANG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .execute(() -> timeout.complete(Result.of(Status.UNAVAILABLE, "timeout")));
        return request.applyToEither(timeout, result -> result)
                .exceptionally(error -> Result.of(Status.UNAVAILABLE, "erro_de_rede"));
    }

    private Result verifyNow(String username, byte[] sharedSecret) {
        try {
            String serverHash = serverHash(sharedSecret);
            HttpResult session = get("https://sessionserver.mojang.com/session/minecraft/hasJoined?username="
                    + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&serverId="
                    + URLEncoder.encode(serverHash, StandardCharsets.UTF_8));

            if (session.status() == HttpURLConnection.HTTP_OK) {
                UUID uuid = parseUuid(session.body());
                if (uuid == null) return Result.of(Status.UNAVAILABLE, "resposta_hasJoined_invalida");
                LOGGER.info("Sessao premium verificada para " + username + ".");
                return Result.verified(uuid);
            }

            // 204 e a resposta normal para uma sessao sem prova premium.
            // Outros status (429/5xx/4xx) sao indisponibilidade, nao prova de conta cracked.
            if (session.status() != HttpURLConnection.HTTP_NO_CONTENT) {
                return Result.of(Status.UNAVAILABLE, "hasJoined_http_" + session.status());
            }

            HttpResult profile = get("https://api.mojang.com/users/profiles/minecraft/"
                    + URLEncoder.encode(username, StandardCharsets.UTF_8));
            if (profile.status() == HttpURLConnection.HTTP_OK) {
                UUID uuid = parseUuid(profile.body());
                if (uuid == null) return Result.of(Status.UNAVAILABLE, "resposta_perfil_invalida");
                return Result.of(Status.PREMIUM_REQUIRES_AUTHENTICATION, "conta_premium_sem_sessao_valida");
            }
            if (profile.status() == HttpURLConnection.HTTP_NO_CONTENT) {
                return Result.of(Status.NOT_PREMIUM, "perfil_premium_nao_encontrado");
            }
            return Result.of(Status.UNAVAILABLE, "perfil_http_" + profile.status());
        } catch (Exception e) {
            LOGGER.warning("Consulta premium da Mojang indisponivel para " + username + " ("
                    + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()) + ").");
            return Result.of(Status.UNAVAILABLE, "falha_de_rede_" + e.getClass().getSimpleName());
        }
    }

    /** Calcula o serverId signed hexadecimal exigido pelo protocolo Minecraft. */
    private String serverHash(byte[] sharedSecret) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(sharedSecret);
        digest.update(keyPair.getPublic().getEncoded());
        return new java.math.BigInteger(digest.digest()).toString(16);
    }

    private HttpResult get(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "LoginPlus/1.1.4");
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) return new HttpResult(status, new byte[0]);
            int contentLength = connection.getContentLength();
            if (contentLength > MAX_MOJANG_RESPONSE_BYTES) return new HttpResult(status, new byte[0]);
            try (InputStream input = connection.getInputStream()) {
                byte[] body = input.readNBytes(MAX_MOJANG_RESPONSE_BYTES + 1);
                if (body.length > MAX_MOJANG_RESPONSE_BYTES) return new HttpResult(status, new byte[0]);
                return new HttpResult(status, body);
            }
        } finally {
            connection.disconnect();
        }
    }

    private UUID parseUuid(byte[] body) {
        Matcher matcher = MOJANG_UUID_PATTERN.matcher(new String(body, StandardCharsets.UTF_8));
        if (!matcher.find()) return null;
        String raw = matcher.group(1).replace("-", "");
        if (!raw.matches("[0-9a-fA-F]{32}")) return null;
        String normalized = raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-"
                + raw.substring(12, 16) + "-" + raw.substring(16, 20) + "-" + raw.substring(20);
        try { return UUID.fromString(normalized); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    public void shutdown() {
        requestExecutor.shutdownNow();
        pending.clear();
    }

    private record HttpResult(int status, byte[] body) {}
    private record Pending(String username, byte[] verifyToken, long createdAt) {}
}
