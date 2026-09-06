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

/** Desafio real para verificar contas premium em um servidor offline-mode. */
public final class PremiumVerificationListener extends PacketListenerAbstract {
    private static final Logger LOGGER = Logger.getLogger("AuthSystem");
    private static final long FALLBACK_MS = 10000L;

    private final PremiumLoginVerifier verifier;
    private final PremiumAuthenticator authenticator;
    private final com.authsystem.AuthSystem plugin;
    private final ConcurrentHashMap<String, PendingConnection> connections = new ConcurrentHashMap<>();

    public PremiumVerificationListener(com.authsystem.AuthSystem plugin,
                                       PremiumLoginVerifier verifier,
                                       PremiumAuthenticator authenticator) {
        this.plugin = plugin;
        this.verifier = verifier;
        this.authenticator = authenticator;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Login.Client.LOGIN_START) {
            handleLoginStart(event);
        } else if (event.getPacketType() == PacketType.Login.Client.ENCRYPTION_RESPONSE) {
            handleEncryptionResponse(event);
        }
    }

    private void handleLoginStart(PacketReceiveEvent event) {
        WrapperLoginClientLoginStart packet = new WrapperLoginClientLoginStart(event);
        String username = packet.getUsername();
        if (username == null || username.length() < 2 || username.length() > 16) {
            return;
        }

        User user = event.getUser();
        ClientVersion version = user.getClientVersion();
        String key = connectionKey(user);
        UUID playerUuid = packet.getPlayerUUID().orElse(null);
        String ip = user.getAddress().getAddress().getHostAddress();

        event.setCancelled(true);

        byte[] verifyToken = verifier.start(key, username);
        connections.put(key, new PendingConnection(username, version, playerUuid, ip));
        user.sendPacket(new WrapperLoginServerEncryptionRequest(
                "", verifier.getPublicKey(), verifyToken, true));

        // Clientes cracked podem nao responder. Uma unica remocao atomica decide quem continua o login,
        // evitando uma corrida com a resposta assincrona da verificacao na Mojang.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            PendingConnection current = connections.remove(key);
            if (current != null) {
                verifier.remove(key);
                resume(user, current.version(), current.username(), current.playerUuid());
            }
        }, FALLBACK_MS / 50L);
    }

    private void handleEncryptionResponse(PacketReceiveEvent event) {
        User user = event.getUser();
        String key = connectionKey(user);
        PendingConnection pending = connections.get(key);
        if (pending == null || !verifier.hasPending(key)) {
            return;
        }

        WrapperLoginClientEncryptionResponse packet = new WrapperLoginClientEncryptionResponse(event);
        Optional<byte[]> encryptedToken = packet.getEncryptedVerifyToken();
        byte[] encryptedSecret = packet.getEncryptedSharedSecret();

        event.setCancelled(true);

        if (encryptedToken.isEmpty() || encryptedSecret == null) {
            failAndResume(key, pending, user);
            return;
        }

        // Valida o desafio antes de ativar o AES, impedindo que uma resposta RSA invalida altere a conexao.
        if (!verifier.validateToken(key, encryptedToken.get())) {
            LOGGER.warning("Invalid premium verify token for " + pending.username());
            failAndResume(key, pending, user);
            return;
        }

        final byte[] sharedSecret;
        try {
            sharedSecret = verifier.decrypt(encryptedSecret);
            if (sharedSecret.length != 16) {
                throw new GeneralSecurityException("Invalid AES secret length: " + sharedSecret.length);
            }
            enableEncryption(user.getChannel(), sharedSecret);
        } catch (GeneralSecurityException e) {
            LOGGER.warning("Could not enable premium encryption for " + pending.username() + ": " + e.getMessage());
            failAndResume(key, pending, user);
            return;
        }

        // A verificacao na Mojang e assincrona. A continuacao que toca na conexao do PacketEvents volta explicitamente para a thread principal do Bukkit.
        verifier.verify(key, sharedSecret).thenAccept(result ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!connections.remove(key, pending)) {
                        return;
                    }
                    result.ifPresentOrElse(
                            uuid -> {
                                authenticator.markVerified(pending.username(), pending.ip(), uuid);
                                LOGGER.info("Premium identity verified for " + pending.username());
                                resume(user, pending.version(), pending.username(), pending.playerUuid());
                            },
                            () -> {
                                LOGGER.info("Mojang session not confirmed for " + pending.username()
                                        + "; continuing as cracked.");
                                resume(user, pending.version(), pending.username(), pending.playerUuid());
                            });
                }));
    }

    private void failAndResume(String key, PendingConnection pending, User user) {
        if (!connections.remove(key, pending)) {
            return;
        }
        verifier.remove(key);
        resume(user, pending.version(), pending.username(), pending.playerUuid());
    }

    private void resume(User user, ClientVersion version, String username, UUID playerUuid) {
        try {
            user.receivePacketSilently(new WrapperLoginClientLoginStart(version, username, null, playerUuid));
        } catch (Exception e) {
            LOGGER.warning("Could not resume login for " + username + ": " + e.getMessage());
        }
    }

    private void enableEncryption(Object channel, byte[] sharedSecret) throws GeneralSecurityException {
        SecretKeySpec key = new SecretKeySpec(sharedSecret, "AES");
        IvParameterSpec iv = new IvParameterSpec(sharedSecret);
        Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, key, iv);
        Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE, key, iv);

        ChannelPipeline pipeline = (ChannelPipeline) ChannelHelper.getPipeline(channel);
        if (pipeline.get("authsystem-decrypt") == null) {
            pipeline.addBefore("splitter", "authsystem-decrypt", new AesCfb8Decoder(decrypt));
        }
        if (pipeline.get("authsystem-encrypt") == null) {
            pipeline.addBefore("prepender", "authsystem-encrypt", new AesCfb8Encoder(encrypt));
        }
    }

    private static String connectionKey(User user) {
        InetSocketAddress address = user.getAddress();
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    private record PendingConnection(String username, ClientVersion version, UUID playerUuid, String ip) {}

    private static final class AesCfb8Decoder extends MessageToMessageDecoder<ByteBuf> {
        private final Cipher cipher;
        private AesCfb8Decoder(Cipher cipher) { this.cipher = cipher; }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
            byte[] input = new byte[msg.readableBytes()];
            msg.readBytes(input);
            out.add(io.netty.buffer.Unpooled.wrappedBuffer(cipher.update(input)));
        }
    }

    private static final class AesCfb8Encoder extends MessageToByteEncoder<ByteBuf> {
        private final Cipher cipher;
        private AesCfb8Encoder(Cipher cipher) { this.cipher = cipher; }

        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf input, ByteBuf out) {
            byte[] data = new byte[input.readableBytes()];
            input.readBytes(data);
            out.writeBytes(cipher.update(data));
        }
    }
}
