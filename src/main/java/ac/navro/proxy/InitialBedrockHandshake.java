package ac.navro.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Reads only the first protocol declaration; forwarded bytes are never rewritten. */
final class InitialBedrockHandshake {
    static final int MAX_BYTES = 3 * 1024 * 1024;
    record Declaration(int protocol, int raknetVersion) { }

    private InitialBedrockHandshake() { }

    static int protocol(ByteBuf frame, int raknetVersion) {
        return inspect(frame, raknetVersion).protocol();
    }

    static Declaration inspect(ByteBuf frame, int raknetVersion) {
        if (frame.readableBytes() < 2 || frame.readableBytes() > MAX_BYTES ||
                frame.getUnsignedByte(frame.readerIndex()) != 0xfe)
            throw new IllegalArgumentException("Invalid initial Bedrock batch");
        ByteBuf batch = frame.slice(frame.readerIndex() + 1, frame.readableBytes() - 1);
        if (raknetVersion >= 11) return new Declaration(readDeclaration(batch, true), raknetVersion);
        if (raknetVersion == 0) {
            try { return new Declaration(readDeclaration(batch.duplicate(), true), 11); }
            catch (IllegalArgumentException ignored) { }
        }
        byte[] compressed = new byte[batch.readableBytes()];
        batch.getBytes(batch.readerIndex(), compressed);
        // Older RakNet clients used both wrapped zlib and raw DEFLATE batches.
        byte[] clear;
        boolean wrapped = false;
        try { clear = inflate(compressed, true); }
        catch (IllegalArgumentException rawFailure) { clear = inflate(compressed, false); wrapped = true; }
        ByteBuf decoded = Unpooled.wrappedBuffer(clear);
        try { return new Declaration(readDeclaration(decoded, false),
                raknetVersion != 0 ? raknetVersion : wrapped ? 9 : 10); }
        finally { decoded.release(); }
    }

    private static int readDeclaration(ByteBuf batch, boolean networkSettings) {
        try {
            int length = VarInts.readUnsignedInt(batch);
            if (length < 5 || length > batch.readableBytes())
                throw new IllegalArgumentException("Truncated initial Bedrock packet");
            ByteBuf packet = batch.readSlice(length);
            int header = VarInts.readUnsignedInt(packet);
            int packetId = header & 0x3ff;
            if (header != packetId || packetId != (networkSettings ? 193 : 1))
                throw new IllegalArgumentException("Expected initial Bedrock protocol declaration");
            int protocol = packet.readInt();
            if (!ConnectionProtocols.accepts(protocol))
                throw new IllegalArgumentException("Unsupported Bedrock protocol " + protocol);
            if (networkSettings && protocol < 554 || !networkSettings && protocol >= 554)
                throw new IllegalArgumentException("Protocol declaration does not match RakNet framing");
            return protocol;
        } catch (IndexOutOfBoundsException error) {
            throw new IllegalArgumentException("Truncated initial Bedrock protocol declaration", error);
        }
    }

    private static byte[] inflate(byte[] compressed, boolean raw) {
        var inflater = new Inflater(raw);
        try (var result = new ByteArrayOutputStream()) {
            inflater.setInput(compressed);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && !inflater.finished())
                    throw new IllegalArgumentException("Invalid initial compressed Bedrock batch");
                if (count > MAX_BYTES - result.size())
                    throw new IllegalArgumentException("Initial Bedrock batch exceeds size limit");
                result.write(buffer, 0, count);
            }
            if (inflater.getRemaining() != 0)
                throw new IllegalArgumentException("Trailing compressed Bedrock data");
            return result.toByteArray();
        } catch (DataFormatException | java.io.IOException error) {
            throw new IllegalArgumentException("Invalid initial compressed Bedrock batch", error);
        } finally { inflater.end(); }
    }
}
