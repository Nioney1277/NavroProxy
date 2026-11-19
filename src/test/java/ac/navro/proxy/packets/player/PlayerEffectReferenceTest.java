package ac.navro.proxy.packets.player;

import ac.navro.proxy.SessionState;
import com.google.gson.*;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class PlayerEffectReferenceTest {
    static SessionState session() {
        var s = new SessionState(); s.setProtocolVersion(2193); s.player().setProtocolVersion(2193);
        var start = new StartGamePacket(); start.setRuntimeEntityId(7); start.setPlayerPosition(Vector3f.ZERO);
        s.observeBackend(start); s.setDetectionEnabled(true); s.authInputPackets(); return s;
    }
    static MobEffectPacket packet(long entity, MobEffectPacket.Event event, int id, int amplifier, int duration) {
        var packet = new MobEffectPacket(); packet.setRuntimeEntityId(entity); packet.setEvent(event);
        packet.setEffectId(id); packet.setAmplifier(amplifier); packet.setDuration(duration); return packet;
    }

    @Test void originalEffectHandlerAndLifetimeStateMatchAcrossDelayedAcksAndTicks() throws Exception {
        var gson = new Gson(); int rows = 0; var seen = new HashSet<Integer>();
        try (var in = new BufferedReader(new InputStreamReader(new GZIPInputStream(
                getClass().getResourceAsStream("/navro-player-effects.jsonl.gz")), StandardCharsets.UTF_8))) {
            var header = JsonParser.parseString(in.readLine()).getAsJsonObject(); assertEquals("NPE1", header.get("magic").getAsString());
            for (int sn=0; sn<header.get("sessions").getAsInt(); sn++) {
                var s = session(); var w = s.player(); w.setPredictionSprintingAction(true);
                for (int step=0; step<header.get("steps").getAsInt(); step++) {
                    var row=JsonParser.parseString(in.readLine()).getAsJsonObject(); int op=row.get("op").getAsInt();
                    switch (op) {
                        case 0 -> {


                            w.getLatencyUtil().queue(row.get("marker").getAsLong(),true);
                            var packet=packet(row.get("entity").getAsLong(), MobEffectPacket.Event.values()[row.get("event").getAsInt()],
                                    row.get("effect").getAsInt(),row.get("amplifier").getAsInt(),row.get("duration").getAsInt());
                            s.observeBackend(packet);
                            packet.setRuntimeEntityId(99); packet.setEffectId(999); packet.setAmplifier(99);
                            packet.setDuration(999); packet.setEvent(MobEffectPacket.Event.REMOVE);
                        }
                        case 1 -> assertEquals(row.get("accepted").getAsBoolean(),w.getLatencyUtil().onResponse(row.get("marker").getAsLong()));
                        case 2 -> w.tickPredictionEffects();
                        default -> throw new AssertionError(op);
                    }
                    var effects=new TreeMap<Integer,List<Integer>>();
                    w.effects().forEach((id,effect)->effects.put(id,List.of(effect.amplifier(),effect.duration())));
                    var speed=w.attributes().get("minecraft:movement");
                    var actual=Map.of("effects",effects,"speedBits",Float.floatToRawIntBits(speed.getValue()),
                            "modifiers",speed.getModifiers().size(),"pending",w.getLatencyUtil().sentQueue().size());
                    assertEquals(row.get("state"),gson.toJsonTree(actual),"session="+sn+" step="+step+" op="+op);
                    rows++;seen.add(op);
                }
            }
            assertNull(in.readLine());
        }
        assertEquals(1536,rows); assertEquals(Set.of(0,1,2),seen);
    }
}
