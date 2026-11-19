package ac.navro.proxy.backend;

import ac.navro.proxy.SessionState;
import org.cloudburstmc.protocol.bedrock.packet.*;
import java.util.UUID;


public final class BackendStateSync {
    public static final String CHANNEL="navro:backend_sync_v1";
    private final SessionState state;
    private final java.util.function.LongSupplier clock;
    private String nonce;
    private long sequence=-1, receivedAt;
    private int tick;
    private boolean active;
    public BackendStateSync(SessionState state) { this(state,System::nanoTime); }
    BackendStateSync(SessionState state,java.util.function.LongSupplier clock) {
        this.state=state;this.clock=clock;
    }
    public void reset() { nonce=null;sequence=-1;active=false;receivedAt=0; }
    public ScriptMessagePacket begin() {
        reset();nonce=UUID.randomUUID().toString();
        var hello=new ScriptMessagePacket();hello.setChannel(CHANNEL);hello.setMessage("hello|"+nonce);
        return hello;
    }
    public static boolean privatePacket(BedrockPacket packet) {
        return packet instanceof ScriptMessagePacket message && CHANNEL.equals(message.getChannel());
    }

    public boolean receive(BedrockPacket packet) {
        if(!privatePacket(packet))return false;
        var message=((ScriptMessagePacket)packet).getMessage();
        if(nonce==null||message==null||message.length()>256)return true;
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
    public int tick() { return tick; }
}
