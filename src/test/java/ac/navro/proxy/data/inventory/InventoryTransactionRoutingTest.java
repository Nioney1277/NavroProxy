package ac.navro.proxy.data.inventory;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.data.ItemUseTracker;
import ac.navro.proxy.packets.player.PlayerInventoryPackets;
import ac.navro.proxy.diagnostics.WorldCollisionDiagnostics;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class InventoryTransactionRoutingTest {
    static SessionState session(int protocol) {
        var s=new SessionState();s.setProtocolVersion(protocol);s.player().setProtocolVersion(protocol);
        var start=new StartGamePacket();start.setRuntimeEntityId(7);start.setPlayerPosition(Vector3f.from(8,64,8));s.observeBackend(start);
        s.setDetectionEnabled(true);s.authInputPackets();return s;
    }
    static ItemData item(String name,int count) {return ItemData.builder().definition(new SimpleItemDefinition(name,1234,false)).count(count).build();}
    static InventoryTransactionPacket use(ItemData claim,int action) {
        var p=new InventoryTransactionPacket();p.setTransactionType(InventoryTransactionType.ITEM_USE);p.setActionType(action);
        p.setHotbarSlot(0);p.setBlockPosition(Vector3i.ZERO);p.setItemInHand(claim);return p;
    }
    static InventoryTransactionPacket drop(ItemData claim,boolean reverse) {
        var p=new InventoryTransactionPacket();p.setTransactionType(InventoryTransactionType.NORMAL);
        var world=new InventoryActionData(InventorySource.fromWorldInteraction(InventorySource.Flag.NONE),0,ItemData.AIR,claim);
        var container=new InventoryActionData(InventorySource.fromContainerWindowId(0),0,ItemData.AIR,ItemData.AIR);
        p.getActions().addAll(reverse?List.of(container,world):List.of(world,container));return p;
    }

    @Test void productionForwardingDecisionAndStateMatchOriginalInventoryReceiveListener() throws Exception {
        int accepted=0,rejected=0,rows=0;
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/navro-inventory-transactions.bin.gz")))) {
            assertEquals(0x4e495431,in.readInt());int count=in.readInt();assertEquals(72,count);
            for(int i=0;i<count;i++) {
                int protocol=in.readInt(),scenario=in.readInt();var s=session(protocol);var use=s.authInputPackets().itemUse();
                ItemData held=DecodedInventoryReferenceTest.wire(in,protocol),claimed=DecodedInventoryReferenceTest.wire(in,protocol);s.inventory().inventoryContainer.set(0,held);
                InventoryTransactionPacket packet;
                if(scenario<=4) {
                    packet=drop(claimed,scenario==4);if(scenario==3)packet.getActions().removeLast();
                } else if(scenario<=9) {
                    packet=use(claimed,scenario==9?2:1);if(scenario==6)packet.setHotbarSlot(9);
                    if(scenario==8)packet.getActions().add(new InventoryActionData(InventorySource.fromContainerWindowId(0),0,item("minecraft:apple",1),ItemData.AIR));
                } else if(scenario==10) {
                    use.tracker().use(use.nativeItem(held),261,false);packet=new InventoryTransactionPacket();packet.setTransactionType(InventoryTransactionType.ITEM_RELEASE);packet.setActionType(0);
                } else {packet=new InventoryTransactionPacket();packet.setTransactionType(InventoryTransactionType.INVENTORY_MISMATCH);}
                String label="original receive case="+i+" protocol="+protocol+" scenario="+scenario;
                boolean expected=in.readBoolean();assertEquals(expected,s.receiveClient(packet,200),label);
                assertEquals(in.readInt(),s.inventory().inventoryContainer.get(0).count(),label);
                assertEquals(in.readBoolean(),s.inventory().inventoryContainer.get(0)==ItemCache.AIR,label);
                assertEquals(in.readInt(),use.tracker().getJavaItemId(),label);assertEquals(in.readUTF(),use.tracker().getDirtyUsing().name(),label);
                assertEquals(in.readBoolean(),s.player().hasFlag(-1),label);
                assertEquals(expected?0:1,s.anomalies().stream().filter(a->a.kind().equals("inventoryPredictionRejected")).count(),label);
                if(expected)accepted++;else rejected++;rows++;
            }assertEquals(-1,in.read());
        }assertEquals(72,rows);assertTrue(accepted>0);assertTrue(rejected>0);
    }

    @Test void acceptedDropConsumesExactlyOnceAndInvalidDropStopsBeforeChangingTheCache() {
        var s=session(2193);s.inventory().inventoryContainer.set(0,item("minecraft:bow",4));
        assertTrue(s.receiveClient(drop(item("minecraft:bow",2),false),200));assertEquals(2,s.inventory().inventoryContainer.get(0).count());
        var cache=s.inventory().inventoryContainer.get(0);assertFalse(s.receiveClient(drop(item("minecraft:bow",3),true),200));
        assertSame(cache,s.inventory().inventoryContainer.get(0));assertEquals(2,cache.count());
        assertTrue(s.receiveClient(drop(item("minecraft:bow",2),true),200));assertSame(ItemCache.AIR,s.inventory().inventoryContainer.get(0));
    }
    @Test void invalidClickAirDoesNotStartUseAndAcceptedReleaseClearsTheRealTracker() {
        var s=session(2193);s.inventory().inventoryContainer.set(0,item("minecraft:bow",4));var tracker=s.authInputPackets().itemUse().tracker();
        var invalid=use(item("minecraft:stone",2),1);assertFalse(s.receiveClient(invalid,200));assertEquals(-1,tracker.getJavaItemId());assertEquals(ItemUseTracker.DirtyUsing.NONE,tracker.getDirtyUsing());
        var valid=use(item("minecraft:bow",2),1);assertTrue(s.receiveClient(valid,200));assertEquals(261,tracker.getJavaItemId());assertEquals(ItemUseTracker.DirtyUsing.INVENTORY_TRANSACTION,tracker.getDirtyUsing());
        var release=new InventoryTransactionPacket();release.setTransactionType(InventoryTransactionType.ITEM_RELEASE);release.setActionType(0);
        assertTrue(s.receiveClient(release,200));assertEquals(-1,tracker.getJavaItemId());assertEquals(ItemUseTracker.DirtyUsing.NONE,tracker.getDirtyUsing());
    }
    @Test void unsetNativeNormalTypeUsesTheSameRejectionPathAndExemptionDoesNotSkipInventoryValidation() {
        var s=session(2193);s.player().setExempted(true);var packet=new InventoryTransactionPacket();packet.setTransactionType(null);
        assertFalse(s.receiveClient(packet,200));assertEquals("inventoryPredictionRejected",s.anomalies().getLast().kind());
    }
    @Test void validationExceptionsRetainNativeForwardingBehaviorAndDiagnosticFailureCannotChangeIt() {
        var s=session(2193);s.inventory().inventoryContainer.set(0,item("minecraft:bow",4));var rows=new ArrayList<Map<String,Object>>();
        s.player().setWorldDiagnostics(new WorldCollisionDiagnostics(s.player(),s.world(),"inventory",rows::add));
        assertTrue(s.receiveClient(use(item("custom:unexported",1),1),200));assertEquals(4,s.inventory().inventoryContainer.get(0).count());
        assertTrue(rows.stream().anyMatch(r->"inventory_transaction_exception".equals(r.get("event"))&&IllegalArgumentException.class.getName().equals(r.get("errorClass"))));
        assertTrue(s.anomalies().isEmpty());
        s.player().setWorldDiagnostics(new WorldCollisionDiagnostics(s.player(),s.world(),"inventory-failure",row->{throw new IllegalStateException("sink");}));
        assertTrue(s.receiveClient(use(item("custom:unexported",1),1),200));assertEquals(4,s.inventory().inventoryContainer.get(0).count());
    }
    @Test void clientPredictionFailureReturnsBeforeBlockPredictionAndInventoryConsumption() {
        var s=session(2193);s.inventory().inventoryContainer.set(0,item("minecraft:stone",4));var p=use(item("minecraft:stone",4),0);
        assertEquals(PlayerInventoryPackets.TransactionResult.ACCEPTED,PlayerInventoryPackets.onTransactionReceive(s,p));
        assertEquals(4,s.inventory().inventoryContainer.get(0).count());assertEquals(0,s.world().bucketPredictionRevision());
    }
    @Test void inspectRetainsObservationForwardingOnInvalidNormalTransactions() {
        var s=session(2193);s.setDetectionEnabled(false);s.inventory().inventoryContainer.set(0,item("minecraft:bow",4));
        assertTrue(s.receiveClient(drop(item("minecraft:bow",9),false),200));assertEquals(4,s.inventory().inventoryContainer.get(0).count());
    }
}
