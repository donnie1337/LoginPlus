package com.authsystem.listeners;

import com.authsystem.util.PremiumAuthenticator;
import com.authsystem.util.PremiumChecker;
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
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

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

/**
 * Performs a real premium challenge in offline-mode.
 *
 * A Mojang name lookup is used only as a candidate filter. The final premium decision
 * is made only after the client proves possession of the Mojang session through the
 * normal RSA/AES login handshake and Mojang's hasJoined endpoint.
 */
public final class PremiumVerificationListener extends PacketListenerAbstract {
    private static final Logger LOGGER = Logger.getLogger("AuthSystem");
    private static final long FALLBACK_MS = 3000L;

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
        event.setCancelled(true);

        // We do the cheap name lookup asynchronously. It is NOT the authentication decision.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean candidate = PremiumChecker.isPremium(username);
            if (!candidate) {
                connections.remove(key);
                resume(user, version, username, playerUuid);
                return;
            }

            byte[] verifyToken = verifier.start(key, username);
            connections.put(key, new PendingConnection(username, version, playerUuid));
            WrapperLoginServerEncryptionRequest request =
                    new WrapperLoginServerEncryptionRequest("", verifier.getPublicKey(), verifyToken, true);
            user.sendPacket(request);

            // A cracked client may not answer the challenge. After a short grace period,
            // continue as cracked instead of trapping it at the login screen.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (connections.remove(key) != null && verifier.hasPending(key)) {
                    verifier.remove(key);
                    resume(user, version, username, playerUuid);
                }
            }, FALLBACK_MS / 50L);
        });
    }

    private void handleEncryptionResponse(PacketReceiveEvent event) {
        User user = event.getUser();
        String key = connectionKey(user);
        PendingConnection pending = connections.remove(key);
        if (pending == null || !verifier.hasPending(key)) {
            return;
        }

        WrapperLoginClientEncryptionResponse packet = new WrapperLoginClientEncryptionResponse(event);
        Optional<byte[]> token = packet.getEncryptedVerifyToken();
        if (token.isEmpty()) {
            verifier.remove(key);
            event.setCancelled(true);
            resume(user, pending.version(), pending.username(), pending.playerUuid());
            return;
        }

        event.setCancelled(true);
        byte[] sharedSecret;
        try {
            sharedSecret = verifier.decrypt(packet.getEncryptedSharedSecret());
        } catch (GeneralSecurityException e) {
            verifier.remove(key);
            resume(user, pending.version(), pending.username(), pending.playerUuid());
            return;
        }

        try {
            enableEncryption(user.getChannel(), sharedSecret);
        } catch (GeneralSecurityException e) {
            verifier.remove(key);
            resume(user, pending.version(), pending.username(), pending.playerUuid());
            return;
        }

        verifier.verify(key, sharedSecret, token.get()).thenAccept(result -> {
            result.ifPresent(uuid -> {
                authenticator.markVerified(pending.username(), uuid);
                LOGGER.info("Premium identity verified for " + pending.username());
            });
            resume(user, pending.version(), pending.username(), pending.playerUuid());
        });
    }

    private void resume(User user, ClientVersion version, String username, UUID playerUuid) {
        try {
            WrapperLoginClientLoginStart packet =
                    new WrapperLoginClientLoginStart(version, username, null, playerUuid);
            user.receivePacketSilently(packet);
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
        String decoderName = "decrypt";
        String encoderName = "encrypt";
        if (pipeline.get(decoderName) == null) {
            pipeline.addBefore("splitter", decoderName, new AesCfb8Decoder(decrypt));
        }
        if (pipeline.get(encoderName) == null) {
            pipeline.addBefore("prepender", encoderName, new AesCfb8Encoder(encrypt));
        }
    }

    private static String connectionKey(User user) {
        InetSocketAddress address = user.getAddress();
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    private record PendingConnection(String username, ClientVersion version, UUID playerUuid) {}

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
