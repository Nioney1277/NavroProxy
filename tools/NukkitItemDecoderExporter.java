import cn.nukkit.*;
import cn.nukkit.block.*;
import cn.nukkit.item.*;
import cn.nukkit.network.protocol.ProtocolInfo;
import cn.nukkit.utils.Utils;
import sun.misc.Unsafe;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NukkitItemDecoderExporter {
    static final int[] PROTOCOLS={729,776,786,800,819,844,860,898,924,944,975,1001,2168,2169,2192,2193,2225};
    record Factory(Integer number,String name) implements Comparable<Factory>{public int compareTo(Factory b){return toString().compareTo(b.toString());}}
    record Binding(Factory factory,int fixedDamage){}
    record Names(int end,String name){}
    record NbtRun(int end,byte[] nbt){}
    record Run(int end,NukkitPredictionItemsExporter.Traits traits){}
    @SuppressWarnings("unchecked") static <T>T field(Object object,String name)throws Exception{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return(T)f.get(object);}
    static Factory select(RuntimeItemMapping map,RuntimeItemMapping.RuntimeEntry entry){
        String name=entry.getIdentifier();Integer id=null;
        if(!Item.isRegisteredStringItemIdentifier(name))try{var legacy=map.fromRuntime(entry.getRuntimeId());if(Utils.hasItemOrBlock(legacy.getLegacyId()))id=legacy.getLegacyId();}catch(IllegalArgumentException ignored){}
        return new Factory(id,id==null?name:null);
    }
    static void writeFactory(DataOutputStream out,Factory factory,int baseId)throws Exception{
                out.writeInt(baseId);out.writeBoolean(factory.number()!=null);if(factory.number()!=null)out.writeInt(factory.number());else out.writeUTF(factory.name());
                int[] damage=new int[65536];boolean identity=true,constant=true;boolean statefulDefault=false;byte[] defaultNbt=null;var nbtRuns=new ArrayList<NbtRun>();var names=new ArrayList<Names>();String previousName=null;boolean hadName=false;
                var rows=new ArrayList<Run>();NukkitPredictionItemsExporter.Traits previousTraits=null;Item prototype=factory.number()==null?Item.fromString(factory.name()):null;
                for(int meta=0;meta<=65535;meta++){
                    Item item;if(factory.number()!=null)item=Item.get(factory.number(),meta,1);else{prototype.setDamage(meta);item=prototype;}
                    byte[] current=item.getCompoundTag();
                    if(current.length!=0&&item.getId()==358&&item.getNamedTag().getTags().keySet().equals(Set.of("map_uuid"))){statefulDefault=true;current=new byte[0];}
                    if(defaultNbt==null)defaultNbt=current.clone();
                    else if(!Arrays.equals(defaultNbt,current)){nbtRuns.add(new NbtRun(meta-1,defaultNbt));defaultNbt=current.clone();}
                    damage[meta]=item.getDamage();identity&=damage[meta]==meta;constant&=damage[meta]==damage[0];
                    String name=item instanceof StringItem?item.getNamespaceId():null;
                    if(hadName&&!Objects.equals(previousName,name))names.add(new Names(meta-1,previousName));previousName=name;hadName=true;
                    if(baseId<0){var traits=NukkitPredictionItemsExporter.Traits.of(item);if(previousTraits!=null&&!previousTraits.equals(traits))rows.add(new Run(meta-1,previousTraits));previousTraits=traits;}
                }
                out.writeBoolean(statefulDefault);nbtRuns.add(new NbtRun(65535,defaultNbt));out.writeInt(nbtRuns.size());for(var run:nbtRuns){out.writeShort(run.end());out.writeInt(run.nbt().length);out.write(run.nbt());}
                if(identity)out.writeByte(0);else if(constant){out.writeByte(1);out.writeInt(damage[0]);}else{int differences=0;for(int meta=0;meta<damage.length;meta++)if(damage[meta]!=meta)differences++;
                    if(differences*6<damage.length*4){out.writeByte(3);out.writeInt(differences);for(int meta=0;meta<damage.length;meta++)if(damage[meta]!=meta){out.writeShort(meta);out.writeInt(damage[meta]);}}
                    else{out.writeByte(2);for(int value:damage)out.writeInt(value);}}
                names.add(new Names(65535,previousName));out.writeInt(names.size());for(var name:names){out.writeShort(name.end());out.writeBoolean(name.name()!=null);if(name.name()!=null)out.writeUTF(name.name());}
                if(baseId<0){rows.add(new Run(65535,previousTraits));var raw=new ByteArrayOutputStream();try(var row=new DataOutputStream(new GZIPOutputStream(raw))){row.writeInt(rows.size());for(var run:rows){row.writeShort(run.end());run.traits().write(row);}}out.writeInt(raw.size());out.write(raw.toByteArray());}
    }
    public static void main(String[] args)throws Exception{
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var u=(Unsafe)f.get(null);f=Server.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,u.allocateInstance(Server.class));
        Block.init();RuntimeItems.init();Item.init();cn.nukkit.potion.Effect.init();cn.nukkit.item.enchantment.Enchantment.init();
        var base=new TreeSet<Factory>();var bindings=new LinkedHashMap<Integer,Map<String,Binding>>();
        for(int protocol:PROTOCOLS){var map=RuntimeItems.getMapping(protocol);var names=new TreeMap<String,Binding>();
            for(var entry:map.getItemPaletteEntries()){
                Factory factory=select(map,entry);int damage=-1;
                if(factory.number()!=null){var legacy=map.fromRuntime(entry.getRuntimeId());if(legacy.isHasDamage())damage=legacy.getDamage();}
                base.add(factory);names.put(entry.getIdentifier(),new Binding(factory,damage));
            }
            bindings.put(protocol,names);
        }
        var all=new TreeSet<>(base);
        for(int id=0;id<Item.list.length;id++)if(Item.list[id]!=null)all.add(new Factory(id,null));
        for(int id=256;id<Block.MAX_BLOCK_ID;id++)if(Block.list[id]!=null)all.add(new Factory(255-id,null));
        for(String name:Item.NAMESPACED_ID_ITEM.keySet())all.add(new Factory(null,name));
        for(int protocol:PROTOCOLS){Map<Integer,RuntimeItemMapping.RuntimeEntry> forward=field(RuntimeItems.getMapping(protocol),"legacy2Runtime");for(int key:forward.keySet())all.add(new Factory((int)(short)(key>>16),null));}
        var baseIds=new HashMap<Factory,Integer>();int index=0;for(var factory:base)baseIds.put(factory,index++);
        var ids=new HashMap<Factory,Integer>();index=0;for(var factory:all)ids.put(factory,index++);
        var cf=Block.class.getDeclaredField("statePrototypes");cf.setAccessible(true);Object[] caches=(Object[])cf.get(null);
        Path destination=Path.of(args[0]),temporary=Path.of(args[0]+".tmp");
        try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary)))){
            out.writeInt(0x4e494434);out.writeInt(cn.nukkit.item.enchantment.Enchantment.getEnchantments().length);out.write(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(args[1]))));out.writeInt(base.size());out.writeInt(PROTOCOLS.length);
            for(int protocol:PROTOCOLS){
                var map=RuntimeItems.getMapping(protocol);int actual=GameVersion.byProtocol(protocol,false).getProtocol();
                out.writeInt(protocol);out.writeBoolean(actual>=ProtocolInfo.v1_26_20_26);out.writeBoolean(actual>=ProtocolInfo.v1_26_30);out.writeBoolean(actual>=ProtocolInfo.v1_26_40);
                var names=bindings.get(protocol);out.writeInt(names.size());for(var entry:names.entrySet()){out.writeUTF(entry.getKey());out.writeInt(ids.get(entry.getValue().factory()));out.writeInt(entry.getValue().fixedDamage());}
                Map<Integer,RuntimeItemMapping.RuntimeEntry> forward=field(map,"legacy2Runtime");out.writeInt(forward.size());for(int key:new TreeSet<>(forward.keySet())){out.writeInt(key);out.writeInt(forward.get(key).getRuntimeId());}
                Map<Integer,String> reverse=field(map,"runtimeId2Name");out.writeInt(reverse.size());for(int key:new TreeSet<>(reverse.keySet())){out.writeInt(key);out.writeUTF(reverse.get(key));}
                Map<String,Integer> named=field(map,"name2RuntimeId");out.writeInt(named.size());for(String name:new TreeSet<>(named.keySet())){out.writeUTF(name);out.writeInt(named.get(name));}
            }
            out.writeInt(all.size());index=0;long samples=0;int extras=0;
            int resumed=0;if(args.length>2){try(var prefix=new DataInputStream(Files.newInputStream(Path.of(args[2])))){resumed=prefix.readInt();prefix.transferTo(out);}System.out.println("Resumed exact constructor rows="+resumed);}

            for(var factory:all){
                if(index<resumed){index++;samples+=65536;if(!baseIds.containsKey(factory))extras++;continue;}
                int baseId=baseIds.getOrDefault(factory,-1);writeFactory(out,factory,baseId);samples+=65536;if(baseId<0)extras++;
                for(Object cache:caches)if(cache instanceof Map<?,?> map)map.clear();
                if(++index%64==0||index==all.size())System.out.println("Native decoder constructors "+index+"/"+all.size()+", metadata inputs="+samples);
            }
            System.out.println("base="+base.size()+" constructors="+all.size()+" extra="+extras+" metadata inputs="+samples);
        }
        Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);
    }
}
