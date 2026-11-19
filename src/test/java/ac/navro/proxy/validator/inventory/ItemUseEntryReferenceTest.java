package ac.navro.proxy.validator.inventory;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.data.inventory.ItemCache;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.definitions.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.*;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class ItemUseEntryReferenceTest {
    static final class Packet extends InventoryTransactionPacket {
        List<InventoryActionData> actions=new ArrayList<>();
        List<LegacySetItemSlotData> legacy=new ArrayList<>();
        @Override public List<InventoryActionData> getActions(){return actions;}
        @Override public List<LegacySetItemSlotData> getLegacySlots(){return legacy;}
    }
    private static ItemData item(DataInputStream in,Map<ItemDefinition,Integer> identities)throws IOException {
        if(!in.readBoolean())return null;
        int id=in.readInt(),damage=in.readInt(),count=in.readInt();String name=in.readBoolean()?in.readUTF():null;int runtime=in.readInt();
        var definition=new SimpleItemDefinition(name,runtime,false);identities.put(definition,id);
        return ItemData.builder().definition(definition).damage(damage).count(count).build();
    }
    @Test void currentOriginalEntryAndArmorHelperPreserveResultsAndEveryCacheAlias()throws Exception {
        int swaps=0,acceptedCases=0,rejectedCases=0;
        try(var in=new DataInputStream(new GZIPInputStream(Objects.requireNonNull(getClass().getResourceAsStream("/navro-item-use-entry.bin.gz"))))) {
            assertEquals(0x4e554531,in.readInt());int cases=in.readInt();assertEquals(6144,cases);
            for(int i=0;i<cases;i++) {
                int mode=in.readInt();boolean hasData=in.readBoolean();var state=new WirePlayerState();var inventory=new CompensatedInventory(state);inventory.heldItemSlot=in.readInt();
                var pos=new Vec3(in.readFloat(),in.readFloat(),in.readFloat());var identities=new IdentityHashMap<ItemDefinition,Integer>();var aliases=new IdentityHashMap<ItemCache,Integer>();
                for(int s=0;s<9;s++){inventory.inventoryContainer.set(s,item(in,identities));aliases.put(inventory.inventoryContainer.get(s),s);}
                for(int s=0;s<36;s++){inventory.armorContainer.set(s,item(in,identities));aliases.put(inventory.armorContainer.get(s),s+9);}
                var packet=new Packet();packet.setTransactionType(InventoryTransactionType.ITEM_USE);packet.setHotbarSlot(in.readInt());packet.setItemInHand(item(in,identities));
                if(in.readBoolean())packet.setBlockPosition(Vector3i.from(in.readInt(),in.readInt(),in.readInt()));packet.setActionType(in.readInt());in.readInt();
                int count=in.readInt();packet.actions=count<0?null:new ArrayList<>();
                for(int a=0;a<Math.max(0,count);a++)packet.actions.add(in.readBoolean()?new InventoryActionData(InventorySource.fromGlobalInventory(),in.readInt(),item(in,identities),ItemData.AIR):null);
                count=in.readInt();packet.legacy=count<0?null:new ArrayList<>();
                for(int a=0;a<Math.max(0,count);a++){
                    if(!in.readBoolean()){packet.legacy.add(null);continue;}
                    int container=in.readInt(),length=in.readInt();byte[] slots=length<0?null:in.readNBytes(length);packet.legacy.add(new LegacySetItemSlotData(container,slots));
                }
                boolean child=in.readBoolean(),expected=in.readBoolean();String expectedError=in.readUTF();
                var context=new ItemTransactionValidator.TransactionContext(){
                    public Vec3 position(){return pos;}
                    public int nativeItemId(ItemData item){return Objects.requireNonNull(identities.get(item.getDefinition()),"unresolved native item");}
                    public InventoryTransactionPacket useItemData(InventoryTransactionPacket raw){return hasData?raw:null;}
                    public boolean handleClickBlock(InventoryTransactionPacket data){assertSame(packet,data);return child;}
                    public boolean handleClickAir(InventoryTransactionPacket data){assertSame(packet,data);return child;}
                    public boolean handleRelease(InventoryTransactionPacket raw){throw new AssertionError("unexpected release");}
                };
                var validator=new ItemTransactionValidator(state,inventory);boolean accepted=true;String error="";
                try{if(mode==0)accepted=validator.handleUse(packet,context);else if(mode==1)validator.handleLegacyArmorEquip(packet,packet);else accepted=validator.handle(packet,context);}
                catch(Exception e){error=e.getClass().getSimpleName()+":"+e.getMessage();}
                String label="native case "+i+" mode "+mode;assertEquals(expectedError,error,label);assertEquals(expected,accepted,label);if(mode!=1){if(accepted)acceptedCases++;else rejectedCases++;}
                for(int s=0;s<9;s++){int ref=in.readInt(),actual=aliases.getOrDefault(inventory.inventoryContainer.get(s),-1);assertEquals(ref,actual,label+" hotbar "+s);if(ref!=s)swaps++;}
                for(int s=0;s<36;s++)assertEquals(in.readInt(),aliases.getOrDefault(inventory.armorContainer.get(s),-1),label+" armor "+s);
            }
            assertEquals(-1,in.read());
        }
        assertTrue(swaps>75,"fixture must exercise actual reference swaps");assertTrue(acceptedCases>250);assertTrue(rejectedCases>250);
    }
}
