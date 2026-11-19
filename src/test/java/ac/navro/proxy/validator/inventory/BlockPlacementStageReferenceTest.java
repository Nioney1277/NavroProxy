package ac.navro.proxy.validator.inventory;

import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.inventory.*;
import ac.navro.proxy.data.block.*;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.util.block.BlockPlacementPhysics;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class BlockPlacementStageReferenceTest {
    private static Vector3i pos(DataInputStream in)throws Exception{return Vector3i.from(in.readInt(),in.readInt(),in.readInt());}
    private static String point(Vector3i p){return p.getX()+","+p.getY()+","+p.getZ();}
    private static final class World extends CompensatedWorld {
        final List<String> events;final boolean failure;
        World(List<String> events,boolean failure){this.events=events;this.failure=failure;}
        @Override public int networkIdForLegacy(int full){return full;}
        @Override public void predictLocalBlock(Vector3i p,int layer,int full){
            events.add("predict:"+point(p)+":"+layer+":"+full);
            if(failure)throw new IllegalStateException("placement prediction failed");super.predictLocalBlock(p,layer,full);
        }
    }
    private static void load(World w,Vector3i p){w.put(p.getX()>>4,p.getZ()>>4,CompensatedWorld.CachedChunk.network(-64,320,24,0));}
    @Test void originalPlacementStageMatchesCacheRevisionsCallbacksInventoryIdentityCountsAndFailureOrder()throws Exception {
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/navro-item-placement-stage.bin.gz")))) {
            assertEquals(0x4E535031,in.readInt());int cases=in.readInt();assertEquals(1680,cases);
            for(int i=0;i<cases;i++) {
                boolean numeric=in.readBoolean();int id=numeric?in.readInt():0;String name=numeric?null:in.readUTF();int damage=in.readInt(),count=in.readInt(),mode=in.readInt(),slot=in.readInt();
                var clicked=pos(in);int face=in.readInt(),block=in.readInt(),metadata=in.readInt();boolean loaded=in.readBoolean(),observe=in.readBoolean(),predictionFails=in.readBoolean(),callbackFails=in.readBoolean();
                var held=numeric?NativeItemPlacementProperties.forNumeric(id,damage):NativeItemPlacementProperties.forIdentifier(name,damage);
                var player=new WirePlayerState();player.acknowledgeGameType(mode<7?GameType.from(mode):null);var inventory=new CompensatedInventory(player);inventory.heldItemSlot=slot;
                var raw=ItemData.builder().definition(new SimpleItemDefinition("native:held",1,false)).damage(damage).count(count).build();inventory.inventoryContainer.set(slot,raw);
                var alias=inventory.inventoryContainer.getHeldItemCache();var events=new ArrayList<String>();var w=new World(events,predictionFails);
                var clickedState=NetworkBlockState.legacy(BlockStateEncoding.fullId(block,metadata));var target=BlockPlacementPhysics.target(clicked,face,clickedState);
                if(loaded){load(w,clicked);if((target.getX()>>4)!=(clicked.getX()>>4)||(target.getZ()>>4)!=(clicked.getZ()>>4))load(w,target);}
                w.updateLegacyBlock(clicked,0,BlockStateEncoding.fullId(block,metadata));
                var data=new InventoryTransactionPacket();data.setHotbarSlot((slot+1)%9);
                ItemTransactionValidator.PlacementObserver observer=observe?(packet,p,b)->{
                    assertSame(data,packet);events.add("callback:"+point(p)+":"+b+":"+inventory.inventoryContainer.getHeldItemCache().count());
                    if(callbackFails)throw new IllegalStateException("rotation callback failed");
                }:null;
                String error="";
                try{new ItemTransactionValidator(player,inventory).handleBlockPlacement(w,held,clicked,face,clickedState,data,observer);}
                catch(RuntimeException failed){error=failed.getClass().getSimpleName()+":"+failed.getMessage();}
                String label="native placement case="+i;
                assertEquals(in.readUTF(),error,label);int size=in.readInt();var expected=new ArrayList<String>();for(int j=0;j<size;j++)expected.add(in.readUTF());
                assertEquals(expected,events,label+" calls");assertEquals(in.readInt(),alias.count(),label+" held cache alias");
                assertEquals(in.readBoolean(),inventory.inventoryContainer.getHeldItemCache()==ItemCache.AIR,label+" air identity");
                assertEquals(in.readInt(),inventory.inventoryContainer.getHeldItemCache().count(),label+" selected count");
                assertEquals(in.readInt(),raw.getCount(),label+" original item unchanged");
                assertEquals(in.readInt(),w.getRawBlockAt(clicked.getX(),clicked.getY(),clicked.getZ(),0),label+" clicked cache");
                assertEquals(in.readInt(),w.getRawBlockAt(target.getX(),target.getY(),target.getZ(),0),label+" target cache");
                assertEquals(in.readLong(),w.bucketPredictionRevision(),label+" revision");
            }
            assertEquals(-1,in.read());
        }
    }
}
