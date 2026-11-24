package ac.navro.proxy.validator.inventory;

import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.data.*;
import ac.navro.proxy.state.WirePlayerState;
import com.google.gson.*;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class AirReleaseReferenceTest {
    static int i(JsonObject o,String key){return o.get(key).getAsInt();}static boolean b(JsonObject o,String key){return o.get(key).getAsBoolean();}
    static ItemData data(ItemUseTestModel.Item item){var p=item.profile;String name=p.get("name").isJsonNull()?null:p.get("name").getAsString();return ItemData.builder().definition(new SimpleItemDefinition(name,p.get("runtime").getAsInt(),false)).count(item.count).build();}
    @Test void originalClickAirReleaseAndBoostMatchRealTrackerStatesTypesCandidatePriorityAndFailures()throws Exception {
        var gson=new GsonBuilder().serializeNulls().create();int errors=0,boosts=0,riptides=0;
        try(var in=new BufferedReader(new InputStreamReader(new GZIPInputStream(getClass().getResourceAsStream("/navro-air-release.jsonl.gz")),StandardCharsets.UTF_8))){var h=JsonParser.parseString(in.readLine()).getAsJsonObject();assertEquals("NAR1",h.get("magic").getAsString());assertEquals(6144,i(h,"cases"));
            for(int n=0;n<6144;n++){
                var r=JsonParser.parseString(in.readLine()).getAsJsonObject();var model=new ItemUseTestModel(h.getAsJsonArray("profiles"),h.getAsJsonObject("air"));model.tick=i(r,"tick");model.serverUsing=b(r,"serverUsing");var tracker=new ItemUseTracker<>(model);var seed=model.inputs.get(i(r,"seed"));tracker.use(seed,model.nativeItemId(seed),true);tracker.setUseButtonLatched(b(r,"latch"));if(b(r,"wait")){tracker.onMovementMetadataAcknowledged(false);tracker.use(seed,model.nativeItemId(seed),false);}model.setFlag(-1,b(r,"using"));model.setFlag(4,b(r,"action"));model.setFlag(32,b(r,"compGlide"));model.since=i(r,"since");
                var player=new WirePlayerState();var boostField=WirePlayerState.class.getDeclaredField("glideBoostTicks");boostField.setAccessible(true);boostField.setInt(player,i(r,"boost"));player.nukkitGlideBoostPending=b(r,"pending");var inventory=new CompensatedInventory(player);inventory.heldItemSlot=i(r,"slot");
                var held=new ItemUseTestModel.Item(i(r,"held"),model.inputs.get(i(r,"held")).profile,i(r,"count"));var chest=new ItemUseTestModel.Item(i(r,"chest"),model.inputs.get(i(r,"chest")).profile,i(r,"chestCount"));inventory.inventoryContainer.set(inventory.heldItemSlot,data(held));inventory.armorContainer.set(1,data(chest));var heldData=inventory.inventoryContainer.getHeldItemData();var chestData=inventory.armorContainer.get(1).getData();var packet=new InventoryTransactionPacket();int claim=i(r,"claim");packet.setItemInHand(claim<0?null:data(claim==held.index?held:model.inputs.get(claim)));packet.setActionType(i(r,"releaseAction"));
                var events=new ArrayList<String>();var dirty=new AtomicBoolean(b(r,"dirtySeed"));ItemUseTestModel.Item[] riptide={b(r,"dirtySeed")?model.cloneItem(model.inputs.get(3)):model.air};
                var releaseContext=new ItemTransactionValidator.ReleaseContext<ItemUseTestModel.Item>(){
                    public InventoryTransactionPacket releaseItemData(InventoryTransactionPacket p){return b(r,"releaseData")?p:null;}public ItemUseTracker<ItemUseTestModel.Item> tracker(){return tracker;}public ItemUseTestModel.Item nativeItem(ItemData item){assertSame(heldData,item);return held;}
                    public ItemUseTestModel.Item serverHeldItem(){events.add("serverInventory");if(!b(r,"hasInventory"))throw new NullPointerException();events.add("serverHeld");return model.item(i(r,"serverHeld"));}
                    public int riptideLevel(ItemUseTestModel.Item item){return item==null||model.isNull(item)?0:item.profile.get("riptide").getAsInt();}
                    // Test binding of the separate native PlayerData.setDirtyRiptide boundary.
                    public void setDirtyRiptide(int charge,ItemUseTestModel.Item item){if(charge<10||riptideLevel(item)<=0)return;riptide[0]=model.cloneItem(item);dirty.set(true);}
                };
                java.util.function.IntSupplier flight=()->{if(!r.get("flightError").getAsString().isEmpty())throw new ClassCastException();return i(r,"flight");};
                var airContext=new ItemTransactionValidator.ClickAirContext<ItemUseTestModel.Item>(){
                    public boolean validate(ItemData p,ItemData c){return ItemTransactionValidator.validate(p,c);}public boolean nativeFirework(ItemData item){assertSame(heldData,item);return held.profile.get("firework").getAsBoolean();}
                    public boolean serverGliding(){events.add("serverGliding");return b(r,"serverGlide");}public boolean serverChestIsElytra(){events.add("serverInventory");if(!b(r,"hasInventory"))throw new NullPointerException();events.add("serverChest");return model.inputs.get(i(r,"serverChest")).profile.get("elytraClass").getAsBoolean();}public boolean compensatedGliding(){return b(r,"compGlide");}
                    ItemUseTestModel.Item bound(ItemData item){if(item==chestData)return chest;assertSame(heldData,item);return held;}
                    public boolean nativeIsNull(ItemData item){return model.isNull(bound(item));}public int nativeItemId(ItemData item){return model.nativeItemId(bound(item));}public int nativeFireworkFlight(ItemData item){assertSame(heldData,item);return flight.getAsInt();}public boolean usingItemFlag(){return model.hasFlag(-1);}public ItemUseTracker<ItemUseTestModel.Item> tracker(){return tracker;}public ItemUseTestModel.Item nativeItem(ItemData item){assertSame(heldData,item);return held;}
                };
                boolean result=true;String error="";try{var validator=new ItemTransactionValidator(player,inventory);switch(i(r,"mode")){case 0->result=validator.handleClickAir(packet,airContext);case 1->result=validator.handleRelease(packet,releaseContext);case 2->player.beginNukkitGlideBoost(flight.getAsInt(),b(r,"expectedRocket"));default->throw new AssertionError();}}catch(RuntimeException e){error=e.getClass().getSimpleName();}
                String label="original air/release case="+n;assertEquals(b(r,"result"),result,label);assertEquals(r.get("error").getAsString(),error,label);assertEquals(r.get("events"),gson.toJsonTree(events),label+" reads");assertEquals(r.get("state"),gson.toJsonTree(model.state(tracker,Collections.nCopies(4,null))),label+" tracker");assertEquals(i(r,"boostAfter"),player.glideBoostTicks(),label);assertEquals(b(r,"pendingAfter"),player.nukkitGlideBoostPending,label);assertEquals(b(r,"dirtyAfter"),dirty.get(),label);assertEquals(r.get("riptideAfter"),gson.toJsonTree(model.view(riptide[0])),label);assertEquals(i(r,"heldAfter"),inventory.inventoryContainer.getHeldItemData().getCount(),label);assertEquals(i(r,"chestAfter"),inventory.armorContainer.get(1).count(),label);if(!error.isEmpty())errors++;if(player.glideBoostTicks()!=i(r,"boost"))boosts++;if(dirty.get())riptides++;
            }
            assertNull(in.readLine());
        }
        assertTrue(errors>50);assertTrue(boosts>100);assertTrue(riptides>100);
    }
}
