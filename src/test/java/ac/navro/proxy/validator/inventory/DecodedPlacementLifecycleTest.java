package ac.navro.proxy.validator.inventory;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.inventory.*;
import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.util.ItemUtil;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the placement commit boundary, not the unconnected live click dispatcher. */
class DecodedPlacementLifecycleTest {
    record Case(int protocol,NativeItemDecoder.Format format,Integer number,String name,int raw,String shell,int runtime,
                NativeItemPlacementProperties.Properties properties) {
        ItemData wire() {
            var tag=NbtMap.builder().putInt("mv_origin_id",number==null?255:number).putInt("mv_origin_meta",raw)
                    .putCompound("mv_origin_nbt",NbtMap.builder().putString("payload","preserved").build());
            if(number==null)tag.putString("mv_origin_namespace",name);
            return ItemData.builder().definition(new SimpleItemDefinition(shell,runtime,false)).count(2).damage(0).tag(tag.build()).build();
        }
    }
    static List<Case> cases() throws Exception {
        var selected=new LinkedHashMap<String,Case>();
        try(var in=new DataInputStream(new GZIPInputStream(DecodedPlacementLifecycleTest.class.getResourceAsStream("/native-placement-bridge.bin.gz")))) {
            assertEquals(0x4e504231,in.readInt());int count=in.readInt();
            for(int i=0;i<count;i++) {
                int protocol=in.readInt();var format=NativeItemDecoder.Format.values()[in.readInt()];Integer number=null;String name=null;
                if(in.readBoolean())number=in.readInt();else name=in.readUTF();int raw=in.readInt();String shell=in.readUTF();int runtime=in.readInt();
                in.readInt();in.readInt();var props=new NativeItemPlacementProperties.Properties(in.readBoolean(),in.readBoolean(),in.readInt(),in.readInt(),in.readInt());in.readBoolean();
                if(protocol==2193&&raw==65535&&props.canBePlaced()&&props.defaultBlockId()!=0&&selected.size()<48)
                    selected.putIfAbsent(number==null?name:String.valueOf(number),new Case(protocol,format,number,name,raw,shell,runtime,props));
            }
        }
        assertEquals(48,selected.size());return List.copyOf(selected.values());
    }
    @Test void actualDecodedPlacementBridgeCommitsNativeDefaultStatesBeforeConsumingTheHeldSlot() throws Exception {
        var clicked=Vector3i.from(8,64,8);var target=clicked.add(1,0,0);int exercised=0;
        for(var c:cases())for(boolean hashed:new boolean[]{false,true})for(GameType mode:List.of(GameType.SURVIVAL,GameType.CREATIVE)) {
            var s=new SessionState();s.setProtocolVersion(c.protocol);s.player().setProtocolVersion(c.protocol);
            var start=new StartGamePacket();start.setRuntimeEntityId(7);start.setBlockNetworkIdsHashed(hashed);s.observeBackend(start);
            s.setDetectionEnabled(true);s.player().acknowledgeGameType(mode);s.inventory().heldItemSlot=2;
            s.world().put(0,0,CompensatedWorld.CachedChunk.network(-64,320,24,s.world().airNetworkId()));
            s.world().updateLegacyBlock(clicked,0,1<<13);s.inventory().inventoryContainer.set(2,NativeItemDecoder.decode(c.protocol,c.wire(),c.format));
            var alias=s.inventory().inventoryContainer.getHeldItemCache();var original=ItemUtil.copy(alias.getData());
            var packet=new InventoryTransactionPacket();packet.setHotbarSlot(8);var callbacks=new ArrayList<Integer>();
            NetworkBlockState block=s.world().networkBlockState(s.world().getRawBlockAt(8,64,8,0));
            s.transactionValidator().handleBlockPlacement(s.world(),alias.getData(),clicked,5,block,packet,(data,placed,id)->{
                assertSame(packet,data);assertEquals(target,placed);assertEquals(c.properties.defaultBlockId(),id);
                assertEquals(s.world().networkIdForLegacy(c.properties.defaultLegacyFullId()),s.world().getRawBlockAt(9,64,8,0));
                assertEquals(2,alias.count());callbacks.add(id);
            });
            assertEquals(List.of(c.properties.defaultBlockId()),callbacks);
            assertEquals(1,s.world().bucketPredictionRevision());assertEquals(mode==GameType.CREATIVE?2:1,alias.count());
            assertEquals(original.getTag(),alias.getData().getTag());assertEquals(original.getDamage(),alias.getData().getDamage());
            assertSame(original.getDefinition(),alias.getData().getDefinition());exercised++;
        }assertEquals(192,exercised);
    }
}
