import cn.nukkit.*;
import cn.nukkit.block.Block;
import cn.nukkit.item.*;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.*;
import cn.nukkit.network.protocol.ProtocolInfo;
import cn.nukkit.utils.BinaryStream;
import sun.misc.Unsafe;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NukkitItemDecoderFixture {
    static void blob(DataOutputStream out,byte[] data)throws Exception{out.writeInt(data.length);out.write(data);}
    static byte[] nbt(CompoundTag tag)throws Exception{return tag==null?new byte[0]:NBTIO.write(tag,ByteOrder.LITTLE_ENDIAN);}
    static CompoundTag tag(int scenario){
        var inner=new CompoundTag().putList(new ListTag<CompoundTag>("ench").add(new CompoundTag().putShort("id",30).putShort("lvl",3)))
                .putCompound("Fireworks",new CompoundTag().putByte("Flight",2)).putString("Damage","inner-value").putByteArray("bytes",new byte[]{1,2,3});
        var tag=new CompoundTag().putInt("mv_origin_id",261).putInt("mv_origin_meta",17).putCompound("mv_origin_nbt",inner)
                .putString("outer","discard-me").putInt("Damage",9).putString("__DamageConflict__","outer-value");
        switch(scenario){
            case 1->tag.putInt("mv_origin_id",325).putInt("mv_origin_meta",1);
            case 2->tag.putInt("mv_origin_id",351).putInt("mv_origin_meta",3);
            case 3->tag.putInt("mv_origin_id",519).putInt("mv_origin_meta",123);
            case 4->tag.putInt("mv_origin_id",255).putString("mv_origin_namespace","minecraft:wind_charge");
            case 5->tag.putInt("mv_origin_id",255).putString("mv_origin_namespace","  MINECRAFT:MACE  ");
            case 6->tag.remove("mv_origin_nbt");
            case 7->tag.remove("mv_origin_meta");
            case 8->tag.putString("mv_origin_nbt","wrong-type");
            case 9->tag.putLong("mv_origin_id",455L).putDouble("mv_origin_meta",65535.75);
            case 10->tag.putInt("mv_origin_id",358);
            case 11->tag.putInt("mv_origin_id",0);
            case 12->tag.putInt("mv_origin_id",444).putInt("mv_origin_meta",32768);
            case 13->tag.putInt("mv_origin_id",401).putInt("mv_origin_meta",65535);
            case 14->{tag.remove("mv_origin_id");tag.remove("Damage");}
            case 15->{tag.remove("mv_origin_id");tag.putShort("Damage",(short)32767);}
            case 16->{var list=new ListTag<CompoundTag>("ench");for(int i=0;i<1000;i++)list.add(new CompoundTag().putShort("id",30).putShort("lvl",1));tag.putList(list);}
            case 17->tag.putString("Damage","wrong-type");
            case 18->tag.putString("mv_origin_meta","wrong-type");
            case 19->{tag.putInt("mv_origin_id",455);tag.remove("mv_origin_nbt");}
            case 20->{tag.putInt("mv_origin_id",401);tag.remove("mv_origin_nbt");}
            case 21->{tag.putInt("mv_origin_id",402).putInt("mv_origin_meta",3);tag.remove("mv_origin_nbt");}
        }
        return tag;
    }
    static byte[] wire(GameVersion version,boolean descriptor,int runtime,int count,int damage,int net,CompoundTag tag)throws Exception{
        BinaryStream data=new BinaryStream();
        if(descriptor)data.putLShort(runtime);else data.putVarInt(runtime);
        if(runtime==0&&!descriptor)return data.getBuffer();
        data.putLShort(count);data.putUnsignedVarInt(damage);data.putBoolean(net!=0);
        if(net!=0){if(descriptor&&version.getProtocol()<ProtocolInfo.v1_26_40)data.putUnsignedVarInt(0);data.putVarInt(net);}
        if(descriptor)data.putUnsignedVarInt(0);else data.putVarInt(0);
        BinaryStream user=new BinaryStream();user.putLShort(-1);user.putByte((byte)1);user.put(nbt(tag));
        for(String block:new String[]{"minecraft:stone","minecraft:dirt"}){
            user.putLInt(1);byte[] bytes=block.getBytes(java.nio.charset.StandardCharsets.UTF_8);user.putLShort(bytes.length);user.put(bytes);
        }
        data.putByteArray(user.getBuffer());return data.getBuffer();
    }
    public static void main(String[] args)throws Exception{
        var field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);var unsafe=(Unsafe)field.get(null);
        field=Server.class.getDeclaredField("instance");field.setAccessible(true);field.set(null,unsafe.allocateInstance(Server.class));
        Block.init();RuntimeItems.init();Item.init();cn.nukkit.potion.Effect.init();cn.nukkit.item.enchantment.Enchantment.init();
        int count=0,success=0,errors=0;
        try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(Path.of(args[0]))))){
            out.writeInt(0x4e494633);out.writeInt(NukkitItemDecoderExporter.PROTOCOLS.length*4*3*22);out.writeInt(cn.nukkit.item.enchantment.Enchantment.getEnchantments().length);
            for(int protocol:NukkitItemDecoderExporter.PROTOCOLS)for(int format=0;format<4;format++)for(int shell:new int[]{248,261,0})for(int scenario=0;scenario<22;scenario++){
                GameVersion version=GameVersion.byProtocol(protocol,false);
                boolean descriptor=version.getProtocol()>=(format==1?ProtocolInfo.v1_26_30:format==2?ProtocolInfo.v1_26_40:ProtocolInfo.v1_26_20_26);
                Item original=Item.get(shell);int runtime=shell==0?0:original.getNetworkId(version);String name=original.getNamespaceId(version);
                int amount=new int[]{0,1,64,65535}[scenario%4],damage=new int[]{0,1,3,123,32767,32768,65535}[scenario%7],net=new int[]{0,42,-17}[scenario%3];
                CompoundTag tag=tag(scenario);byte[] bytes=wire(version,descriptor,runtime,amount,damage,net,tag);
                out.writeInt(protocol);out.writeInt(format);out.writeBoolean(descriptor);out.writeUTF(name);out.writeInt(runtime);out.writeInt(amount);out.writeInt(damage);out.writeInt(net);blob(out,nbt(tag));blob(out,bytes);
                try{
                    BinaryStream input=new BinaryStream(bytes);Item decoded=descriptor?input.getNetworkItemStackDescriptor(version):input.getSlot(version);
                    if(input.getOffset()!=bytes.length)throw new AssertionError("Unread original bytes");
                    out.writeBoolean(true);out.writeInt(decoded.getId());out.writeInt(decoded.getDamage());out.writeInt(decoded.getCount());out.writeInt(decoded.getStackNetId());out.writeBoolean(decoded.isNull());
                    String identifier=null;int network=Integer.MIN_VALUE;try{identifier=decoded.getNamespaceId(version);}catch(Exception ignored){}try{network=decoded.getNetworkId(version);}catch(Exception ignored){}
                    out.writeBoolean(identifier!=null);if(identifier!=null)out.writeUTF(identifier);out.writeInt(network);blob(out,decoded.getCompoundTag());
                    Item positive=decoded.clone();positive.setCount(1);NukkitPredictionItemsExporter.Traits.of(positive).write(out);success++;
                }catch(RuntimeException error){out.writeBoolean(false);out.writeUTF(error.getClass().getName());errors++;}
                count++;
            }
        }
        System.out.println("Actual native decoded wire cases="+count+" success="+success+" errors="+errors);
    }
}
