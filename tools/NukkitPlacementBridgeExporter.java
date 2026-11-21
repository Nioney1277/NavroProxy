import ac.navro.proxy.data.inventory.NativeItemDecoder;
import cn.nukkit.Server;
import cn.nukkit.block.Block;
import cn.nukkit.item.*;
import sun.misc.Unsafe;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;


public final class NukkitPlacementBridgeExporter {
    record Key(Integer number,String name) { }
    record Properties(boolean place,boolean mapped,int block,int defaultId,int full) {
        static Properties of(Item item) {
            Block block=item.getBlockUnsafe();
            Block state=block==null?null:Block.get(block.getId(),0);
            return new Properties(item.canBePlaced(),block!=null,item.getBlockId(),state==null?0:state.getId(),state==null?0:state.getFullId());
        }
        void write(DataOutputStream out)throws IOException {out.writeBoolean(place);out.writeBoolean(mapped);out.writeInt(block);out.writeInt(defaultId);out.writeInt(full);}
    }
    record Run(int end,Properties value) { }
    static Object value(Object o,String name)throws Exception {var m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(o);}
    static Set<Key> baseline()throws Exception {
        var result=new HashSet<Key>();
        try(var in=new DataInputStream(new GZIPInputStream(NukkitPlacementBridgeExporter.class.getResourceAsStream("/navro/item-placement.bin.gz")))) {
            if(in.readInt()!=0x4e495031||in.readInt()!=65536)throw new IOException("Invalid baseline placement table");
            int count=in.readInt();
            for(int i=0;i<count;i++) {
                result.add(in.readBoolean()?new Key(in.readInt(),null):new Key(null,in.readUTF()));
                int ranges=in.readInt();in.skipNBytes(Math.multiplyExact(ranges,16L));
            }
            if(in.read()!=-1)throw new IOException("Trailing baseline placement bytes");
        }return result;
    }
    public static void main(String[] args)throws Exception {
        var field=NativeItemDecoder.class.getDeclaredField("TABLE");field.setAccessible(true);
        Object[] factories=(Object[])value(field.get(null),"factories");Set<Key> covered=baseline();var missing=new ArrayList<Key>();
        for(Object factory:factories){var key=new Key((Integer)value(factory,"number"),(String)value(factory,"name"));if(!covered.contains(key))missing.add(key);}
        field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);var unsafe=(Unsafe)field.get(null);
        field=Server.class.getDeclaredField("instance");field.setAccessible(true);field.set(null,unsafe.allocateInstance(Server.class));
        Block.init();RuntimeItems.init();Item.init();
        field=Block.class.getDeclaredField("statePrototypes");field.setAccessible(true);Object[] caches=(Object[])field.get(null);
        Path target=Path.of(args[0]),temporary=target.resolveSibling(target.getFileName()+".tmp");
        int done=0;long states=0,totalRanges=0;
        var digest=MessageDigest.getInstance("SHA-256");
        try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary)));
            var reference=new DataOutputStream(Files.newOutputStream(Path.of(args[1])));
            var hashed=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))) {
            out.writeInt(0x4e495032);out.writeInt(65536);out.writeInt(missing.size());
            reference.writeInt(0x4e495232);reference.writeInt(65536);reference.writeInt(missing.size());
            for(Key key:missing) {
                out.writeBoolean(key.number!=null);if(key.number!=null)out.writeInt(key.number);else out.writeUTF(key.name);
                reference.writeBoolean(key.number!=null);if(key.number!=null)reference.writeInt(key.number);else reference.writeUTF(key.name);
                Item prototype=key.number==null?Item.fromString(key.name):null;var runs=new ArrayList<Run>();Properties previous=null;
                for(int damage=0;damage<=65535;damage++) {
                    Item item;if(key.number!=null)item=Item.get(key.number,damage,1);else{prototype.setDamage(damage);item=prototype;}
                    Properties properties=Properties.of(item);
                    hashed.writeInt(damage);properties.write(hashed);
                    if(previous!=null&&!previous.equals(properties))runs.add(new Run(damage-1,previous));previous=properties;states++;
                }
                runs.add(new Run(65535,previous));out.writeInt(runs.size());totalRanges+=runs.size();
                for(Run run:runs){out.writeShort(run.end);run.value.write(out);}
                reference.write(digest.digest());
                for(Object cache:caches)if(cache instanceof Map<?,?> map)map.clear();
                if(++done%32==0||done==missing.size())System.out.println("Placement decoder bridge "+done+"/"+missing.size()+" constructors, "+states+" native inputs");
            }
        }
        Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Decoder constructors="+factories.length+" extra="+missing.size()+" inputs="+states+" lossless ranges="+totalRanges);
    }
}
