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

/** Real premium challenge for an offline-mode server. */
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
        if (username == null || username.length() < 2 || username.length() > 16) return;

        User user = event.getUser();
        ClientVersion version = user.getClientVersion();
        String key = connectionKey(user);
        UUID playerUuid = packet.getPlayerUUID().orElse(null);
        String ip = user.getAddress().getAddress().getHostAddress();
        event.setCancelled(true);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            // Name existence is only a candidate filter; it is NEVER the premium decision.
            boolean candidate = PremiumChecker.isPremium(username);
            if (!candidate) {
                connections.remove(key);
                resume(user, version, username, playerUuid);
                return;
            }

            byte[] verifyToken = verifier.start(key, username);
            connections.put(key, new PendingConnection(username, version, playerUuid, ip));
            user.sendPacket(new WrapperLoginServerEncryptionRequest(
                    "", verifier.getPublicKey(), verifyToken, true));

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
        if (pending == null || !verifier.hasPending(key)) return;

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
            enableEncryption(user.getChannel(), sharedSecret);
        } catch (GeneralSecurityException e) {
            verifier.remove(key);
            resume(user, pending.version(), pending.username(), pending.playerUuid());
            return;
        }

        verifier.verify(key, sharedSecret, token.get()).thenAccept(result -> {
            result.ifPresent(uuid -> {
                authenticator.markVerified(pending.username(), pending.ip(), uuid);
                LOGGER.info("Premium identity verified for " + pending.username());
            });
            resume(user, pending.version(), pending.username(), pending.playerUuid());
        });
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
