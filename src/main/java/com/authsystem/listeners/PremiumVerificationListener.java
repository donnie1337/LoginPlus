package com.authsystem.listeners;

import com.authsystem.AuthSystem;
import com.authsystem.util.IpResolver;
import com.authsystem.util.PremiumAuthenticator;
import com.authsystem.util.PremiumLoginVerifier;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientEncryptionResponse;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerDisconnect;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import net.kyori.adventure.text.Component;
import org.bukkit.scheduler.BukkitTask;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** Desafio criptografico usado para verificar contas premium em servidor offline-mode. */
public final class PremiumVerificationListener extends PacketListenerAbstract {
    private static final Logger LOGGER = Logger.getLogger("LoginPlus");
    private static final long FALLBACK_MS = 15000L;
    private static final long CONNECTION_TTL_MS = 20000L;
    private static final String PREMIUM_ACCOUNT_MESSAGE = "Esta conta ja esta cadastrada no servidor como conta original. Entre usando o Minecraft original com este nickname.";
    private final PremiumLoginVerifier verifier;
    private final PremiumAuthenticator authenticator;
    private final AuthSystem plugin;
    private final int maxPendingGlobal;
    private final int maxPendingPerIp;
    private final AtomicInteger pendingGlobal = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> pendingPerIp = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BukkitTask> fallbackTasks = new ConcurrentHashMap<>();

