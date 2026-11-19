package ac.navro.proxy.teleport;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SupportedCodecs;
import ac.navro.proxy.teleport.data.TeleportData;
import com.google.gson.*;
import io.netty.buffer.*;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class TeleportReferenceTest {
    static Vec3 vec(JsonArray a){return new Vec3(Float.intBitsToFloat(a.get(0).getAsInt()),Float.intBitsToFloat(a.get(1).getAsInt()),Float.intBitsToFloat(a.get(2).getAsInt()));}
    static List<Integer> bits(Vec3 v){return List.of(Float.floatToRawIntBits(v.x),Float.floatToRawIntBits(v.y),Float.floatToRawIntBits(v.z));}
    @Test void originalMovePlayerBytesAndConfirmationAndOrderedDrainMatch()throws Exception{
        var gson=new Gson();int count=0,errors=0;
        try(var in=new BufferedReader(new InputStreamReader(new GZIPInputStream(getClass().getResourceAsStream("/navro-teleport.jsonl.gz")),StandardCharsets.UTF_8))){
            var h=JsonParser.parseString(in.readLine()).getAsJsonObject();assertEquals("NTP1",h.get("magic").getAsString());
            for(String line;(line=in.readLine())!=null;){var row=JsonParser.parseString(line).getAsJsonObject();var target=vec(row.getAsJsonArray("target"));var rotation=vec(row.getAsJsonArray("rotation"));MovePlayerPacket[] sent={null};var util=new TeleportUtil(new TeleportUtil.Context(){
                public long runtimeEntityId(){return row.get("entity").getAsLong();}public Vec3 rotation(){return rotation;}public boolean sendPacket(MovePlayerPacket p){sent[0]=p;return true;}
                public void movementEvent(String s,Object...v){}public void swimStage(String s,Object...v){}public void itemUseStage(String s,Object...v){}
            });if(row.get("pending").getAsBoolean())util.getQueuedTeleports().add(new TeleportData(target));
            String error="";try{util.teleport(row.get("nil").getAsBoolean()?null:target);}catch(RuntimeException e){error=e.getClass().getSimpleName();}
            assertEquals(row.get("error").getAsString(),error,"native case "+count);if(!error.isEmpty())errors++;
            if(row.get("bytes").isJsonNull())assertNull(sent[0]);else{
                assertNotNull(sent[0]);var codec=SupportedCodecs.forProtocol(row.get("protocol").getAsInt());ByteBuf b=Unpooled.buffer();
                try{VarInts.writeUnsignedInt(b,codec.getPacketDefinition(MovePlayerPacket.class).getId());codec.tryEncode(codec.createHelper(),b,sent[0]);byte[] bytes=new byte[b.readableBytes()];b.readBytes(bytes);assertArrayEquals(Base64.getDecoder().decode(row.get("bytes").getAsString()),bytes,"native wire case "+count);}finally{b.release();}
            }
            var data=new TeleportData(target,count%2==0,row.get("mode").getAsInt());target.x+=1;assertEquals(row.get("copied"),gson.toJsonTree(bits(data.getPosition())));assertEquals(row.get("match").getAsBoolean(),data.matchesConfirmation(vec(row.getAsJsonArray("incoming")),row.get("handled").getAsBoolean()));
            var drain=new TeleportUtil(null);var entries=new ArrayList<TeleportData>();for(var ack:row.getAsJsonArray("acks")){var e=new TeleportData(Vec3.ZERO);if(ack.getAsBoolean())e.accept();entries.add(e);drain.getQueuedTeleports().add(e);}
            var processed=new ArrayList<Integer>();drain.drainAcceptedTeleports(e->processed.add(entries.indexOf(e)));assertEquals(row.get("drained"),gson.toJsonTree(processed));assertEquals(row.get("remaining").getAsInt(),drain.getQueuedTeleports().size());count++;
            }
        }
        assertEquals(4096,count);assertTrue(errors>0);
    }
}
