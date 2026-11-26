package ac.navro.proxy.nethernet;

import ac.navro.proxy.ConnectionProtocols;
import ac.navro.proxy.protocol.*;
import io.netty.buffer.*;
import org.cloudburstmc.protocol.bedrock.codec.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Connection compatibility without creating any anti-cheat player/world state. */
final class ConnectionBatchSession implements AutoCloseable {
    enum Encoding { NONE, RAW, ZLIB, PREFIXED }
    record Initial(int protocol, Encoding encoding) { }
    private static final int LIMIT = NetherNetBatchSession.MAX_BATCH;
    private final int protocol;
    private final BedrockCodec codec;
    private final BedrockCodecHelper helper;
    private final int scriptMessageId;
    private final PublicKey transportKey;
    private final KeyPair backendKey;
    private final RakNetBackendEncryption raknetBackend;
    private final boolean raknetFrontend;
    private final InetAddress forwardedPeer;
    private final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    private final Map<String,Long> counts = new TreeMap<>();
    private Encoding frontendEncoding, backendEncoding;
    private RakNetBackendEncryption.BatchCipher frontendCipher, backendCipher;
    private byte[] deferredLogin;
    private boolean verified, authenticated, closed;

    ConnectionBatchSession(Initial initial, PublicKey transportKey, KeyPair backendKey,
                           boolean raknetBackend, boolean raknetFrontend, InetAddress forwardedPeer,
                           PrivateKey signer) {
        this.protocol = initial.protocol(); this.codec = ConnectionProtocols.codec(protocol);
        this.helper = codec.createHelper(); this.transportKey = transportKey; this.backendKey = backendKey;
        var scriptDefinition = codec.getPacketDefinition(ScriptMessagePacket.class);
        scriptMessageId = scriptDefinition == null ? -1 : scriptDefinition.getId();
        this.raknetFrontend = raknetFrontend; this.forwardedPeer = forwardedPeer;
        this.raknetBackend = raknetBackend ? new RakNetBackendEncryption(signer) : null;
        frontendEncoding = initial.encoding();
        backendEncoding = protocol < 554 && raknetBackend
                ? initial.encoding() == Encoding.ZLIB ? Encoding.ZLIB : Encoding.RAW : Encoding.NONE;
    }

    static Initial initial(byte[] batch) throws IOException {
        for (Encoding encoding : new Encoding[]{Encoding.NONE, Encoding.RAW, Encoding.ZLIB}) {
            try {
                ByteBuf data = Unpooled.wrappedBuffer(unpack(batch, encoding));
                try {
                    int length = VarInts.readUnsignedInt(data);
                    if (length < 5 || length > data.readableBytes()) continue;
                    var packet = data.readSlice(length); int id = VarInts.readUnsignedInt(packet);
                    int version = packet.readInt();
                    if ((id == 193 && version >= 554 || id == 1 && version < 554) && ConnectionProtocols.accepts(version))
                        return new Initial(version, encoding);
                } finally { data.release(); }
            } catch (IOException | RuntimeException ignored) { }
        }
        throw new IOException("Expected a supported Bedrock login or network-settings declaration");
    }

