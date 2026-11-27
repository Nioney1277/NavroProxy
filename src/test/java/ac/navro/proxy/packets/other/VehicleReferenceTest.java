package ac.navro.proxy.packets.other;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.SupportedCodecs;
import ac.navro.proxy.teleport.data.TeleportData;
import com.google.gson.*;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.entity.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class VehicleReferenceTest {
    static SessionState session() {
        var s=new SessionState();s.setProtocolVersion(2193);s.player().setProtocolVersion(2193);
        var start=new StartGamePacket();start.setRuntimeEntityId(7);start.setUniqueEntityId(70);start.setPlayerPosition(Vector3f.ZERO);s.observeBackend(start);
        s.setDetectionEnabled(true);s.authInputPackets();
        spawn(s,42,420,true);spawn(s,43,430,false);spawn(s,44,440,false);return s;
    }
    static void spawn(SessionState s,long runtime,long unique,boolean boat) {
        var p=new AddEntityPacket();p.setRuntimeEntityId(runtime);p.setUniqueEntityId(unique);p.setEntityType(boat?90:12);
        p.setIdentifier(boat?"minecraft:boat":"minecraft:pig");p.setPosition(Vector3f.ZERO);p.setMotion(Vector3f.ZERO);
        p.getMetadata().put(EntityDataTypes.WIDTH,1F);p.getMetadata().put(EntityDataTypes.HEIGHT,1F);s.observeBackend(p);
    }
    static SetEntityLinkPacket link(long vehicle,long rider,int type) {
        var p=new SetEntityLinkPacket();p.setEntityLink(new EntityLinkData(vehicle,rider,EntityLinkData.Type.values()[type],false,false,0));return p;
    }
    static void queued(SessionState s,long marker,BedrockPacket p) {s.player().getLatencyUtil().queue(marker,true);s.observeBackend(p);}
    static BufferedReader reference() throws Exception {
        return new BufferedReader(new InputStreamReader(new GZIPInputStream(VehicleReferenceTest.class.getResourceAsStream("/navro-vehicle-links.jsonl.gz")),StandardCharsets.UTF_8));
    }
    @Test void linkEncodingUtilityMatchesNativeBytesAndDecodesVehicleBeforeRider() throws Exception {
        try(var in=reference()) {
            var cases=JsonParser.parseString(in.readLine()).getAsJsonObject().getAsJsonArray("wire");int count=0;
            for(var value:cases) {
                var row=value.getAsJsonObject();var codec=SupportedCodecs.forProtocol(row.get("protocol").getAsInt());
                var packet=link(row.get("vehicle").getAsLong(),row.get("rider").getAsLong(),row.get("type").getAsInt());
                packet.setEntityLink(new EntityLinkData(packet.getEntityLink().getFrom(),packet.getEntityLink().getTo(),packet.getEntityLink().getType(),
                        row.get("immediate").getAsBoolean(),row.get("initiated").getAsBoolean(),Float.intBitsToFloat(row.get("angularBits").getAsInt())));
                byte[] expected=Base64.getDecoder().decode(row.get("bytes").getAsString());var encoded=Unpooled.buffer();
                try {
                    VarInts.writeUnsignedInt(encoded,codec.getPacketDefinition(SetEntityLinkPacket.class).getId());codec.tryEncode(codec.createHelper(),encoded,packet);
                    byte[] actual=new byte[encoded.readableBytes()];encoded.readBytes(actual);assertArrayEquals(expected,actual,"native link case "+count);
                } finally {encoded.release();}
                var nativeBytes=Unpooled.wrappedBuffer(expected);
                try {
                    int id=VarInts.readUnsignedInt(nativeBytes);var decoded=(SetEntityLinkPacket)codec.tryDecode(codec.createHelper(),nativeBytes,id);
                    assertEquals(packet.getEntityLink(),decoded.getEntityLink());assertFalse(nativeBytes.isReadable());
                } finally {nativeBytes.release();}
                count++;
            }assertEquals(32,count);
        }
    }
    @Test void entityIndexAndPendingMarkersRemainStableAcrossLegacyTrace() throws Exception {
        var gson=new GsonBuilder().serializeNulls().create();int count=0;var operations=new HashSet<Integer>();
        try(var in=reference()) {
            var header=JsonParser.parseString(in.readLine()).getAsJsonObject();assertEquals("NVL1",header.get("magic").getAsString());
            for(int sn=0;sn<header.get("sessions").getAsInt();sn++) {
                var s=session();SetEntityLinkPacket lastLink=null;
                for(int step=0;step<header.get("steps").getAsInt();step++) {
                    var row=JsonParser.parseString(in.readLine()).getAsJsonObject();int op=row.get("op").getAsInt();
                    switch(op) {
                        case 0 -> spawn(s,row.get("runtime").getAsLong(),row.get("unique").getAsLong(),row.get("boat").getAsBoolean());
                        case 1 -> {lastLink=link(row.get("vehicle").getAsLong(),row.get("rider").getAsLong(),row.get("type").getAsInt());queued(s,row.get("marker").getAsLong(),lastLink);}
                        case 2 -> assertEquals(row.get("accepted").getAsBoolean(),s.player().getLatencyUtil().onResponse(row.get("marker").getAsLong()));
                        case 3 -> {var p=new InteractPacket();p.setRuntimeEntityId(row.get("target").getAsLong());p.setAction(InteractPacket.Action.values()[row.get("action").getAsInt()]);s.observeClient(p);}
                        case 4 -> {var p=new RemoveEntityPacket();p.setUniqueEntityId(row.get("unique").getAsLong());queued(s,row.get("marker").getAsLong(),p);}
                        case 5 -> s.teleports().getQueuedTeleports().add(new TeleportData(Vec3.ZERO));
                        case 6 -> lastLink.setEntityLink(link(row.get("vehicle").getAsLong(),row.get("rider").getAsLong(),row.get("type").getAsInt()).getEntityLink());
                        default -> throw new AssertionError(op);
                    }
                    var actual=new LinkedHashMap<String,Object>();var vehicle=s.player().vehicleData;
                    actual.put("vehicle",vehicle==null?null:vehicle.vehicleRuntimeId);actual.put("control",vehicle!=null&&vehicle.canWeControlThisVehicle);
                    actual.put("offsetBits",Float.floatToRawIntBits(s.playerYOffset()));actual.put("teleports",s.teleports().getQueuedTeleports().size());
                    var flags=new TreeMap<Long,Boolean>();s.entities().forEach((id,e)->flags.put(id,e.inVehicle()));actual.put("riders",flags);
                    var index=new TreeMap<Long,Long>();for(var v:header.getAsJsonArray("uniqueIds")){long id=v.getAsLong();Long runtime=s.entityRuntimeIdByUniqueId(id);if(runtime!=null)index.put(id,runtime);}actual.put("index",index);
                    actual.put("pending",s.player().getLatencyUtil().sentQueue().size());
                    for (String field : List.of("index", "pending"))
                        assertEquals(row.getAsJsonObject("state").get(field),gson.toJsonTree(actual).getAsJsonObject().get(field),
                                "session="+sn+" step="+step+" op="+op+" field="+field);
                    count++;operations.add(op);
                }
            }assertNull(in.readLine());
        }assertEquals(1024,count);assertEquals(Set.of(0,1,2,3,4,5,6),operations);
    }
}
