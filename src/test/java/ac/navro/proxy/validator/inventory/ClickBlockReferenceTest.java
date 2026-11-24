package ac.navro.proxy.validator.inventory;

import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.*;
import ac.navro.proxy.data.inventory.*;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.util.block.BlockUtil;
import com.google.gson.*;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class ClickBlockReferenceTest {
    static int i(JsonObject o,String key){return o.get(key).getAsInt();}static boolean b(JsonObject o,String key){return o.get(key).getAsBoolean();}static String s(JsonObject o,String key){return o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsString():null;}
    static Vector3i pos(JsonObject o,String key){if(!o.has(key)||o.get(key).isJsonNull())return null;var a=o.getAsJsonArray(key);return Vector3i.from(a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt());}
    static String point(Vector3i p){return p.getX()+","+p.getY()+","+p.getZ();}
    static ItemData item(JsonObject o,Map<ItemDefinition,JsonObject> definitions){if(o==null)return null;var d=new SimpleItemDefinition(s(o,"name"),i(o,"runtime"),false);definitions.put(d,o);return ItemData.builder().definition(d).damage(i(o,"damage")).count(i(o,"count")).tag(b(o,"tag")?NbtMap.builder().putString("payload","held").build():null).build();}
    static NbtMap tag(int kind){return switch(kind){case 0->NbtMap.EMPTY;case 1->NbtMap.builder().putCompound("book",NbtMap.EMPTY).build();case 2->NbtMap.builder().putString("vault_state","active").build();default->NbtMap.builder().putString("hasBook","true").build();};}
    static final class World extends CompensatedWorld {
        final JsonObject input;final List<String> events;boolean capture;Runnable switched=()->{};
        World(JsonObject in,List<String> events){input=in;this.events=events;}
        @Override public int networkIdForLegacy(int full){return full;}
        @Override public NetworkBlockState networkBlockState(int network){try{var ctor=NetworkBlockState.class.getDeclaredConstructor(int.class,int.class,NbtMap.class);ctor.setAccessible(true);return ctor.newInstance(network,network,NbtMap.builder().putString("name",network==i(input,"full")?s(input,"name"):"").putCompound("states",NbtMap.EMPTY).build());}catch(Exception e){throw new AssertionError(e);}}
        @Override public int getRawBlockAt(int x,int y,int z,int layer){if(capture)events.add("read:"+x+","+y+","+z+":"+layer);return super.getRawBlockAt(x,y,z,layer);}
        @Override public NbtMap getBlockEntityTag(int x,int y,int z){if(capture)events.add("nbt:"+x+","+y+","+z);return super.getBlockEntityTag(x,y,z);}
        @Override public void predictLocalBlock(Vector3i p,int layer,int full){events.add("predict:"+point(p)+":"+layer+":"+full);if(b(input,"predictFail"))throw new IllegalStateException("prediction failed");super.predictLocalBlock(p,layer,full);if(b(input,"switch"))switched.run();}
        @Override public void updateLegacyBlock(int x,int y,int z,int layer,int full){if(capture){events.add("direct:"+x+","+y+","+z+":"+layer+":"+full);if(b(input,"directFail"))throw new IllegalStateException("direct failed");}super.updateLegacyBlock(x,y,z,layer,full);}
    }
    static void load(World w,Vector3i p){if(w.getChunk(p.getX()>>4,p.getZ()>>4)==null)w.put(p.getX()>>4,p.getZ()>>4,CompensatedWorld.CachedChunk.network(-64,320,24,0));}
    record Definition(int runtimeId) implements BlockDefinition {public int getRuntimeId(){return runtimeId;}}
    @Test void currentOriginalFullDispatcherMatchesAllStagesLookupOrderCorrectionsWorldAndInventory()throws Exception {
        int predictions=0,rotations=0,corrections=0,errors=0;
        try(var in=new BufferedReader(new InputStreamReader(new GZIPInputStream(getClass().getResourceAsStream("/navro-click-block.jsonl.gz")),StandardCharsets.UTF_8))){var header=JsonParser.parseString(in.readLine()).getAsJsonObject();assertEquals("NCB1",s(header,"magic"));assertEquals(8192,i(header,"cases"));
            for(int c=0;c<8192;c++){
                var o=JsonParser.parseString(in.readLine()).getAsJsonObject();var player=new WirePlayerState();player.acknowledgeGameType(i(o,"game")==7?null:GameType.from(i(o,"game")));player.tickSinceBlockResync=13;var inventory=new CompensatedInventory(player);inventory.heldItemSlot=i(o,"slot");var defs=new IdentityHashMap<ItemDefinition,JsonObject>();var aliases=new IdentityHashMap<ItemCache,Integer>();
                for(int slot=0;slot<9;slot++){inventory.inventoryContainer.set(slot,item(o.getAsJsonArray("initial").get(slot).getAsJsonObject(),defs));aliases.put(inventory.inventoryContainer.get(slot),slot);}var alias=inventory.inventoryContainer.getHeldItemCache();var held=alias.getData();var info=o.getAsJsonObject("held");
                var packet=new InventoryTransactionPacket();packet.setBlockPosition(pos(o,"pos"));packet.setHotbarSlot(i(o,"hotbar"));packet.setItemInHand(item(o.getAsJsonObject("claim"),defs));if(o.has("cursorY")&&!o.get("cursorY").isJsonNull())packet.setClickPosition(Vector3f.from(0,Float.intBitsToFloat(i(o,"cursorY")),0));
                var buckets=new HashMap<Integer,ItemData>();for(var entry:o.getAsJsonArray("buckets")){var data=entry.getAsJsonObject();buckets.put(i(data,"damage"),item(data,defs));}
                var events=new ArrayList<String>();var w=new World(o,events);var clicked=pos(o,"clicked");var target=pos(o,"target");var lower=pos(o,"lower");if(b(o,"loaded")){load(w,clicked);load(w,target);load(w,lower);}w.updateBlock(clicked.getX(),clicked.getY(),clicked.getZ(),0,i(o,"full"));w.updateBlock(clicked.getX(),clicked.getY(),clicked.getZ(),1,i(o,"layerOne"));w.updateBlock(target.getX(),target.getY(),target.getZ(),0,i(o,"targetFull"));w.updateBlock(lower.getX(),lower.getY(),lower.getZ(),0,i(o,"lowerFull"));w.updateBlockEntityTag(clicked.getX(),clicked.getY(),clicked.getZ(),tag(i(o,"tagKind")));
                ItemTransactionValidator.PlacementObserver rotation=(data,p,id)->{assertSame(packet,data);events.add("rotation:"+point(p)+":"+id+":"+inventory.inventoryContainer.getHeldItemCache().count());if(b(o,"rotateSlot")){inventory.heldItemSlot=(inventory.heldItemSlot+1)%9;events.add("slot:"+inventory.heldItemSlot);}if(b(o,"rotationFail"))throw new IllegalStateException("rotation failed");};
                java.util.function.BiFunction<Boolean,Boolean,ItemTransactionValidator.PlacementChecks> checks=(present,rot)->!present?null:new ItemTransactionValidator.PlacementChecks(){public ItemTransactionValidator.AirLiquidPlaceCheck airLiquidPlace(){events.add("check:AirLiquidPlace");return b(o,"air")?(data,h,clickedState)->{assertSame(packet,data);assertSame(held,h);return false;}:null;}public ItemTransactionValidator.PlacementObserver rotationPlace(){events.add("check:RotationPlace");return rot?rotation:null;}};
                var currentChecks=new AtomicReference<>(checks.apply(b(o,"holder"),b(o,"rotation")));w.switched=()->currentChecks.set(checks.apply(b(o,"postHolder"),b(o,"postRotation")));var sent=new AtomicInteger();
                var context=new ItemTransactionValidator.ClickBlockContext(){
                    public boolean validate(ItemData p,ItemData c){return ItemTransactionValidator.validate(p,c);}public CompensatedWorld world(){return w;}public Integer resolvedFace(InventoryTransactionPacket data){return i(o,"face")<0?null:i(o,"face");}public int clientInteractPrediction(InventoryTransactionPacket data){return i(o,"prediction");}public boolean sneaking(){return b(o,"sneak");}public ItemTransactionValidator.PlacementChecks checks(){return currentChecks.get();}public ItemTransactionValidator.ActivationObserver trapdoorSampler(){return (p,b,a,phase,trace)->0;}
                    public int nativeItemId(ItemData data){assertSame(held,data);return i(info,"id");}public int nativeItemDamage(ItemData data){assertSame(held,data);return i(info,"damage");}public int nativeBlockId(ItemData data){assertSame(held,data);return i(info,"block");}public boolean nativeIsNull(ItemData data){assertSame(held,data);return b(info,"empty");}public boolean nativeCanBePlaced(ItemData data){assertSame(held,data);return b(info,"canPlace");}public NativeItemPlacementProperties.Properties placementProperties(ItemData data){assertSame(held,data);return new NativeItemPlacementProperties.Properties(b(info,"canPlace"),b(info,"mapped"),i(info,"block"),i(info,"defaultId"),i(info,"defaultFull"));}public ItemData createNativeBucket(int damage){return buckets.get(damage);}
                    public void restoreCorrectBlock(Vector3i p){if(!b(o,"authority"))return;var ids=o.getAsJsonArray("authorityIds");BlockUtil.restoreCorrectBlock(update->{int flags=0;for(var flag:update.getFlags())flags|=1<<flag.ordinal();events.add("send:"+point(update.getBlockPosition())+":"+update.getDataLayer()+":"+update.getDefinition().getRuntimeId()+":"+flags);if(sent.incrementAndGet()==i(o,"sendFail"))throw new IllegalStateException("send failed");},p,new Definition(ids.get(0).getAsInt()),new Definition(ids.get(1).getAsInt()));}
                };
                events.clear();w.capture=true;boolean result=false;String error="";try{result=new ItemTransactionValidator(player,inventory).handleClickBlock(packet,context);}catch(RuntimeException failed){error=failed.getClass().getSimpleName();}w.capture=false;String label="native click block case="+c;assertEquals(b(o,"result"),result,label);assertEquals(s(o,"error"),error,label);var expected=new ArrayList<String>();for(var e:o.getAsJsonArray("events"))expected.add(e.getAsString());assertEquals(expected,events,label+" sequence");assertEquals(i(o,"heldSlotAfter"),inventory.heldItemSlot,label);assertEquals(i(o,"resync"),player.tickSinceBlockResync,label);assertEquals(i(o,"aliasCount"),alias.count(),label);
                for(int slot=0;slot<9;slot++){var cache=inventory.inventoryContainer.get(slot);assertEquals(o.getAsJsonArray("cacheIds").get(slot).getAsInt(),aliases.getOrDefault(cache,-1),label+" cache alias");var actual=cache.getData();var expectedItem=o.getAsJsonArray("after").get(slot).getAsJsonObject();var nativeInfo=defs.get(actual.getDefinition());int id=actual==ItemData.AIR?0:i(nativeInfo,"id");assertEquals(i(expectedItem,"id"),id,label+" item ID");assertEquals(i(expectedItem,"damage"),actual.getDamage(),label);assertEquals(i(expectedItem,"count"),actual.getCount(),label);assertEquals(b(expectedItem,"tag"),actual.getTag()!=null,label);if(cache!=ItemCache.AIR){assertEquals(s(expectedItem,"name"),actual.getDefinition().getIdentifier(),label);assertEquals(i(expectedItem,"runtime"),actual.getDefinition().getRuntimeId(),label);}else assertSame(ItemData.AIR,actual,label+" wire representation of native AIR_ITEM sentinel");}
                int index=0;for(var p:List.of(clicked,target,lower))for(int layer=0;layer<2;layer++)assertEquals(o.getAsJsonArray("blocks").get(index++).getAsInt(),w.getRawBlockAt(p.getX(),p.getY(),p.getZ(),layer),label+" world");assertEquals(o.get("revision").getAsLong(),w.bucketPredictionRevision(),label);predictions+=events.stream().filter(e->e.startsWith("predict:")).count();rotations+=events.stream().filter(e->e.startsWith("rotation:")).count();corrections+=sent.get();if(!error.isEmpty())errors++;
            }
            assertNull(in.readLine());
        }
        assertTrue(predictions>100);assertTrue(rotations>20);assertTrue(corrections>200);assertTrue(errors>30);
    }
}
