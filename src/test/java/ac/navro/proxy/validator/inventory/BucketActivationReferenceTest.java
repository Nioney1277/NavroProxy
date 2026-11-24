package ac.navro.proxy.validator.inventory;

import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.compensated.cache.container.impl.PlayerContainerCache;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.*;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class BucketActivationReferenceTest {
    static String point(Vector3i p){return p.getX()+","+p.getY()+","+p.getZ();}
    static Vector3i pos(DataInputStream in)throws IOException{return Vector3i.from(in.readInt(),in.readInt(),in.readInt());}
    static ItemData stack(int id,int damage,int count,boolean tagged){return ItemData.builder().definition(new SimpleItemDefinition("native:item",id,false)).damage(damage).count(count).tag(tagged?NbtMap.builder().putString("payload","held").build():null).build();}
    static final class World extends CompensatedWorld {
        final List<String> events;final boolean predictionFails;final int directFailure;boolean capture;
        World(List<String> events,boolean fail,int direct){this.events=events;predictionFails=fail;directFailure=direct;}
        @Override public int networkIdForLegacy(int full){return full;}
        @Override public NetworkBlockState networkBlockState(int network){return NetworkBlockState.legacy(network);}
        @Override public int getRawBlockAt(int x,int y,int z,int layer){if(capture)events.add("read:"+x+","+y+","+z+":"+layer);return super.getRawBlockAt(x,y,z,layer);}
        @Override public void predictLocalBlock(Vector3i p,int layer,int full){events.add("predict:"+point(p)+":"+layer+":"+full);if(predictionFails)throw new IllegalStateException("prediction failed");super.predictLocalBlock(p,layer,full);}
        @Override public void updateLegacyBlock(int x,int y,int z,int layer,int full){
            if(capture){events.add("direct:"+x+","+y+","+z+":"+layer+":"+full);if(directFailure==1)throw new IllegalStateException("direct write failed before");}
            super.updateLegacyBlock(x,y,z,layer,full);if(capture&&directFailure==2)throw new IllegalStateException("direct write failed after");
        }
    }
    static void load(World w,Vector3i p){if(w.getChunk(p.getX()>>4,p.getZ()>>4)==null)w.put(p.getX()>>4,p.getZ()>>4,CompensatedWorld.CachedChunk.network(-64,320,24,0));}
    @Test void originalBucketAndActivationBehaviorIsPreservedExceptCreativeBucketInventoryWrites()throws Exception {
        int bucketsChanged=0,worldWrites=0,errors=0;
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/navro-bucket-activation.bin.gz")))) {
            assertEquals(0x4e424131,in.readInt());int cases=in.readInt();assertEquals(8192,cases);
            for(int i=0;i<cases;i++) {
                int mode=in.readInt(),game=in.readInt(),slot=in.readInt(),id=in.readInt(),damage=in.readInt(),count=in.readInt(),heldBlockId=in.readInt();boolean nullHeld=in.readBoolean();int block=in.readInt(),meta=in.readInt(),nativeFull=in.readInt();var clicked=pos(in);var target=pos(in);int layerFull=in.readInt(),lowerFull=in.readInt();
                boolean loaded=in.readBoolean(),predictionFails=in.readBoolean(),setFails=in.readBoolean();int directFailure=in.readInt();boolean nullPos=in.readBoolean(),nullBlock=in.readBoolean();
                var events=new ArrayList<String>();var player=new WirePlayerState();player.acknowledgeGameType(GameType.from(game));var inventory=new CompensatedInventory(player);inventory.heldItemSlot=slot;
                var cache=new PlayerContainerCache(inventory){@Override public void set(int s,ItemData data){events.add("set:"+s+":"+data.getDefinition().getRuntimeId()+":"+data.getDamage()+":"+data.getCount()+":"+(data.getTag()!=null));if(setFails)throw new IllegalStateException("inventory write failed");super.set(s,data);}};
                var raw=stack(id,damage,count,true);cache.set(slot,raw,false);Field field=CompensatedInventory.class.getDeclaredField("inventoryContainer");field.setAccessible(true);field.set(inventory,cache);var alias=cache.getHeldItemCache();var held=alias.getData();
                var w=new World(events,predictionFails,directFailure);var lower=Vector3i.from(clicked.getX(),clicked.getY()-1,clicked.getZ());
                if(loaded){load(w,clicked);load(w,target);load(w,lower);}w.updateBlock(clicked.getX(),clicked.getY(),clicked.getZ(),0,nativeFull);w.updateBlock(clicked.getX(),clicked.getY(),clicked.getZ(),1,layerFull);w.updateBlock(lower.getX(),lower.getY(),lower.getZ(),0,lowerFull);
                var context=new ItemTransactionValidator.BucketContext(){public int nativeItemId(ItemData item){assertSame(held,item);return id;}public int nativeItemDamage(ItemData item){assertSame(held,item);return damage;}public ItemData createNativeBucket(int bucketDamage){return stack(325,bucketDamage,1,false);}};
                events.clear();w.capture=true;boolean result=true;String error="";
                try{var validator=new ItemTransactionValidator(player,inventory);if(mode==0)result=validator.handleBucketUse(w,held,clicked,target,NetworkBlockState.legacy(BlockStateEncoding.fullId(block,meta)),context);else if(mode==1)validator.predictActivatedBlock(w,nullPos?null:clicked,nullBlock?null:NetworkBlockState.legacy(BlockStateEncoding.fullId(block,meta)),(p,b,a,phase,trace)->0);else result=validator.isScaffoldingPlacement(w,nullHeld?null:heldBlockId,nullBlock?null:NetworkBlockState.legacy(BlockStateEncoding.fullId(block,meta)),nullPos?null:target);}
                catch(RuntimeException e){error=e.getClass().getSimpleName()+":"+e.getMessage();}w.capture=false;
                String label="native bucket/activation case="+i;boolean creativeBucket=mode==0&&player.gameType()==GameType.CREATIVE;
                assertEquals(in.readBoolean(),result,label);String expectedError=in.readUTF();
                // The retained original reference consumed creative buckets. Only that inventory behavior is intentionally corrected.
                if(creativeBucket&&expectedError.equals("IllegalStateException:inventory write failed"))expectedError="";
                assertEquals(expectedError,error,label);int size=in.readInt();var expected=new ArrayList<String>();
                for(int e=0;e<size;e++)expected.add(in.readUTF());
                if(creativeBucket)expected.removeIf(event->event.startsWith("set:"));
                assertEquals(expected,events,label+" events");
                var after=cache.getHeldItemData();int expectedId=in.readInt(),expectedDamage=in.readInt(),expectedCount=in.readInt();
                boolean expectedTag=in.readBoolean(),expectedAlias=in.readBoolean();int expectedOldCount=in.readInt();
                if(creativeBucket) {
                    assertSame(held,after,label);assertSame(alias,cache.getHeldItemCache(),label);
                } else {
                    assertEquals(expectedId,after.getDefinition().getRuntimeId(),label);assertEquals(expectedDamage,after.getDamage(),label);
                    assertEquals(expectedCount,after.getCount(),label);assertEquals(expectedTag,after.getTag()!=null,label);
                    assertEquals(expectedAlias,cache.getHeldItemCache()==alias,label);
                }
                assertEquals(expectedOldCount,alias.count(),label);
                for(var p:List.of(clicked,target,lower))for(int layer=0;layer<2;layer++)assertEquals(in.readInt(),w.getRawBlockAt(p.getX(),p.getY(),p.getZ(),layer),label+" world");assertEquals(in.readLong(),w.bucketPredictionRevision(),label+" revision");
                if(after!=held)bucketsChanged++;worldWrites+=events.stream().filter(e->e.startsWith("predict:")||e.startsWith("direct:")).count();if(!error.isEmpty())errors++;
            }
            assertEquals(-1,in.read());
        }
        assertTrue(bucketsChanged>500);assertTrue(worldWrites>1000);assertTrue(errors>50);
    }
}
