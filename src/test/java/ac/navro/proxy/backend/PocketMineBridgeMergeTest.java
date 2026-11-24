package ac.navro.proxy.backend;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.command.NavroCommand;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import ac.navro.proxy.data.inventory.NativeItemDecoder;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.nbt.*;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PocketMineBridgeMergeTest {
    static ScriptMessagePacket message(String text){var p=new ScriptMessagePacket();p.setChannel(BackendStateSync.CHANNEL);p.setMessage(text);return p;}
    static StartGamePacket start(){var p=new StartGamePacket();p.setRuntimeEntityId(7);p.setUniqueEntityId(70);p.setPlayerPosition(Vector3f.from(8,64,8));return p;}
    static String palette(boolean large)throws Exception{
        var air=NbtMap.builder().putString("name","minecraft:air").putCompound("states",NbtMap.EMPTY).build();
        var stone=NbtMap.builder().putString("name","minecraft:stone").putCompound("states",NbtMap.EMPTY).build();
        var states=new ArrayList<NbtMap>(List.of(stone,air));var random=new Random(37);
        if(large)for(int i=0;i<4096;i++)states.add(air.toBuilder().putString("name","bridge:"+Long.toUnsignedString(random.nextLong(),16)+Long.toUnsignedString(random.nextLong(),16)).build());
        var bytes=new ByteArrayOutputStream();
        try(var writer=NbtUtils.createGZIPWriter(bytes)){writer.writeTag(NbtMap.builder().putInt("protocol",2193).putList("states",NbtType.COMPOUND,states).build());}
        return "palette|"+Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    @Test void readinessAndAlertsAreNonceBoundAndNeedAFreshRealTick(){
        var s=AuthInputPacketsTest.session();var sync=s.backendSync();String nonce=sync.begin().getMessage().substring(6);
        sync.receive(message("pm_ready|wrong"));assertFalse(sync.isPocketMine());assertNull(sync.alert("test"));
        sync.receive(message("pm_ready|"+nonce));assertTrue(sync.isPocketMine());assertNull(sync.alert("test"));
        sync.receive(message("tick|"+nonce+"|0|901"));assertTrue(s.world().hasNativeServerTick());
        assertEquals("alert|"+nonce+"|test",sync.alert("test").getMessage());assertNull(sync.alert("x".repeat(4097)));
        sync.reset();assertFalse(sync.isPocketMine());assertFalse(s.world().hasNativeServerTick());assertNull(sync.alert("test"));
    }
    @Test void runtimePaletteIsAppliedAtStartAndDoesNotLeakIntoTheNextWorld()throws Exception{
        var s=AuthInputPacketsTest.session();s.consumeBackendSync(message(palette(false)));assertTrue(s.backendSync().hasRuntimePalette());
        s.observeBackend(start());assertEquals(1,s.world().airNetworkId());assertTrue(s.world().networkBlockState(0).is("minecraft:stone"));
        assertFalse(s.backendSync().hasRuntimePalette());
        s.observeBackend(start());assertEquals(NetworkBlockStateRegistry.forProtocol(2193,false).airNetworkId(),s.world().airNetworkId());
        s.closeBackendWorld();assertFalse(s.world().hasNativeServerTick());assertFalse(s.backendSync().hasRuntimePalette());
    }
    @Test void multipartPalettesRejectReorderingAndAcceptACompleteBoundedTransfer()throws Exception{
        var s=AuthInputPacketsTest.session();String encoded=palette(true);assertTrue(encoded.length()>30000);
        int count=(encoded.length()+29999)/30000;
        s.consumeBackendSync(message("palette_part|1|"+count+"|"+encoded.substring(30000,60000)));assertFalse(s.backendSync().hasRuntimePalette());
        for(int i=0;i<count;i++)s.consumeBackendSync(message("palette_part|"+i+"|"+count+"|"+encoded.substring(i*30000,Math.min((i+1)*30000,encoded.length()))));
        assertTrue(s.backendSync().hasRuntimePalette());s.observeBackend(start());assertEquals(1,s.world().airNetworkId());
    }
    static ItemData custom(int count){return ItemData.builder().definition(new SimpleItemDefinition("bridge:gem",31000,true)).count(count).tag(NbtMap.builder().putString("payload","kept").build()).build();}
    @Test void customDefinitionsKeepTheirBackendIdentityAndVanillaItemsKeepTheirNativeDecoder(){
        var item=custom(5);assertSame(item,NativeItemDecoder.decode(2193,item));assertTrue(NativeItemDecoder.usesBackendDefinition(2193,item));
        assertFalse(NativeItemDecoder.placement(2193,item).canBePlaced());assertFalse(NativeItemDecoder.traits(2193,item).positiveNull());
        var stone=ItemData.builder().definition(new SimpleItemDefinition("minecraft:stone",1,false)).count(1).build();
        assertFalse(NativeItemDecoder.usesBackendDefinition(2193,stone));assertEquals(1,NativeItemDecoder.traits(2193,stone).nativeId());
    }
    @Test void customInventoryDropsDeductOnlyTheValidatedSlotAndRejectOverdraw(){
        var s=AuthInputPacketsTest.session();s.inventory().inventoryContainer.set(12,custom(5));
        var p=new InventoryTransactionPacket();p.setTransactionType(InventoryTransactionType.NORMAL);
        p.getActions().add(new InventoryActionData(InventorySource.fromContainerWindowId(0),12,custom(5),custom(3)));
        p.getActions().add(new InventoryActionData(InventorySource.fromWorldInteraction(InventorySource.Flag.DROP_ITEM),0,ItemData.AIR,custom(2)));
        assertTrue(s.transactionValidator().handleNormal(p));assertEquals(3,s.inventory().inventoryContainer.get(12).count());
        p.getActions().set(1,new InventoryActionData(InventorySource.fromWorldInteraction(InventorySource.Flag.DROP_ITEM),0,ItemData.AIR,custom(4)));
        assertFalse(s.transactionValidator().handleNormal(p));assertEquals(3,s.inventory().inventoryContainer.get(12).count());
        assertEquals("kept",s.inventory().inventoryContainer.get(12).getData().getTag().getString("payload"));
    }
    @Test void backendCommandNamesRemainOwnedByTheBackend(){
        var p=new AvailableCommandsPacket();assertEquals(NavroCommand.NAMES,NavroCommand.advertise(p));
        assertEquals(Set.of("navro:navro"),NavroCommand.unclaimedNames(p));
        NavroCommand.advertise(p);assertEquals(1,p.getCommands().stream().filter(c->c.getName().equals("navro")).count());
    }
}
