import cn.nukkit.*;
import cn.nukkit.block.Block;
import cn.nukkit.item.*;
import cn.nukkit.utils.Utils;
import sun.misc.Unsafe;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NukkitItemBindingsExporter {
    record Sample(int protocol,String name,int runtime,Integer numeric){}
    static Set<NukkitItemDecoderExporter.Factory> baseFactories(Path path)throws Exception{
        var factories=new HashSet<NukkitItemDecoderExporter.Factory>();
        try(var in=new DataInputStream(new java.util.zip.GZIPInputStream(Files.newInputStream(path)))){
            if(in.readInt()!=0x4e494434)throw new IOException("Invalid base constructor directory");in.skipNBytes(40);
            int protocols=in.readInt();for(int p=0;p<protocols;p++){
                in.skipNBytes(7);for(int n=in.readInt();n>0;n--){in.readUTF();in.skipNBytes(8);}in.skipNBytes((long)in.readInt()*8);
                for(int n=in.readInt();n>0;n--){in.skipNBytes(4);in.readUTF();}for(int n=in.readInt();n>0;n--){in.readUTF();in.skipNBytes(4);}
            }
            int count=in.readInt();for(int i=0;i<count;i++){
                int base=in.readInt();factories.add(in.readBoolean()?new NukkitItemDecoderExporter.Factory(in.readInt(),null):new NukkitItemDecoderExporter.Factory(null,in.readUTF()));in.readBoolean();
                for(int n=in.readInt();n>0;n--){in.skipNBytes(2);in.skipNBytes(in.readInt());}
                int mode=in.readUnsignedByte();if(mode==1)in.skipNBytes(4);else if(mode==2)in.skipNBytes(65536L*4);else if(mode==3)in.skipNBytes((long)in.readInt()*6);else if(mode!=0)throw new IOException("Invalid metadata mode");
                for(int n=in.readInt();n>0;n--){in.skipNBytes(2);if(in.readBoolean())in.readUTF();}if(base<0)in.skipNBytes(in.readInt());
            }
            if(in.read()!=-1)throw new IOException("Trailing constructor directory");
        }
        return factories;
    }
    public static void main(String[] args)throws Exception{
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var unsafe=(Unsafe)f.get(null);f=Server.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,unsafe.allocateInstance(Server.class));
        Block.init();RuntimeItems.init();Item.init();cn.nukkit.potion.Effect.init();cn.nukkit.item.enchantment.Enchantment.init();int total=0;var samples=new ArrayList<Sample>();var known=baseFactories(Path.of(args[1]));var extras=new TreeSet<NukkitItemDecoderExporter.Factory>();
        Path destination=Path.of(args[0]),temporary=Path.of(args[0]+".tmp");
        try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary)))){
            out.writeInt(0x4e494232);out.write(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(args[1]))));
            out.writeInt(NukkitItemDecoderExporter.PROTOCOLS.length);
            for(int protocol:NukkitItemDecoderExporter.PROTOCOLS){
                var mapping=RuntimeItems.getMapping(protocol);var palette=new HashSet<String>();for(var entry:mapping.getItemPaletteEntries())palette.add(entry.getIdentifier());
                Map<Integer,String> reverse=NukkitItemDecoderExporter.field(mapping,"runtimeId2Name");var missing=new TreeMap<String,Integer>();
                for(var entry:reverse.entrySet())if(!palette.contains(entry.getValue())){
                    Integer old=missing.put(entry.getValue(),entry.getKey());if(old!=null&&!old.equals(entry.getKey()))throw new AssertionError("Ambiguous native runtime name: "+entry.getValue());
                }
                out.writeInt(protocol);out.writeInt(missing.size());
                for(var entry:missing.entrySet()){
                    Integer id=null;int fixed=-1;
                    if(!Item.isRegisteredStringItemIdentifier(entry.getKey()))try{var legacy=mapping.fromRuntime(entry.getValue());if(Utils.hasItemOrBlock(legacy.getLegacyId())){id=legacy.getLegacyId();if(legacy.isHasDamage())fixed=legacy.getDamage();}}catch(IllegalArgumentException ignored){}
                    samples.add(new Sample(protocol,entry.getKey(),entry.getValue(),id));
                    var factory=new NukkitItemDecoderExporter.Factory(id,id==null?entry.getKey():null);if(!known.contains(factory))extras.add(factory);
                    out.writeUTF(entry.getKey());out.writeBoolean(id!=null);if(id!=null)out.writeInt(id);else out.writeUTF(entry.getKey());out.writeInt(fixed);
                }
                total+=missing.size();System.out.println("Supplementary native bindings protocol="+protocol+" entries="+missing.size());
            }
            out.writeInt(extras.size());System.out.println("Additional exact constructors="+extras.size());
            var cacheField=Block.class.getDeclaredField("statePrototypes");cacheField.setAccessible(true);Object[] caches=(Object[])cacheField.get(null);
            int index=0;for(var factory:extras){NukkitItemDecoderExporter.writeFactory(out,factory,-1);for(Object cache:caches)if(cache instanceof Map<?,?> map)map.clear();if(++index%32==0||index==extras.size())System.out.println("Additional constructor metadata rows="+index+"/"+extras.size());}
        }
        Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);System.out.println("Supplementary native runtime bindings="+total);
        if(args.length>2)try(var reference=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(Path.of(args[2]))))){
            reference.writeInt(0x4e494252);reference.writeInt(samples.size());
            for(var sample:samples){
                var version=GameVersion.byProtocol(sample.protocol(),false);boolean descriptor=version.getProtocol()>=cn.nukkit.network.protocol.ProtocolInfo.v1_26_20_26;
                var writer=new cn.nukkit.utils.BinaryStream();if(descriptor)writer.putLShort(sample.runtime());else writer.putVarInt(sample.runtime());
                if(descriptor||sample.runtime()!=0){
                    writer.putLShort(1);writer.putUnsignedVarInt(3);writer.putBoolean(false);writer.putVarInt(0);
                    var user=new cn.nukkit.utils.BinaryStream();user.putLShort(-1);user.putByte((byte)1);user.put(cn.nukkit.nbt.NBTIO.write(new cn.nukkit.nbt.tag.CompoundTag().putInt("marker",1),java.nio.ByteOrder.LITTLE_ENDIAN));user.putLInt(0);user.putLInt(0);if(Objects.equals(sample.numeric(),513))user.putLLong(0);writer.putByteArray(user.getBuffer());
                }
                var reader=new cn.nukkit.utils.BinaryStream(writer.getBuffer());Item decoded=descriptor?reader.getNetworkItemStackDescriptor(version):reader.getSlot(version);
                if(reader.getOffset()!=writer.getBuffer().length)throw new AssertionError("Unread native binding bytes: "+sample.name());
                reference.writeInt(sample.protocol());reference.writeUTF(sample.name());reference.writeInt(sample.runtime());
                reference.writeInt(decoded.getDamage());reference.writeInt(decoded.getCount());String name=null;int runtime=Integer.MIN_VALUE;
                try{name=decoded.getNamespaceId(version);}catch(Exception ignored){}try{runtime=decoded.getNetworkId(version);}catch(Exception ignored){}
                reference.writeBoolean(name!=null);if(name!=null)reference.writeUTF(name);reference.writeInt(runtime);NukkitPredictionItemsExporter.Traits.of(decoded).write(reference);
            }
            System.out.println("Actual native supplementary binding decodes="+samples.size());
        }
    }
}
