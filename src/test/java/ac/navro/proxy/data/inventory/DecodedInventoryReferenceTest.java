package ac.navro.proxy.data.inventory;

import ac.navro.proxy.ProtocolDefinitions;
import ac.navro.proxy.SupportedCodecs;
import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.util.ItemUtil;
import ac.navro.proxy.validator.inventory.ItemTransactionValidator;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class DecodedInventoryReferenceTest {
    @Test void everySupplementaryRuntimeBindingMatchesTheActualNativeByteDecoder()throws Exception{
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/native-item-runtime-bindings.bin.gz")))){
            assertEquals(0x4e494252,in.readInt());int count=in.readInt();assertEquals(4551,count);
            for(int i=0;i<count;i++){
                int protocol=in.readInt();String wireName=in.readUTF();int wireRuntime=in.readInt();
                var wire=ItemData.builder().definition(new SimpleItemDefinition(wireName,wireRuntime,false)).count(1).damage(3)
                        .tag(org.cloudburstmc.nbt.NbtMap.builder().putInt("marker",1).build()).build();
                String label="supplementary native binding="+wireName+" protocol="+protocol;
                ItemData item=assertDoesNotThrow(()->NativeItemDecoder.decode(protocol,wire),label);
                assertEquals(in.readInt(),item.getDamage(),label);assertEquals(in.readInt(),item.getCount(),label);
                String identifier=in.readBoolean()?in.readUTF():null;assertEquals(identifier,ItemUtil.identifier(item),label);assertEquals(in.readInt(),ItemUtil.runtimeId(item),label);
                assertEquals(NativePredictionItems.readTraits(in),NativePredictionItems.resolve(protocol,item),label);
            }
            assertEquals(-1,in.read());
        }
    }
    static ItemData wire(DataInputStream in,int protocol)throws Exception{
        if(!in.readBoolean())return null;
        boolean descriptor=in.readBoolean();String name=in.readUTF();int runtime=in.readInt();byte[] bytes=in.readNBytes(in.readInt());
        var definitions=new ProtocolDefinitions();var start=new StartGamePacket();start.getItemDefinitions().add(new SimpleItemDefinition(name,runtime,false));definitions.observeBackend(start);
        var helper=SupportedCodecs.forProtocol(protocol).createHelper();definitions.configure(helper,true);var buffer=Unpooled.wrappedBuffer(bytes);
        try{ItemData item=descriptor?helper.readNetworkItemStackDescriptor(buffer):helper.readItem(buffer);assertFalse(buffer.isReadable());return item;}finally{buffer.release();}
    }
    static void state(DataInputStream in,int protocol,ItemCache cache,String label)throws Exception{
        ItemData nativeItem=cache.getNativeData();
        assertEquals(in.readInt(),NativePredictionItems.resolve(protocol,nativeItem).nativeId(),label);
        assertEquals(in.readInt(),nativeItem.getDamage(),label);assertEquals(in.readInt(),nativeItem.getCount(),label);
        assertEquals(in.readBoolean(),cache.isEmpty(),label);
        String name=in.readBoolean()?in.readUTF():null;assertEquals(name,ItemUtil.identifier(NativeItemDecoder.decode(protocol,nativeItem)),label);
        assertEquals(in.readInt(),ItemUtil.runtimeId(NativeItemDecoder.decode(protocol,nativeItem)),label);
    }
    @Test void actualOriginalCacheBundleAndDropOperationsMatchAfterSdkReadsTheSameNativeBytes()throws Exception{
        int bundleCases=0,dropCases=0,errorCases=0,owned=0;
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/navro-decoded-inventory.bin.gz")))){
            assertEquals(0x4e444931,in.readInt());int count=in.readInt();assertEquals(1728,count);
            for(int i=0;i<count;i++){
                int protocol=in.readInt(),type=in.readInt();String label="native inventory case="+i+" protocol="+protocol;
                var session=new ac.navro.proxy.SessionState();session.setProtocolVersion(protocol);session.player().setProtocolVersion(protocol);session.setDetectionEnabled(true);
                var player=session.player();var inventory=session.inventory();
                if(type==0){
                    ItemData bundle=wire(in,protocol),a=wire(in,protocol),b=wire(in,protocol);int slot=in.readInt();
                    ItemCache owner=assertDoesNotThrow(()->ItemCache.build(inventory,bundle),label);boolean hasBundle=in.readBoolean();assertEquals(hasBundle,owner.getBundle()!=null,label);
                    Set<Integer> keys=new TreeSet<>();for(int n=in.readInt();n>0;n--)keys.add(in.readInt());assertEquals(keys,inventory.getBundleCache().keySet(),label);
                    assertEquals(in.readBoolean(),ItemCache.build(inventory,bundle).getBundle()!=null,label);assertEquals(in.readBoolean(),owner.clone().getBundle()==owner.getBundle(),label);
                    if(hasBundle){
                        ItemCache first=ItemCache.build(inventory,a),second=ItemCache.build(inventory,b);var data=owner.getBundle();
                        assertEquals(in.readBoolean(),data.add(slot,first),label);state(in,protocol,data.getContents()[slot],label);ItemCache before=data.getContents()[slot];
                        assertEquals(in.readBoolean(),data.add(slot,second),label);assertEquals(in.readInt(),data.count,label);state(in,protocol,data.getContents()[slot],label);
                        assertEquals(in.readBoolean(),data.getContents()[slot]==before,label);assertEquals(in.readBoolean(),data.getContents()[slot]==first,label);
                        assertEquals(in.readInt(),first.count(),label);assertEquals(in.readInt(),second.count(),label);owned++;
                    }
                    bundleCases++;
                }else if(type==1){
                    ItemData predicted=wire(in,protocol),claimed=wire(in,protocol);inventory.inventoryContainer.set(0,predicted);boolean reverse=in.readBoolean();
                    var packet=new InventoryTransactionPacket();packet.setTransactionType(InventoryTransactionType.NORMAL);
                    var world=new InventoryActionData(InventorySource.fromWorldInteraction(InventorySource.Flag.NONE),0,ItemData.AIR,claimed);
                    var container=new InventoryActionData(InventorySource.fromContainerWindowId(0),0,ItemData.AIR,ItemData.AIR);
                    packet.getActions().addAll(reverse?List.of(container,world):List.of(world,container));ItemCache before=inventory.inventoryContainer.get(0);
                    assertEquals(in.readBoolean(),session.receiveClient(packet,200),label);state(in,protocol,inventory.inventoryContainer.get(0),label);
                    assertEquals(in.readBoolean(),before==inventory.inventoryContainer.get(0),label);dropCases++;
                }else{
                    ItemData malformed=wire(in,protocol);String typeName=in.readUTF();
                    var error=assertThrows(RuntimeException.class,()->ItemCache.build(inventory,malformed),label);
                    assertEquals(typeName,error.getClass().getName(),label);errorCases++;
                }
            }
            assertEquals(-1,in.read());assertEquals(576,bundleCases);assertEquals(576,dropCases);assertEquals(576,errorCases);assertTrue(owned>200);
        }
    }

    @Test void protocol975KeepsInventoryAndLegacyTransactionRestorationRulesSeparate() {
        var inventory=ItemCacheTest.nativeInventory(975);var predicted=ItemCacheTest.origin(1,10,org.cloudburstmc.nbt.NbtMap.EMPTY).toBuilder().definition(new SimpleItemDefinition("minecraft:bow",123,false)).build();
        inventory.inventoryContainer.set(0,predicted);var validator=new ItemTransactionValidator(inventory.getPlayer(),inventory);
        var packet=new InventoryTransactionPacket();packet.setTransactionType(InventoryTransactionType.NORMAL);
        var container=new InventoryActionData(InventorySource.fromContainerWindowId(0),0,ItemData.AIR,ItemData.AIR);
        var claimed=predicted.toBuilder().count(2).build();
        packet.getActions().add(container);packet.getActions().add(new InventoryActionData(InventorySource.fromWorldInteraction(InventorySource.Flag.NONE),0,ItemData.AIR,claimed));
        assertFalse(validator.handleNormal(packet));assertEquals(10,inventory.inventoryContainer.get(0).count());
        packet.getActions().set(1,new InventoryActionData(InventorySource.fromWorldInteraction(InventorySource.Flag.NONE),0,ItemData.AIR,ItemCacheTest.origin(1,2,org.cloudburstmc.nbt.NbtMap.EMPTY)));
        assertTrue(validator.handleNormal(packet));assertEquals(8,inventory.inventoryContainer.get(0).count());assertEquals("minecraft:bow",inventory.inventoryContainer.get(0).getData().getDefinition().getIdentifier());
        assertEquals(1,NativePredictionItems.resolve(975,inventory.inventoryContainer.get(0).getNativeData()).nativeId());assertEquals(10,predicted.getCount());
    }
}
