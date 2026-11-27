package ac.navro.proxy.backend;

import ac.navro.proxy.SessionState;
import org.cloudburstmc.protocol.bedrock.packet.*;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;


public final class BackendStateSync {
    public static final String CHANNEL="navro:backend_sync_v1";
    private static final int MAX_PALETTE_MESSAGE = 512 * 1024;
    private static final int PALETTE_PART_BYTES = 30_000;
    private static final int MAX_PALETTE_PARTS = (MAX_PALETTE_MESSAGE + PALETTE_PART_BYTES - 1) / PALETTE_PART_BYTES;
    private static final int MAX_ALERT_BYTES = 4096;
    private final SessionState state;
    private final java.util.function.LongSupplier clock;
    private volatile String nonce;
    private long sequence=-1, receivedAt;
    private int tick;
    private boolean active;
    private boolean pocketMineAlerts;
    private boolean nukkitAlerts;
    private volatile boolean commandBridgeReady;
    private long commandSequence=-1;
    private NetworkBlockStateRegistry.RuntimePalette pendingPalette;
    private StringBuilder paletteParts;
    private int expectedPart, palettePartCount;
    public BackendStateSync(SessionState state) { this(state,System::nanoTime); }
    BackendStateSync(SessionState state,java.util.function.LongSupplier clock) {
        this.state=state;this.clock=clock;
    }
    public void reset() {
        nonce=null;sequence=-1;tick=0;active=false;pocketMineAlerts=false;nukkitAlerts=false;receivedAt=0;
        commandBridgeReady=false;commandSequence=-1;
        pendingPalette = null;
        paletteParts = null;
        expectedPart = palettePartCount = 0;
        state.world().clearNativeServerClock();
    }
    public ScriptMessagePacket begin() {
        reset();nonce=UUID.randomUUID().toString();
        var hello=new ScriptMessagePacket();hello.setChannel(CHANNEL);hello.setMessage("hello|"+nonce);
        return hello;
    }
    public static boolean privatePacket(BedrockPacket packet) {
        return packet instanceof ScriptMessagePacket message && CHANNEL.equals(message.getChannel());
    }

    public ScriptMessagePacket alert(String message) {
        if (!(pocketMineAlerts || nukkitAlerts) || !available() || message.getBytes(StandardCharsets.UTF_8).length > MAX_ALERT_BYTES)
            return null;
        var packet = new ScriptMessagePacket();
        packet.setChannel(CHANNEL);
        packet.setMessage("alert|" + nonce + "|" + message);
        return packet;
    }

    private void receivePalette(String message) {
        if (pendingPalette != null || message.length() > MAX_PALETTE_MESSAGE) return;
        try {
            byte[] compressed = Base64.getDecoder().decode(message.substring("palette|".length()));
            var palette = NetworkBlockStateRegistry.readRuntimePalette(new ByteArrayInputStream(compressed));
            if (palette.protocol() == state.world().protocolVersion()) {
                pendingPalette = palette;
            }
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("[NavroProxy] Invalid PM block palette: " + error.getMessage());
        }
    }

    private void receivePalettePart(String message) {
        if (pendingPalette != null || message.length() > PALETTE_PART_BYTES + 32) return;
        String[] fields = message.split("\\|", 4);
        if (fields.length != 4) return;
        try {
            int index = Integer.parseInt(fields[1]);
            int count = Integer.parseInt(fields[2]);
            String part = fields[3];
            if (count < 2 || count > MAX_PALETTE_PARTS || index < 0 || index >= count
                    || part.isEmpty() || part.length() > PALETTE_PART_BYTES
                    || index < count - 1 && part.length() != PALETTE_PART_BYTES) {
                paletteParts = null;
                return;
            }
            if (index == 0) {
                paletteParts = new StringBuilder();
                palettePartCount = count;
                expectedPart = 0;
            }
            if (paletteParts == null || index != expectedPart || count != palettePartCount
                    || (long) paletteParts.length() + part.length() > MAX_PALETTE_MESSAGE) {
                paletteParts = null;
                return;
            }
            paletteParts.append(part);
            if (++expectedPart == count) {
                String complete = paletteParts.toString();
                paletteParts = null;
                if (complete.startsWith("palette|")) receivePalette(complete);
            }
        } catch (NumberFormatException ignored) {
            paletteParts = null;
        }
    }

