package ac.navro.proxy.validator.inventory;

import ac.navro.proxy.data.inventory.NativeItemDecoder;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreativeBucketTransactionTest {
    @Test void consecutiveCreativeBucketTransactionsPassRealValidationAndPredictEachTarget() {
        int[] damages={8,10,11},blocks={8,10,561};
        for(int i=0;i<damages.length;i++) {
            var s=AuthInputPacketsTest.session();s.player().acknowledgeGameType(GameType.CREATIVE);
            var item=NativeItemDecoder.constructNumeric(2193,325,damages[i],1).toBuilder()
                    .tag(NbtMap.builder().putString("custom","creative bucket").build()).build();
            s.inventory().inventoryContainer.set(0,item);var original=s.inventory().inventoryContainer.getHeldItemData();
            var alias=s.inventory().inventoryContainer.getHeldItemCache();long before=s.world().bucketPredictionRevision();
            for(int repeat=0;repeat<3;repeat++) {
                var clicked=Vector3i.from(9+repeat,63,8);var packet=new InventoryTransactionPacket();
                packet.setTransactionType(InventoryTransactionType.ITEM_USE);packet.setActionType(0);packet.setHotbarSlot(0);
                packet.setBlockPosition(clicked);packet.setBlockFace(1);packet.setClickPosition(Vector3f.from(.5,1,.5));
                packet.setItemInHand(item);packet.setClientInteractPrediction(ItemUseTransaction.PredictedResult.SUCCESS);
                assertTrue(s.receiveClient(packet,8500),s.anomalies().toString());
                assertSame(alias,s.inventory().inventoryContainer.getHeldItemCache());assertSame(original,s.inventory().inventoryContainer.getHeldItemData());
                assertEquals(s.world().networkIdForLegacy(blocks[i]<<13),s.world().getRawBlockAt(9+repeat,64,8,0));
            }
            assertEquals(before+3,s.world().bucketPredictionRevision());assertTrue(s.anomalies().isEmpty());
        }
    }
}
