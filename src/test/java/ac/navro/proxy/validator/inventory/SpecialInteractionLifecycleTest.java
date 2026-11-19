package ac.navro.proxy.validator.inventory;

import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.*;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpecialInteractionLifecycleTest {
    static final Vector3i POS=Vector3i.from(8,64,8);
    static NetworkBlockState block(int id,int meta){return NetworkBlockState.legacy(BlockStateEncoding.fullId(id,meta));}
    static ItemData item(String name,int count){return ItemData.builder().definition(new SimpleItemDefinition(name,20001,false)).damage(999).count(count).tag(NbtMap.builder().putString("label","untouched").build()).build();}
    static CompensatedWorld world(boolean hash){var w=new CompensatedWorld();w.setProtocolVersion(2193);var start=new StartGamePacket();start.setBlockNetworkIdsHashed(hash);w.apply(start);w.put(0,0,CompensatedWorld.CachedChunk.network(-64,320,24,w.airNetworkId()));return w;}
    static final class Context implements ItemTransactionValidator.UseItemOnContext {
        int id=1,damage=0,mapped=1;final List<String> reads=new ArrayList<>();
        public int nativeItemId(ItemData item){reads.add("id");return id;}public int nativeItemDamage(ItemData item){reads.add("damage");return damage;}public int nativeBlockId(ItemData item){reads.add("mapped");return mapped;}
        public boolean nativeIsNull(ItemData item){reads.add("empty");return id==0||item.getCount()<=0;}
        public boolean hasTag(ItemData item,String tag){reads.add(tag);return ItemTransactionValidator.UseItemOnContext.super.hasTag(item,tag);}
    }
    private static ItemTransactionValidator validator(){var p=new WirePlayerState();return new ItemTransactionValidator(p,new CompensatedInventory(p));}
    @Test void cauldronPreservesBucketCheckOrderForAllModesAndNeverMutatesWorldOrInventory() {
        for(boolean hash:new boolean[]{false,true})for(GameType mode:GameType.values())for(int damage:new int[]{8,10,11,0}) {
            var p=new WirePlayerState();p.acknowledgeGameType(mode);var inv=new CompensatedInventory(p);inv.inventoryContainer.set(0,item("wire:unrelated",0));var alias=inv.inventoryContainer.getHeldItemCache();var held=alias.getData();var w=world(hash);w.updateLegacyBlock(POS,0,118<<13);var context=new Context();context.id=325;context.damage=damage;
            assertEquals(damage!=0,new ItemTransactionValidator(p,inv).handleUseItemOn(w,POS,block(118,0),held,1,context));
            if(damage==8)assertEquals(List.of("id","id","damage"),context.reads);if(damage==10)assertEquals(List.of("id","id","damage","id","damage"),context.reads);
            assertEquals(w.networkIdForLegacy(118<<13),w.getRawBlockAt(8,64,8,0));assertEquals(0,w.bucketPredictionRevision());assertSame(alias,inv.inventoryContainer.getHeldItemCache());assertSame(held,alias.getData());assertEquals(0,held.getCount());assertEquals("untouched",held.getTag().getString("label"));
        }
    }
    @Test void cakeChecksNativeItemTagsBeforeDamageAndCandleUsesNativeEmptyIdentityAndLitState() {
        var v=validator();var c=new Context();var stone=item("minecraft:stone",7);
        assertTrue(v.handleUseItemOn(null,null,block(92,0),stone,null,c));assertEquals(List.of("id","minecraft:candles"),c.reads);c.reads.clear();assertFalse(v.handleUseItemOn(null,null,block(92,1),stone,null,c));
        assertTrue(v.handleUseItemOn(null,null,block(92,1),item("minecraft:red_candle",7),null,c));
        c.id=0;assertFalse(stone.isNull());assertTrue(v.handleUseItemOn(null,null,block(667,4),stone,null,c));assertFalse(v.handleUseItemOn(null,null,block(667,0),stone,null,c));
        c.id=1;assertTrue(v.handleUseItemOn(null,null,block(667,4),item("wire:not_air",0),null,c));assertFalse(v.handleUseItemOn(null,null,block(667,4),stone,null,c));
    }
    @Test void lecternRetainsNumericConversionCompoundPresenceAndFailureBeforeBookFallback() {
        var v=validator();var w=world(false);var c=new Context();c.id=0;var held=item("wire:positive_air",5);
        w.updateBlockEntityTag(8,64,8,NbtMap.builder().putInt("hasBook",65536).build());assertTrue(v.lecternHasBook(w,POS));assertFalse(v.handleUseItemOn(w,POS,block(449,0),held,1,c));
        w.updateBlockEntityTag(8,64,8,NbtMap.builder().putLong("hasBook",1L<<32).build());assertFalse(v.lecternHasBook(w,POS));assertTrue(v.handleUseItemOn(w,POS,block(449,0),held,1,c));
        w.updateBlockEntityTag(8,64,8,NbtMap.builder().putCompound("book",NbtMap.EMPTY).build());assertTrue(v.lecternHasBook(w,POS));
        w.updateBlockEntityTag(8,64,8,NbtMap.builder().putString("hasBook","true").putCompound("book",NbtMap.EMPTY).build());assertThrows(ClassCastException.class,()->v.handleUseItemOn(w,POS,block(449,0),held,1,c));
        c.id=1;assertFalse(v.handleUseItemOn(null,null,block(449,0),held,1,c));
    }
    @Test void vaultPreservesFirstNonemptyStateNumericStringsNullReflectionFallbackAndEmptyHandShortCircuit() {
        var v=validator();var w=world(false);var c=new Context();var held=item("wire:held",1);
        w.updateBlockEntityTag(8,64,8,NbtMap.builder().putString("vault_state","inactive").putString("state","active").build());assertFalse(v.isVaultActive(w,POS,block(569,0)));assertTrue(v.handleUseItemOn(w,POS,block(569,0),held,1,c));
        w.updateBlockEntityTag(8,64,8,NbtMap.builder().putInt("vault_state",0).putString("state","active").build());assertFalse(v.isVaultActive(w,POS,block(569,0)));
        w.updateBlockEntityTag(8,64,8,NbtMap.builder().putString("vault_state","").putString("state","ACTIVE").build());assertTrue(v.isVaultActive(w,POS,null));assertFalse(v.handleUseItemOn(w,POS,block(569,0),held,1,c));
        w.updateBlockEntityTag(8,64,8,NbtMap.builder().putString("vault_state","active").putCompound("state",NbtMap.EMPTY).build());assertTrue(v.isVaultActive(w,POS,block(569,0)));
        w.updateBlockEntityTag(8,64,8,NbtMap.builder().putCompound("vault_state",NbtMap.EMPTY).putString("state","active").build());assertThrows(ClassCastException.class,()->v.isVaultActive(w,POS,block(569,0)));c.id=0;assertTrue(v.handleUseItemOn(null,null,block(569,0),held,1,c));
    }
    @Test void noteblockRequiresUpFaceAndNativeTagWhileAnchorBerryAndTntUseNativeMappings() {
        var v=validator();var c=new Context();var skull=item("minecraft:player_head",7);assertTrue(v.handleUseItemOn(null,null,block(25,0),skull,1,c));assertFalse(v.handleUseItemOn(null,null,block(25,0),skull,2,c));assertFalse(v.handleUseItemOn(null,null,block(25,0),skull,null,c));
        var held=item("minecraft:glowstone",7);assertFalse(v.handleUseItemOn(null,null,block(527,0),held,1,c));c.mapped=89;assertTrue(v.handleUseItemOn(null,null,block(527,3),held,1,c));assertFalse(v.handleUseItemOn(null,null,block(527,4),held,1,c));
        c.id=351;c.damage=15;assertTrue(v.handleUseItemOn(null,null,block(462,2),held,1,c));assertFalse(v.handleUseItemOn(null,null,block(462,3),held,1,c));c.id=255;assertFalse(v.handleUseItemOn(null,null,block(462,2),item("minecraft:bone_meal",7),1,c));
        c.id=259;assertTrue(v.handleUseItemOn(null,null,block(46,0),held,1,c));c.id=385;assertTrue(v.handleUseItemOn(null,null,block(46,0),held,1,c));c.id=1;assertFalse(v.handleUseItemOn(null,null,block(46,0),item("minecraft:flint_and_steel",7),1,c));
    }
    @Test void bellRetainsAttachmentAxisThresholdAndNanBehaviorWithoutWorldOrItemChanges() {
        assertTrue(ItemTransactionValidator.isProperHit(block(461,4),4,0.8124F));assertFalse(ItemTransactionValidator.isProperHit(block(461,4),4,Math.nextUp(0.8124F)));assertTrue(ItemTransactionValidator.isProperHit(block(461,4),4,Float.NaN));assertFalse(ItemTransactionValidator.isProperHit(block(461,4),1,Float.NaN));
        assertTrue(ItemTransactionValidator.isProperHit(block(461,0),2,0));assertFalse(ItemTransactionValidator.isProperHit(block(461,0),4,0));assertFalse(ItemTransactionValidator.isProperHit(block(461,8),2,0));assertTrue(ItemTransactionValidator.isProperHit(block(461,8),4,0));
        assertFalse(ItemTransactionValidator.isProperHit(null,1,0));assertThrows(NullPointerException.class,()->ItemTransactionValidator.isProperHit(block(461,0),null,0));
    }
    @Test void nullOperandsReturnBeforeContextAndOriginalUnconditionalBranchesIgnorePositionAndNbt() {
        var v=validator();var held=item("wire:held",7);assertFalse(v.handleUseItemOn(null,null,null,held,null,null));assertFalse(v.handleUseItemOn(null,null,block(1,0),null,null,null));
        var c=new Context();for(int id:new int[]{86,73,63,68})assertTrue(v.handleUseItemOn(null,null,block(id,0),held,null,c));for(int id:new int[]{91,74,1,0})assertFalse(v.handleUseItemOn(null,null,block(id,0),held,null,c));
    }
}
