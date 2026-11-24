package ac.navro.proxy.data.inventory;

import ac.navro.proxy.util.ItemUtil;
import ac.navro.proxy.util.block.BlockReachUtil;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class PlacementDecoderReferenceTest {
    @Test void ordinaryRuntimeBindingsAndEachWireFormatRetainNativePlacementSemantics() throws Exception {
        int rows=0,errors=0;var protocols=new HashSet<Integer>();
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/native-placement-wire.bin.gz")))) {
            assertEquals(0x4e505731,in.readInt());int count=in.readInt();assertEquals(1989,count);
            for(int i=0;i<count;i++) {
                int protocol=in.readInt();var format=NativeItemDecoder.Format.values()[in.readInt()];String name=in.readUTF();int runtime=in.readInt(),raw=in.readInt();
                var wire=ItemData.builder().definition(new SimpleItemDefinition(name,runtime,false)).count(2).damage(raw).netId(42).usingNetId(true)
                        .tag(NbtMap.builder().putString("payload","ordinary").build()).canPlace(new String[]{"minecraft:stone"}).canBreak(new String[]{"minecraft:dirt"}).build();
                protocols.add(protocol);rows++;
                if(!in.readBoolean()) {
                    String type=in.readUTF();RuntimeException error=assertThrows(RuntimeException.class,()->NativeItemDecoder.placement(protocol,wire,format));
                    assertEquals(type,error.getClass().getName());errors++;continue;
                }
                int id=in.readInt(),damage=in.readInt();var expected=new NativeItemPlacementProperties.Properties(in.readBoolean(),in.readBoolean(),in.readInt(),in.readInt(),in.readInt());boolean placeable=in.readBoolean();
                var decoded=NativeItemDecoder.decode(protocol,wire,format);String label="ordinary case="+i+" protocol="+protocol+" name="+name+" raw="+raw;
                assertEquals(id,NativePredictionItems.resolve(protocol,decoded).nativeId(),label);assertEquals(damage,decoded.getDamage(),label);
                assertEquals(expected,NativeItemDecoder.placement(protocol,wire,format),label);assertEquals(placeable,BlockReachUtil.isPlaceable(protocol,decoded),label);
            }assertEquals(-1,in.read());
        }assertEquals(1989,rows);assertEquals(17,protocols.size());assertTrue(errors>0,"Native out-of-range decoder rejection cases must execute");
    }
    @Test void nativeWireDecodedPlacementViewsKeepTheOriginalConstructorAndMetadataAcrossEveryFactory() throws Exception {
        int count=0,placeable=0;var factories=new HashSet<String>();var formats=EnumSet.noneOf(NativeItemDecoder.Format.class);
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/native-placement-bridge.bin.gz")))) {
            assertEquals(0x4e504231,in.readInt());int cases=in.readInt();assertEquals(70329,cases);
            for(int i=0;i<cases;i++) {
                int protocol=in.readInt();var format=NativeItemDecoder.Format.values()[in.readInt()];
                Integer number=null;String name=null;if(in.readBoolean())number=in.readInt();else name=in.readUTF();int raw=in.readInt();
                String shell=in.readUTF();int runtime=in.readInt(),id=in.readInt(),damage=in.readInt();
                var expected=new NativeItemPlacementProperties.Properties(in.readBoolean(),in.readBoolean(),in.readInt(),in.readInt(),in.readInt());
                boolean expectedPlaceable=in.readBoolean();var tag=NbtMap.builder().putInt("mv_origin_id",number==null?255:number).putInt("mv_origin_meta",raw).putCompound("mv_origin_nbt",NbtMap.EMPTY);
                if(number==null)tag.putString("mv_origin_namespace",name);
                var wire=ItemData.builder().definition(new SimpleItemDefinition(shell,runtime,false)).count(2).damage(0).netId(42).usingNetId(true).tag(tag.build())
                        .canPlace(new String[]{"minecraft:stone"}).canBreak(new String[]{"minecraft:dirt"}).build();
                var decoded=NativeItemDecoder.decode(protocol,wire,format);String label="case="+i+" factory="+(number==null?name:number)+" damage="+raw;
                assertEquals(id,NativePredictionItems.resolve(protocol,decoded).nativeId(),label);assertEquals(damage,decoded.getDamage(),label);
                assertEquals(expected,NativeItemDecoder.placement(protocol,decoded),label);
                assertEquals(expected,NativeItemDecoder.placement(protocol,wire,format),label);
                assertEquals(expected,NativeItemDecoder.placement(protocol,ItemUtil.copy(decoded)),label);
                assertEquals(expectedPlaceable,BlockReachUtil.isPlaceable(protocol,decoded),label);
                assertEquals(0,wire.getDamage(),label);assertTrue(wire.getTag().containsKey("mv_origin_id"),label);
                factories.add(number==null?"named:"+name:"numeric:"+number);formats.add(format);if(expectedPlaceable)placeable++;count++;
            }assertEquals(-1,in.read());
        }
        assertEquals(70329,count);assertEquals(44187,placeable);assertEquals(3349,factories.size());assertEquals(3,formats.size());
    }

    @Test void placementBridgeRejectsUnexportedOrCrossProtocolConstructionWithoutGuessing() {
        var wire=ItemData.builder().definition(new SimpleItemDefinition("minecraft:stone",1,false)).count(1).build();
        var decoded=NativeItemDecoder.decode(2193,wire);
        assertThrows(IllegalArgumentException.class,()->NativeItemDecoder.placement(2168,decoded));
        var custom=wire.toBuilder().definition(new SimpleItemDefinition("custom:not_exported",12345,true)).build();
        assertThrows(IllegalArgumentException.class,()->NativeItemDecoder.placement(2193,custom));
    }
}
