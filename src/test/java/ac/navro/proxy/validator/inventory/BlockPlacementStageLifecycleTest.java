package ac.navro.proxy.validator.inventory;

import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.*;
import ac.navro.proxy.data.inventory.*;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BlockPlacementStageLifecycleTest {
    private static final Vector3i POS=Vector3i.from(8,64,8);
    private static CompensatedWorld world(boolean hash,boolean loaded) {
        var w=new CompensatedWorld();w.setProtocolVersion(2193);var start=new StartGamePacket();start.setBlockNetworkIdsHashed(hash);w.apply(start);
        if(loaded)w.put(0,0,CompensatedWorld.CachedChunk.network(-64,320,24,w.airNetworkId()));return w;
    }
    private static ItemData stack(int count) {
        return ItemData.builder().definition(new SimpleItemDefinition("minecraft:spruce_planks",5,false)).damage(1).count(count)
                .tag(NbtMap.builder().putString("label","unchanged").build()).build();
    }
    @Test void nativeDefaultStateAndCountChangesUseRealPaletteModesAndPreserveInventoryPayloads() {
        for(boolean hash:new boolean[]{false,true})for(GameType mode:GameType.values()) {
            var player=new WirePlayerState();player.acknowledgeGameType(mode);var inventory=new CompensatedInventory(player);inventory.heldItemSlot=2;
            var raw=stack(2);inventory.inventoryContainer.set(2,raw);inventory.inventoryContainer.set(7,stack(20));var alias=inventory.inventoryContainer.getHeldItemCache();
            var w=world(hash,true);int snow=BlockStateEncoding.fullId(78,6);w.updateLegacyBlock(POS,0,snow);
            var clicked=w.networkBlockState(w.getRawBlockAt(8,64,8,0));var data=new InventoryTransactionPacket();data.setHotbarSlot(7);var calls=new AtomicInteger();long sent=w.bucketPredictionRevision();
            new ItemTransactionValidator(player,inventory).handleBlockPlacement(w,NativeItemPlacementProperties.forNumeric(5,1),POS,5,clicked,data,(packet,target,id)->{
                calls.incrementAndGet();assertSame(data,packet);assertEquals(POS,target);assertEquals(5,id);
                assertEquals(w.networkIdForLegacy(5<<13),w.getRawBlockAt(8,64,8,0));assertEquals(2,alias.count());
            });
            int expected=mode==GameType.CREATIVE?2:1;assertEquals(1,calls.get());assertEquals(expected,alias.count());assertEquals(20,inventory.inventoryContainer.getItemFromSlot(7).count());
            assertEquals(raw.getTag(),alias.getData().getTag());assertEquals(1,alias.getData().getDamage());assertSame(raw.getDefinition(),alias.getData().getDefinition());
            assertEquals(2,raw.getCount());assertFalse(w.applyAcknowledgedBlock(8,64,8,0,w.networkIdForLegacy(snow),sent));
            assertTrue(w.applyAcknowledgedBlock(8,64,8,0,w.networkIdForLegacy(snow),w.bucketPredictionRevision()));assertEquals(expected,alias.count());
        }
    }
    @Test void originalStageStillConsumesOnUnloadedPredictionAndDoesNotConsumeAfterCallbackFailure() {
        var player=new WirePlayerState();player.acknowledgeGameType(GameType.SURVIVAL);var inventory=new CompensatedInventory(player);inventory.inventoryContainer.set(0,stack(1));
        var alias=inventory.inventoryContainer.getHeldItemCache();var empty=world(false,false);var clicked=NetworkBlockState.legacy(1<<13);var data=new InventoryTransactionPacket();var calls=new AtomicInteger();
        var properties=NativeItemPlacementProperties.forNumeric(295,0);
        new ItemTransactionValidator(player,inventory).handleBlockPlacement(empty,properties,POS,1,clicked,data,(packet,pos,id)->calls.incrementAndGet());
        assertEquals(0,empty.bucketPredictionRevision());assertEquals(1,calls.get());assertEquals(0,alias.count());assertSame(ItemCache.AIR,inventory.inventoryContainer.getHeldItemCache());
        inventory.inventoryContainer.set(0,stack(2));var loaded=world(false,true);
        assertThrows(IllegalStateException.class,()->new ItemTransactionValidator(player,inventory).handleBlockPlacement(loaded,properties,POS,1,clicked,data,(packet,pos,id)->{throw new IllegalStateException("callback failed");}));
        assertEquals(1,loaded.bucketPredictionRevision());assertEquals(loaded.networkIdForLegacy(59<<13),loaded.getRawBlockAt(8,65,8,0));
        assertEquals(2,inventory.inventoryContainer.getHeldItemCache().count());
    }
}