    NetherNetBatchSession.Result process(boolean backend, byte[] bytes) throws Exception {
        if (closed) throw new IOException("Session closed");
        if (backend && backendCipher != null) bytes = backendCipher.decrypt(bytes);
        if (!backend && frontendCipher != null) bytes = frontendCipher.decrypt(bytes);
        Encoding destination = backend ? frontendEncoding : backendEncoding;
        ByteBuf input = Unpooled.wrappedBuffer(unpack(bytes, backend ? backendEncoding : frontendEncoding));
        ByteBuf output = Unpooled.buffer();
        var clients = new ArrayList<byte[]>(); var backends = new ArrayList<byte[]>(); var names = new ArrayList<String>();
        int frames = 0;
        try {
            while (input.isReadable()) {
                if (++frames > 4096) throw new IOException("Too many Bedrock frames in one batch");
                int length = VarInts.readUnsignedInt(input);
                if (length < 1 || length > input.readableBytes()) throw new IOException("Invalid Bedrock frame size");
                ByteBuf frame = input.readSlice(length), original = frame.duplicate();
                int header = VarInts.readUnsignedInt(frame), id = header & 1023;
                if (header != id) throw new IOException("Subclients are not supported");
                boolean control = id == 1 || id == 3 || id == 4 || id == 143 || id == 193 || id == 5 || id == scriptMessageId;
                if (!control) {
                    if (!authenticated) throw new IOException("Game packet before authenticated login");
                    writeFrame(output, original); continue;
                }
                BedrockPacket packet = codec.tryDecode(helper, frame, id);
                try {
                    if (frame.isReadable()) throw new IOException("Trailing bytes in login/settings packet");
                    names.add(packet.getClass().getSimpleName());
                    counts.merge((backend ? "backend:" : "client:") + packet.getClass().getSimpleName(), 1L, Long::sum);
                    if (ac.navro.proxy.backend.BackendStateSync.privatePacket(packet)) continue;
                    if (packet instanceof ScriptMessagePacket && !authenticated)
                        throw new IOException("Script message before authenticated login");
                    if (packet instanceof RequestNetworkSettingsPacket settings) {
                        if (backend || verified || settings.getProtocolVersion() != protocol)
                            throw new IOException("Invalid network-settings request");
                    } else if (packet instanceof NetworkSettingsPacket settings) {
                        if (!backend || verified || settings.getCompressionAlgorithm() != org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm.ZLIB)
                            throw new IOException("Unsupported or unexpected backend compression settings");
                        frontendEncoding = backendEncoding = protocol >= 649 ? Encoding.PREFIXED : Encoding.RAW;
                    } else if (packet instanceof LoginPacket login) {
                        if (backend || verified || login.getProtocolVersion() != protocol) throw new IOException("Unexpected login");
                        byte[] forwarded = encodeFrame(login(login));
                        if (raknetFrontend) {
                            deferredLogin = forwarded;
                            var keys = EncryptionUtils.createKeyPair(); byte[] salt = EncryptionUtils.generateRandomToken();
                            var handshake = new ServerToClientHandshakePacket();
                            handshake.setJwt(EncryptionUtils.createHandshakeJwt(keys, salt));
                            clients.add(packFrame(encodeFrame(handshake), frontendEncoding));
                            frontendCipher = new RakNetBackendEncryption.BatchCipher(
                                    EncryptionUtils.getSecretKey(keys.getPrivate(), verifiedLoginKey, salt), protocol >= 428);
                        } else { VarInts.writeUnsignedInt(output, forwarded.length); output.writeBytes(forwarded); }
                        continue;
                    } else if (packet instanceof ClientToServerHandshakePacket) {
                        if (backend || !raknetFrontend || deferredLogin == null || authenticated)
                            throw new IOException("Unexpected frontend encryption confirmation");
                        authenticated = true; backends.add(packFrame(deferredLogin, backendEncoding)); deferredLogin = null;
                        continue;
                    } else if (packet instanceof ServerToClientHandshakePacket handshake) {
                        if (!backend || raknetBackend == null || !authenticated || backendCipher != null)
                            throw new IOException("Unexpected backend encryption handshake");
                        backendCipher = raknetBackend.completeBatchHandshake(handshake.getJwt());
                        backends.add(backendCipher.encrypt(packFrame(encodeFrame(new ClientToServerHandshakePacket()), backendEncoding)));
                        continue;
                    }
                    writeFrame(output, original);
                } finally { io.netty.util.ReferenceCountUtil.safeRelease(packet); }
            }
            byte[] forwarded = output.isReadable() ? pack(ByteBufUtil.getBytes(output), destination) : null;
            if (forwarded != null && backend && frontendCipher != null) forwarded = frontendCipher.encrypt(forwarded);
            if (forwarded != null && !backend && backendCipher != null) forwarded = backendCipher.encrypt(forwarded);
            return new NetherNetBatchSession.Result(forwarded, clients, backends, names, authenticated);
        } finally { input.release(); output.release(); }
    }

