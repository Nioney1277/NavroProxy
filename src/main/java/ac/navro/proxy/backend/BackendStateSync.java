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
    private static final int MAX_ALERT_BYTES = 4096;
    private final SessionState state;
    private final java.util.function.LongSupplier clock;
    private String nonce;
    private long sequence=-1, receivedAt;
    private int tick;
    private boolean active;
    private boolean pocketMineAlerts;
    private boolean paletteReceived;
    private boolean runtimePalette;
    public BackendStateSync(SessionState state) { this(state,System::nanoTime); }
    BackendStateSync(SessionState state,java.util.function.LongSupplier clock) {
        this.state=state;this.clock=clock;
    }
    public void reset() { nonce=null;sequence=-1;active=false;pocketMineAlerts=false;receivedAt=0; }
    public ScriptMessagePacket begin() {
        reset();nonce=UUID.randomUUID().toString();
        var hello=new ScriptMessagePacket();hello.setChannel(CHANNEL);hello.setMessage("hello|"+nonce);
        return hello;
    }
    public static boolean privatePacket(BedrockPacket packet) {
        return packet instanceof ScriptMessagePacket message && CHANNEL.equals(message.getChannel());
    }

    public ScriptMessagePacket alert(String message) {
        if (!pocketMineAlerts || !available() || message.getBytes(StandardCharsets.UTF_8).length > MAX_ALERT_BYTES)
            return null;
        var packet = new ScriptMessagePacket();
        packet.setChannel(CHANNEL);
        packet.setMessage("alert|" + nonce + "|" + message);
        return packet;
    }

    private void receivePalette(String message) {
        if (nonce != null || paletteReceived || message.length() > MAX_PALETTE_MESSAGE) return;
        paletteReceived = true;
        try {
            byte[] compressed = Base64.getDecoder().decode(message.substring("palette|".length()));
            var palette = NetworkBlockStateRegistry.readRuntimePalette(new ByteArrayInputStream(compressed));
            if (palette.protocol() == state.world().protocolVersion()) {
                state.setRuntimeBlockPalette(palette);
                runtimePalette = true;
            }
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("[NavroProxy] Invalid PM block palette: " + error.getMessage());
        }
    }

    public boolean receive(BedrockPacket packet) {
        if(!privatePacket(packet))return false;
        var message=((ScriptMessagePacket)packet).getMessage();
        if (message == null) return true;
        if (message.startsWith("palette|")) {
            receivePalette(message);
            return true;
        }
        if (nonce == null || message.length() > 256) return true;
        if (message.equals("pm_ready|" + nonce)) {
            pocketMineAlerts = true;
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
    public boolean isPocketMine() { return pocketMineAlerts; }
    public boolean hasRuntimePalette() { return runtimePalette; }
    public int tick() { return tick; }
}
