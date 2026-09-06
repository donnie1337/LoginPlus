package com.authsystem.listeners;

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
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.bukkit.scheduler.BukkitTask;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Desafio criptografico usado para verificar contas premium em servidor offline-mode. */
public final class PremiumVerificationListener extends PacketListenerAbstract {
    private static final Logger LOGGER = Logger.getLogger("AuthSystem");
    private static final long FALLBACK_MS = 15000L;
    private final PremiumLoginVerifier verifier;
    private final PremiumAuthenticator authenticator;
    private final com.authsystem.AuthSystem plugin;
    private final ConcurrentHashMap<String, PendingConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BukkitTask> fallbackTasks = new ConcurrentHashMap<>();

    public PremiumVerificationListener(com.authsystem.AuthSystem plugin, PremiumLoginVerifier verifier, PremiumAuthenticator authenticator) {
        this.plugin = plugin;
        this.verifier = verifier;
        this.authenticator = authenticator;
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
        String ip = user.getAddress().getAddress().getHostAddress();

        // O banco local sempre vem primeiro. Se o nickname ja possui conta, nao fazemos handshake premium.
        if (plugin.getPlayerDataManager().isRegistered(username)) {
            authenticator.clear(username, ip);
            event.setCancelled(true);
            resume(user, version, username, playerUuid);
            return;
        }

        String key = connectionKey(user);
        event.setCancelled(true);
        byte[] verifyToken = verifier.start(key, username);
        if (verifyToken == null) {
            // Ja existe um desafio ativo para esta conexao. Nao sobrescreva token/estado.
            LOGGER.fine("Handshake premium duplicado ignorado para " + username + " (" + key + ").");
            return;
        }

        connections.put(key, new PendingConnection(username, version, playerUuid, ip));
        user.sendPacket(new WrapperLoginServerEncryptionRequest("", verifier.getPublicKey(), verifyToken, true));
        BukkitTask fallback = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            PendingConnection current = connections.remove(key);
            fallbackTasks.remove(key);
            if (current != null) {
                verifier.remove(key);
                resume(user, current.version(), current.username(), current.playerUuid());
            }
        }, FALLBACK_MS / 50L);
        fallbackTasks.put(key, fallback);
    }

    private void handleEncryptionResponse(PacketReceiveEvent event) {
        User user = event.getUser();
        String key = connectionKey(user);
        PendingConnection pending = connections.get(key);
        if (pending == null || !verifier.hasPending(key)) return;
        BukkitTask fallback = fallbackTasks.remove(key);
        if (fallback != null) fallback.cancel();
        WrapperLoginClientEncryptionResponse packet = new WrapperLoginClientEncryptionResponse(event);
        Optional<byte[]> encryptedToken = packet.getEncryptedVerifyToken();
        byte[] encryptedSecret = packet.getEncryptedSharedSecret();
        event.setCancelled(true);
        if (encryptedToken.isEmpty() || encryptedSecret == null) { failAndResume(key, pending, user); return; }
        if (!verifier.validateToken(key, encryptedToken.get())) {
            LOGGER.warning("Token de verificacao premium invalido para " + pending.username());
            failAndResume(key, pending, user);
            return;
        }

        final byte[] sharedSecret;
        try {
            sharedSecret = verifier.decrypt(encryptedSecret);
            if (sharedSecret.length != 16) throw new GeneralSecurityException("Chave AES invalida: tamanho " + sharedSecret.length);
            enableEncryption(user.getChannel(), sharedSecret);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            LOGGER.warning("Nao foi possivel ativar a criptografia premium para " + pending.username() + ": " + e.getMessage());
            failAndResume(key, pending, user);
            return;
        }

        int limiteConsultas = plugin.getConfig().getInt("max-verificacoes-mojang-por-minuto", 30);
        if (!plugin.getMojangRateLimiter().podeConsultar(pending.ip(), limiteConsultas)) {
            LOGGER.warning("Limite de verificacoes Mojang atingido para o IP " + pending.ip() + ". " + pending.username() + " continuara como cracked.");
            failAndResume(key, pending, user);
            return;
        }

        verifier.verify(key, sharedSecret).thenAccept(result -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!connections.remove(key, pending)) return;
            UUID mojangUuid = result.orElse(null);
            if (mojangUuid != null && pending.playerUuid() != null && mojangUuid.equals(pending.playerUuid())) {
                authenticator.markVerified(pending.username(), pending.ip(), mojangUuid);
                LOGGER.info("Identidade premium verificada para " + pending.username());
            } else if (mojangUuid != null) {
                LOGGER.warning("UUID da Mojang nao corresponde ao UUID enviado pelo cliente para " + pending.username() + ". Conexao tratada como cracked.");
            } else {
                LOGGER.info("Sessao da Mojang nao confirmada para " + pending.username() + "; continuando como cracked.");
            }
            resume(user, pending.version(), pending.username(), pending.playerUuid());
        }));
    }

    private void failAndResume(String key, PendingConnection pending, User user) {
        BukkitTask fallback = fallbackTasks.remove(key);
        if (fallback != null) fallback.cancel();
        if (!connections.remove(key, pending)) return;
        verifier.remove(key);
        resume(user, pending.version(), pending.username(), pending.playerUuid());
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

    private static String connectionKey(User user) {
        InetSocketAddress address = user.getAddress();
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    private record PendingConnection(String username, ClientVersion version, UUID playerUuid, String ip) {}
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