    private PublicKey verifiedLoginKey;
    private LoginPacket login(LoginPacket login) throws Exception {
        if (login.getAuthPayload() == null || login.getAuthPayload().getAuthType() == org.cloudburstmc.protocol.bedrock.data.auth.AuthType.GUEST)
            throw new IOException("Missing authenticated player login");
        var chain = EncryptionUtils.validatePayload(login.getAuthPayload());
        var identity = LoginIdentity.fromVerifiedChain(chain);
        verifiedLoginKey = chain.identityClaims().parsedIdentityPublicKey();
        if (!raknetFrontend && (transportKey == null || !MessageDigest.isEqual(transportKey.getEncoded(), verifiedLoginKey.getEncoded())))
            throw new IOException("Transport identity differs from game login");
        byte[] data = EncryptionUtils.verifyClientData(login.getClientJwt(), verifiedLoginKey);
        if (data == null) throw new IOException("Invalid client-data signature");
        if (forwardedPeer != null) {
            String xuid = chain.identityClaims().extraData.xuid;
            if (xuid == null || !xuid.matches("[0-9]{1,20}")) throw new IOException("Missing forwarded XUID");
            var json = com.google.gson.JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            json.addProperty("Waterdog_XUID", xuid); json.addProperty("Waterdog_IP", forwardedPeer.getHostAddress());
            data = json.toString().getBytes(StandardCharsets.UTF_8);
        }
        verified = true; authenticated = !raknetFrontend;
        if (raknetBackend != null) return raknetBackend.login(protocol, identity, chain.rawIdentityClaims(), data);
        if (backendKey == null) throw new IOException("Missing backend session key");
        return PrivateBackendLogin.encode(protocol, identity, chain.rawIdentityClaims(), data, backendKey);
    }

    private byte[] encodeFrame(BedrockPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            VarInts.writeUnsignedInt(buffer, codec.getPacketDefinition(packet.getClass()).getId());
            codec.tryEncode(helper, buffer, packet); return ByteBufUtil.getBytes(buffer);
        } finally { buffer.release(); }
    }
    private static void writeFrame(ByteBuf output, ByteBuf frame) {
        VarInts.writeUnsignedInt(output, frame.readableBytes()); output.writeBytes(frame, frame.readerIndex(), frame.readableBytes());
    }
    private static byte[] packFrame(byte[] frame, Encoding encoding) throws IOException {
        ByteBuf buffer = Unpooled.buffer();
        try { VarInts.writeUnsignedInt(buffer, frame.length); buffer.writeBytes(frame); return pack(ByteBufUtil.getBytes(buffer), encoding); }
        finally { buffer.release(); }
    }
    static byte[] unpack(byte[] bytes, Encoding encoding) throws IOException {
        if (bytes.length == 0 || bytes.length > LIMIT) throw new IOException("Invalid batch size");
        if (encoding == Encoding.NONE) return bytes;
        int offset = 0;
        if (encoding == Encoding.PREFIXED) {
            if ((bytes[0] & 255) == 255) return Arrays.copyOfRange(bytes, 1, bytes.length);
            if (bytes[0] != 0) throw new IOException("Unsupported compression prefix");
            offset = 1;
        }
        var inflater = new Inflater(encoding != Encoding.ZLIB);
        try (var out = new ByteArrayOutputStream()) {
            inflater.setInput(bytes, offset, bytes.length - offset); byte[] chunk = new byte[8192];
            while (!inflater.finished()) {
                int size = inflater.inflate(chunk);
                if (size == 0 && !inflater.finished() || size > LIMIT - out.size()) throw new IOException("Invalid or oversized compressed batch");
                out.write(chunk, 0, size);
            }
            if (inflater.getRemaining() != 0) throw new IOException("Trailing compressed batch data");
            return out.toByteArray();
        } catch (DataFormatException error) { throw new IOException("Invalid compressed batch", error); }
        finally { inflater.end(); }
    }
    static byte[] pack(byte[] bytes, Encoding encoding) throws IOException {
        if (bytes.length > LIMIT) throw new IOException("Oversized batch");
        if (encoding == Encoding.NONE) return bytes;
        if (encoding == Encoding.PREFIXED) {
            byte[] prefixed = new byte[bytes.length + 1]; prefixed[0] = (byte)255;
            System.arraycopy(bytes, 0, prefixed, 1, bytes.length); return prefixed;
        }
        var deflater = new Deflater(6, encoding != Encoding.ZLIB);
        try (var out = new ByteArrayOutputStream()) {
            deflater.setInput(bytes); deflater.finish(); byte[] chunk = new byte[8192];
            while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk));
            return out.toByteArray();
        } finally { deflater.end(); }
    }
    NetherNetBatchSession.Result tick() throws IOException {
        if (!authenticated && System.nanoTime() > deadline) throw new IOException("Player login timed out");
        return new NetherNetBatchSession.Result(null, List.of(), List.of(), List.of(), authenticated);
    }
    Map<String,Long> counts() { return Map.copyOf(counts); }
    boolean authenticated() { return authenticated; }
    @Override public void close() { closed = true; deferredLogin = null; }
}