    public boolean receive(BedrockPacket packet) {
        if(!privatePacket(packet))return false;
        var message=((ScriptMessagePacket)packet).getMessage();
        if (message == null) return true;
        if (message.startsWith("palette_part|")) {
            receivePalettePart(message);
            return true;
        }
        if (message.startsWith("palette|")) {
            receivePalette(message);
            return true;
        }
        if(nonce!=null&&message.startsWith("command|")) {
            receiveCommand(message);return true;
        }
        if (nonce == null || message.length() > 256) return true;
        if(message.equals("commands_ready|"+nonce)) {
            if(!(pocketMineAlerts||nukkitAlerts))return true;
            commandBridgeReady=true;
            var mode=new ScriptMessagePacket();mode.setChannel(CHANNEL);
            mode.setMessage("commands|"+nonce+"|"+state.commandSource().value());
            state.sendBackendSync(mode);return true;
        }
        if (message.equals("pm_ready|" + nonce)) {
            pocketMineAlerts = true;
            return true;
        }
        if (message.equals("nk_ready|" + nonce)) {
            nukkitAlerts = true;
            return true;
        }
        String[] fields=message.split("\\|",-1);
        if(fields.length!=4||!"tick".equals(fields[0])||!nonce.equals(fields[1]))return true;
        try {
            long next=Long.parseLong(fields[2]);int sample=Integer.parseInt(fields[3]);
            if(next<0||next<=sequence || active && sample-tick<0)return true;
            sequence=next;tick=sample;receivedAt=clock.getAsLong();active=true;
            state.world().setNativeServerClock(this::tick,this::available);
            var log=state.player().worldDiagnostics();
            if(log!=null)log.event("backend_native_tick",ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                    "sequence",sequence,"serverTick",tick));
        } catch(NumberFormatException ignored) { }
        return true;
    }
    public boolean available() { return active&&clock.getAsLong()-receivedAt<1_000_000_000L; }
    private void receiveCommand(String message) {
        if(!commandBridgeReady||state.proxyCommands()||message.length()>1024)return;
        String[] parts=message.split("\\|",-1);
        if(parts.length!=5||!nonce.equals(parts[1])||!java.util.Set.of("debug","debug2","reload","proxyping").contains(parts[3]))return;
        try {
            long next=Long.parseLong(parts[2]);
            if(next<0||next<=commandSequence)return;
            String target=new String(Base64.getDecoder().decode(parts[4]),StandardCharsets.UTF_8);
            if(target.length()>64||target.chars().anyMatch(Character::isISOControl)
                    ||parts[3].equals("reload")&&!target.isEmpty())return;
            commandSequence=next;
            String connectionNonce=nonce;
            state.bridgeCommand(parts[3],target,text->{
                if(!commandBridgeReady||!connectionNonce.equals(nonce)||state.proxyCommands())return;
                byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>4096)return;
                var reply=new ScriptMessagePacket();reply.setChannel(CHANNEL);
                reply.setMessage("command_reply|"+connectionNonce+"|"+parts[3]+"|"+Base64.getEncoder().encodeToString(bytes));
                state.sendBackendSync(reply);
            });
        } catch(IllegalArgumentException ignored) { }
    }
    public boolean isPocketMine() { return pocketMineAlerts; }
    public boolean hasBackendCommands() { return pocketMineAlerts || nukkitAlerts; }
    public boolean hasRuntimePalette() { return pendingPalette != null; }
    public NetworkBlockStateRegistry.RuntimePalette takeRuntimePalette() {
        var palette = pendingPalette;
        pendingPalette = null;
        return palette;
    }
    public int tick() { return tick; }
}
