package ac.navro.proxy.data.inventory;

import ac.navro.proxy.ProtocolDefinitions;
import ac.navro.proxy.SupportedCodecs;
import ac.navro.proxy.util.ItemUtil;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.nbt.*;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class NativeItemDecoderReferenceTest {
    static byte[] blob(DataInputStream in)throws Exception{return in.readNBytes(in.readInt());}
    static NbtMap nbt(byte[] bytes)throws Exception{
        if(bytes.length==0)return null;
        try(var in=NbtUtils.createReaderLE(new ByteArrayInputStream(bytes))){NbtMap tag=(NbtMap)in.readTag();return tag.isEmpty()?null:tag;}
    }
    static ItemDefinition definition(String name,int runtime){return new ItemDefinition(){
        public String getIdentifier(){return name;}public int getRuntimeId(){return runtime;}public boolean isComponentBased(){return false;}
    };}
    @Test void actualFrozenNativeWireDecodersMatchPropertiesIdentityNbtAndExceptions()throws Exception{
        int successes=0,errors=0,sdk=0;
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/native-item-decoder.bin.gz")))){
            assertEquals(0x4e494633,in.readInt());int count=in.readInt();assertEquals(4488,count);assertTrue(in.readInt()>0);
            for(int i=0;i<count;i++){
                int protocol=in.readInt();var format=NativeItemDecoder.Format.values()[in.readInt()];boolean descriptor=in.readBoolean();
                String name=in.readUTF();int runtime=in.readInt(),amount=in.readInt(),damage=in.readInt(),net=in.readInt();
                NbtMap tag=nbt(blob(in));byte[] bytes=blob(in);
                ItemData raw=ItemData.builder().definition(definition(name,runtime)).count(amount).damage(damage).netId(net).usingNetId(net!=0).tag(tag)
                        .canPlace(new String[]{"minecraft:stone"}).canBreak(new String[]{"minecraft:dirt"}).build();
                boolean success=in.readBoolean();String label="native wire case="+i+" protocol="+protocol+" format="+format+" shell="+name;
                if(!success){
                    String type=in.readUTF();RuntimeException error=assertThrows(RuntimeException.class,()->NativeItemDecoder.decode(protocol,raw,format),label);
                    assertEquals(type,error.getClass().getName(),label);errors++;continue;
                }
                int id=in.readInt(),meta=in.readInt(),expectedCount=in.readInt(),stack=in.readInt();boolean isNull=in.readBoolean();
                String identifier=in.readBoolean()?in.readUTF():null;int network=in.readInt();NbtMap expectedTag=nbt(blob(in));
                var traits=NativePredictionItems.readTraits(in);
                ItemData decoded=NativeItemDecoder.decode(protocol,raw,format);
                assertEquals(id,NativePredictionItems.resolve(protocol,decoded).nativeId(),label);
                assertEquals(traits,NativePredictionItems.resolve(protocol,decoded),label);
                assertEquals(meta,decoded.getDamage(),label);assertEquals(expectedCount,decoded.getCount(),label);assertEquals(stack,decoded.getNetId(),label);
                assertEquals(isNull,decoded.getCount()<=0||traits.positiveNull(),label);
                assertEquals(identifier,ItemUtil.identifier(decoded),label);assertEquals(network,ItemUtil.runtimeId(decoded),label);assertEquals(expectedTag,decoded.getTag(),label);
                assertSame(decoded,NativeItemDecoder.decode(protocol,decoded,format),label);
                assertEquals(traits,NativePredictionItems.resolve(protocol,ItemUtil.copy(decoded)),label);
                assertEquals(tag,raw.getTag(),label);assertEquals(damage,raw.getDamage(),label);assertEquals(name,raw.getDefinition().getIdentifier(),label);
                // Both implementations consume these exact bytes, with connection definitions installed.
                var codec=SupportedCodecs.forProtocol(protocol);
                if(format==NativeItemDecoder.Format.INVENTORY&&codec!=null){
                    var definitions=new ProtocolDefinitions();var start=new StartGamePacket();start.getItemDefinitions().add(raw.getDefinition());definitions.observeBackend(start);
                    var helper=codec.createHelper();definitions.configure(helper,true);var buffer=Unpooled.wrappedBuffer(bytes);
                    try{
                        ItemData sdkItem=descriptor?helper.readNetworkItemStackDescriptor(buffer):helper.readItem(buffer);assertFalse(buffer.isReadable(),label);
                        ItemData throughSdk=NativeItemDecoder.decode(protocol,sdkItem,format);
                        assertEquals(decoded.getDamage(),throughSdk.getDamage(),label);assertEquals(decoded.getTag(),throughSdk.getTag(),label);
                        assertEquals(ItemUtil.identifier(decoded),ItemUtil.identifier(throughSdk),label);sdk++;
                    }finally{buffer.release();}
                }
                successes++;
            }
            assertEquals(-1,in.read());assertTrue(successes>2500);assertTrue(errors>100);assertTrue(sdk>600);
        }
    }
    @Test void canonicalNbtCopiesDoNotAliasTheWireOrLaterNativeClones(){
        NbtMap inner=NbtMap.builder().putByteArray("bytes",new byte[]{1,2,3}).build();
        ItemData wire=ItemData.builder().definition(definition("minecraft:info_update",123)).count(1)
                .tag(NbtMap.builder().putInt("mv_origin_id",455).putInt("mv_origin_meta",0).putCompound("mv_origin_nbt",inner).build()).build();
        ItemData decoded=NativeItemDecoder.decode(2193,wire),copy=ItemUtil.copy(decoded);
        decoded.getTag().getByteArray("bytes")[0]=99;
        assertArrayEquals(new byte[]{1,2,3},inner.getByteArray("bytes"));assertArrayEquals(new byte[]{1,2,3},copy.getTag().getByteArray("bytes"));
        assertEquals(455,NativePredictionItems.resolve(2193,copy).nativeId());
    }
}