    public PremiumVerificationListener(AuthSystem plugin, PremiumLoginVerifier verifier, PremiumAuthenticator authenticator,
                                       int maxPendingGlobal, int maxPendingPerIp) {
        this.plugin = plugin;
        this.verifier = verifier;
        this.authenticator = authenticator;
        this.maxPendingGlobal = Math.max(1, maxPendingGlobal);
        this.maxPendingPerIp = Math.max(1, maxPendingPerIp);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Login.Client.LOGIN_START) handleLoginStart(event);
        else if (event.getPacketType() == PacketType.Login.Client.ENCRYPTION_RESPONSE) handleEncryptionResponse(event);
    }

    private void handleLoginStart(PacketReceiveEvent event) {
        WrapperLoginClientLoginStart packet = new WrapperLoginClientLoginStart(event);
        String username = packet.getUsername();
        if (username == null || !username.matches("[A-Za-z0-9_]{3,16}")) return;

        User user = event.getUser();
        ClientVersion version = user.getClientVersion();
        UUID playerUuid = packet.getPlayerUUID().orElse(null);
        String ip = IpResolver.getUserIp(user);
        if (ip == null || ip.isBlank()) {
            LOGGER.warning("Nao foi possivel identificar o IP durante o handshake premium de " + username + ".");
            event.setCancelled(true);
            handleVerificationFailure(user, version, username, playerUuid, null, "IP indisponivel durante a verificacao premium.");
            return;
        }

        if (plugin.getPlayerDataManager().isRegistered(username) && !plugin.getPlayerDataManager().isPremiumIdentity(username)) {
            authenticator.clear(username, ip, playerUuid);
            event.setCancelled(true);
            resume(user, version, username, playerUuid);
            return;
        }

        String key = connectionKey(user);
        if (key == null) {
            LOGGER.warning("Nao foi possivel criar a chave da conexao durante o handshake premium de " + username + ".");
            event.setCancelled(true);
            handleVerificationFailure(user, version, username, playerUuid, null, "Chave da conexao indisponivel durante a verificacao premium.");
            return;
        }

        event.setCancelled(true);
        if (!tryAcquirePending(ip)) {
            LOGGER.fine("Limite de handshakes premium pendentes atingido para " + username + ".");
            handleVerificationFailure(user, version, username, playerUuid, ip, "Limite de verificacoes premium pendentes atingido.");
            return;
        }

        byte[] verifyToken = verifier.start(key, username);
        if (verifyToken == null) {
            releasePending(ip);
            LOGGER.fine("Handshake premium duplicado ignorado para " + username + ".");
            return;
        }

        PendingConnection pending = new PendingConnection(username, version, playerUuid, ip, System.currentTimeMillis());
        if (connections.putIfAbsent(key, pending) != null) {
            verifier.remove(key);
            releasePending(ip);
            LOGGER.warning("Reserva de conexao premium duplicada detectada para " + username + ".");
            handleVerificationFailure(user, version, username, playerUuid, ip, "Reserva de conexao premium duplicada.");
            return;
        }

        user.sendPacket(new WrapperLoginServerEncryptionRequest("", verifier.getPublicKey(), verifyToken, true));
        BukkitTask fallback = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            PendingConnection current = connections.remove(key);
            fallbackTasks.remove(key);
            if (current != null) {
                verifier.remove(key);
                releasePending(current.ip());
                handleVerificationFailure(user, current.version(), current.username(), current.playerUuid(), current.ip(), "Tempo limite da verificacao premium excedido.");
            }
        }, FALLBACK_MS / 50L);
        fallbackTasks.put(key, fallback);
    }

    private void handleEncryptionResponse(PacketReceiveEvent event) {
        User user = event.getUser();
        String key = connectionKey(user);
        if (key == null) return;
        PendingConnection pending = connections.get(key);
        if (pending == null || !verifier.hasPending(key)) return;
        BukkitTask fallback = fallbackTasks.remove(key);
        if (fallback != null) fallback.cancel();
        WrapperLoginClientEncryptionResponse packet = new WrapperLoginClientEncryptionResponse(event);
        Optional<byte[]> encryptedToken = packet.getEncryptedVerifyToken();
        byte[] encryptedSecret = packet.getEncryptedSharedSecret();
        event.setCancelled(true);
        if (encryptedToken.isEmpty() || encryptedSecret == null) { failAndHandle(key, pending, user, "Resposta de criptografia premium incompleta."); return; }
        if (!verifier.validateToken(key, encryptedToken.get())) {
            LOGGER.warning("Token de verificacao premium invalido para " + pending.username());
            failAndHandle(key, pending, user, "Token de verificacao premium invalido.");
            return;
        }

        final byte[] sharedSecret;
        try {
            sharedSecret = verifier.decrypt(encryptedSecret);
            if (sharedSecret.length != 16) throw new GeneralSecurityException("Chave AES invalida: tamanho " + sharedSecret.length);
            enableEncryption(user.getChannel(), sharedSecret);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            LOGGER.warning("Nao foi possivel ativar a criptografia premium para " + pending.username() + ": " + e.getMessage());
            failAndHandle(key, pending, user, "Falha ao ativar a criptografia da verificacao premium.");
            return;
        }

        int limiteConsultas = plugin.getConfig().getInt("max-verificacoes-mojang-por-minuto", 30);
        if (!plugin.getMojangRateLimiter().podeConsultar(pending.ip(), limiteConsultas)) {
            LOGGER.warning("Limite de verificacoes Mojang atingido para " + pending.username() + ".");
            failAndHandle(key, pending, user, "Limite de verificacoes na Mojang atingido.");
            return;
        }

        verifier.verify(key, sharedSecret).thenAccept(result -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!connections.remove(key, pending)) return;
            releasePending(pending.ip());
            UUID mojangUuid = result.orElse(null);
            if (mojangUuid != null && pending.playerUuid() != null) {
                authenticator.markVerified(pending.username(), pending.ip(), pending.playerUuid(), mojangUuid);
                plugin.getPlayerDataManager().markPremiumIdentity(pending.username(), mojangUuid, pending.ip());
                LOGGER.info("Identidade premium verificada e protegida para " + pending.username());
                resume(user, pending.version(), pending.username(), pending.playerUuid());
            } else if (mojangUuid != null) {
                LOGGER.warning("A Mojang confirmou a conta premium de " + pending.username() + ", mas o cliente nao apresentou UUID de conexao valido.");
                handleVerificationFailure(user, pending.version(), pending.username(), pending.playerUuid(), pending.ip(), "UUID da conexao indisponivel apos a verificacao premium.");
            } else {
                LOGGER.info("Sessao da Mojang nao confirmada para " + pending.username() + ".");
                handleVerificationFailure(user, pending.version(), pending.username(), pending.playerUuid(), pending.ip(), "A Mojang nao confirmou esta sessao.");
            }
        }));
    }

    private boolean tryAcquirePending(String ip) {
        while (true) {
            int atual = pendingGlobal.get();
            if (atual >= maxPendingGlobal) return false;
            if (!pendingGlobal.compareAndSet(atual, atual + 1)) continue;
            AtomicInteger porIp = pendingPerIp.computeIfAbsent(ip, ignored -> new AtomicInteger());
            int ipAtual = porIp.incrementAndGet();
            if (ipAtual <= maxPendingPerIp) return true;
            porIp.decrementAndGet();
            if (porIp.get() == 0) pendingPerIp.remove(ip, porIp);
            pendingGlobal.decrementAndGet();
            return false;
        }
    }

    private void releasePending(String ip) {
        pendingGlobal.updateAndGet(valor -> Math.max(0, valor - 1));
        AtomicInteger porIp = pendingPerIp.get(ip);
        if (porIp != null) {
            int restante = porIp.updateAndGet(valor -> Math.max(0, valor - 1));
            if (restante == 0) pendingPerIp.remove(ip, porIp);
        }
    }

    public void cleanupExpired() {
        long agora = System.currentTimeMillis();
        connections.entrySet().removeIf(entry -> {
            PendingConnection pending = entry.getValue();
            if (agora - pending.createdAt() < CONNECTION_TTL_MS) return false;
            BukkitTask task = fallbackTasks.remove(entry.getKey());
            if (task != null) task.cancel();
            verifier.remove(entry.getKey());
            releasePending(pending.ip());
            return true;
        });
    }

    private void failAndHandle(String key, PendingConnection pending, User user, String reason) {
        BukkitTask fallback = fallbackTasks.remove(key);
        if (fallback != null) fallback.cancel();
        if (!connections.remove(key, pending)) return;
        verifier.remove(key);
        releasePending(pending.ip());
        handleVerificationFailure(user, pending.version(), pending.username(), pending.playerUuid(), pending.ip(), reason);
    }

    private void handleVerificationFailure(User user, ClientVersion version, String username, UUID playerUuid, String ip, String reason) {
        boolean protectedPremium = plugin.getPlayerDataManager().isPremiumIdentity(username);
        if (protectedPremium) {
            LOGGER.warning("Acesso cracked recusado para a conta premium protegida " + username + ": " + reason);
            disconnect(user, PREMIUM_ACCOUNT_MESSAGE);
            return;
        }

        String action = plugin.getPremiumFailureAction();
        if ("kick".equals(action)) {
            LOGGER.warning("Verificacao premium recusada para " + username + ": " + reason + " (acao=kick)");
            disconnect(user, "Nao foi possivel verificar sua conta premium. Tente novamente.");
            return;
        }
        if (user != null) resume(user, version, username, playerUuid);
    }

    private void disconnect(User user, String message) {
        if (user == null) return;
        try {
            user.sendPacket(new WrapperLoginServerDisconnect(Component.text(message)));
        } catch (Exception ignored) {
            // Se o pacote de disconnect nao puder ser enviado, o fechamento do canal ainda protege a conta.
        }
        Object rawChannel = user.getChannel();
        if (rawChannel instanceof Channel channel) channel.close();
    }

    private void resume(User user, ClientVersion version, String username, UUID playerUuid) {
        try { user.receivePacketSilently(new WrapperLoginClientLoginStart(version, username, null, playerUuid)); }
        catch (Exception e) { LOGGER.warning("Nao foi possivel continuar o login de " + username + ": " + e.getMessage()); }
    }

    private void enableEncryption(Object channel, byte[] sharedSecret) throws GeneralSecurityException {
        SecretKeySpec key = new SecretKeySpec(sharedSecret, "AES");
        IvParameterSpec iv = new IvParameterSpec(sharedSecret);
        Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, key, iv);
        Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE, key, iv);
        ChannelPipeline pipeline = (ChannelPipeline) ChannelHelper.getPipeline(channel);
        if (pipeline.get("authsystem-decrypt") == null) pipeline.addBefore("splitter", "authsystem-decrypt", new AesCfb8Decoder(decrypt));
        if (pipeline.get("authsystem-encrypt") == null) pipeline.addBefore("prepender", "authsystem-encrypt", new AesCfb8Encoder(encrypt));
    }

    /** Usa o identificador do canal, e nao IP:porta, para impedir colisao entre conexoes. */
    private static String connectionKey(User user) {
        if (user == null || user.getChannel() == null) return null;
        Object rawChannel = user.getChannel();
        if (!(rawChannel instanceof Channel channel)) return null;
        return channel.id().asLongText();
    }

    private record PendingConnection(String username, ClientVersion version, UUID playerUuid, String ip, long createdAt) {}
    private static final class AesCfb8Decoder extends MessageToMessageDecoder<ByteBuf> {
        private final Cipher cipher;
        private AesCfb8Decoder(Cipher cipher) { this.cipher = cipher; }
        @Override protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) { byte[] input = new byte[msg.readableBytes()]; msg.readBytes(input); out.add(io.netty.buffer.Unpooled.wrappedBuffer(cipher.update(input))); }
    }
    private static final class AesCfb8Encoder extends MessageToByteEncoder<ByteBuf> {
        private final Cipher cipher;
        private AesCfb8Encoder(Cipher cipher) { this.cipher = cipher; }
        @Override protected void encode(ChannelHandlerContext ctx, ByteBuf input, ByteBuf out) { byte[] data = new byte[input.readableBytes()]; input.readBytes(data); out.writeBytes(cipher.update(data)); }
    }
}
