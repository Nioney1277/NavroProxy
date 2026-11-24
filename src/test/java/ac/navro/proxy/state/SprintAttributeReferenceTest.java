package ac.navro.proxy.state;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class SprintAttributeReferenceTest {
    @Test void delayedAttributeAndMetadataAcksMatchOriginalPlayerDataAcrossRapidSprintEdges()throws Exception {
        var gson=new Gson();int rows=0;var operations=new HashSet<Integer>();
        try(var in=new BufferedReader(new InputStreamReader(new GZIPInputStream(getClass().getResourceAsStream("/navro-sprint-attributes.jsonl.gz")),StandardCharsets.UTF_8))) {
            var header=JsonParser.parseString(in.readLine()).getAsJsonObject();assertEquals("NSA1",header.get("magic").getAsString());
            for(int sn=0;sn<header.get("sessions").getAsInt();sn++) {
                var s=MovementStateSyncTest.session();var w=s.player();int pending=0;
                for(int step=0;step<header.get("steps").getAsInt();step++) {
                    var row=JsonParser.parseString(in.readLine()).getAsJsonObject();int op=row.get("op").getAsInt();boolean sprint=row.get("sprint").getAsBoolean();
                    switch(op) {
                        case 0 -> w.setPredictionSprintingAction(sprint);
                        case 1 -> {w.getLatencyUtil().queue(1000+step,true);s.observeBackend(SprintAttributeSyncTest.attributes(s,row.get("base").getAsFloat(),row.get("value").getAsFloat()));pending++;}
                        case 2 -> {w.getLatencyUtil().queue(1000+step,true);s.observeBackend(MovementStateSyncTest.metadata(s,sprint?Set.of(3):Set.of(),true,false));pending++;}
                        case 3 -> {assertTrue(w.getLatencyUtil().onOrderedResponse());pending--;}
                        case 4 -> SprintAttributeSyncTest.input(s,step);
                        default -> throw new AssertionError(op);
                    }
                    var speed=SprintAttributeSyncTest.speed(s);
                    var actual=Map.of("base",Float.floatToRawIntBits(speed.getBaseValue()),"value",Float.floatToRawIntBits(speed.getValue()),
                            "modifiers",speed.getModifiers().size(),"client",w.clientSprinting(),"server",w.serverSprinting(),
                            "flag",w.hasFlag(3),"prediction",w.predictionSprinting(),"pending",pending);
                    assertEquals(row.get("state"),gson.toJsonTree(actual),"session="+sn+" step="+step+" op="+op);
                    rows++;operations.add(op);
                }
            }
            assertNull(in.readLine());
        }
        assertEquals(4096,rows);assertEquals(5,operations.size());
    }
}
