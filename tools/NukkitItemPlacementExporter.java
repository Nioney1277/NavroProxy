import cn.nukkit.*;
import cn.nukkit.block.*;
import cn.nukkit.item.*;
import sun.misc.Unsafe;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NukkitItemPlacementExporter {
    private record Properties(boolean canPlace,boolean mapped,int blockId,int defaultId,int fullId) {
        void write(DataOutputStream out)throws IOException {out.writeBoolean(canPlace);out.writeBoolean(mapped);out.writeInt(blockId);out.writeInt(defaultId);out.writeInt(fullId);}
    }
    private record Run(int end,Properties value) { }
    private static final Map<Integer,int[]> defaults=new HashMap<>();
    private static Properties properties(Item item) {
        Block block=item.getBlockUnsafe();
        if(block==null)return new Properties(item.canBePlaced(),false,item.getBlockId(),0,0);
        int[] state=defaults.computeIfAbsent(block.getId(),id->{Block b=Block.get(id.intValue(),0);return new int[]{b.getId(),b.getFullId()};});
        return new Properties(item.canBePlaced(),true,item.getBlockId(),state[0],state[1]);
    }
    public static void main(String[] args)throws Exception {
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var unsafe=(Unsafe)f.get(null);
        f=Server.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,unsafe.allocateInstance(Server.class));
        Block.init();RuntimeItems.init();Item.init();
        f=Block.class.getDeclaredField("statePrototypes");f.setAccessible(true);Object[] caches=(Object[])f.get(null);
        var numbers=new TreeSet<Integer>();for(int id=0;id<Item.list.length;id++)if(Item.list[id]!=null)numbers.add(id);
        for(int id=256;id<Block.MAX_BLOCK_ID;id++)if(Block.list[id]!=null)numbers.add(255-id);
        var identifiers=new TreeSet<>(Item.NAMESPACED_ID_ITEM.keySet());identifiers.add("minecraft:air");
        var digest=MessageDigest.getInstance("SHA-256");var audit=new StringBuilder("factory\tkey\tnative canPlace,mapped,blockId,defaultId,defaultFullId by metadata\n");
        int entries=numbers.size()+identifiers.size();long states=0,ranges=0;
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])));
            var reference=new DataOutputStream(new FileOutputStream(args[1]));
            var hashed=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))) {
            out.writeInt(0x4E495031);out.writeInt(65536);out.writeInt(entries);
            reference.writeInt(0x4E495231);reference.writeInt(65536);reference.writeInt(entries);
            for(int index=0;index<entries;index++) {
                boolean numeric=index<numbers.size();int id=numeric?numbers.stream().skip(index).findFirst().orElseThrow():0;
                String name=numeric?"":identifiers.stream().skip(index-numbers.size()).findFirst().orElseThrow();
                out.writeBoolean(numeric);reference.writeBoolean(numeric);
                if(numeric){out.writeInt(id);reference.writeInt(id);}else{out.writeUTF(name);reference.writeUTF(name);}
                Item prototype=numeric?null:Item.fromString(name);var row=new ArrayList<Run>();Properties previous=null;
                for(int data=0;data<=65535;data++) {
                    Item item;
                    if(numeric)item=Item.get(id,data,1);
                    else{prototype.setDamage(data);item=prototype;}
                    Properties value=properties(item);hashed.writeInt(data);value.write(hashed);
                    if(previous!=null&&!previous.equals(value))row.add(new Run(data-1,previous));previous=value;states++;
                }
                row.add(new Run(65535,previous));out.writeInt(row.size());ranges+=row.size();
                audit.append(numeric?"numeric":"identifier-setDamage").append('\t').append(numeric?String.valueOf(id):name).append('\t');
                int start=0;for(var run:row) {
                    out.writeShort(run.end);run.value.write(out);
                    if(start>0)audit.append(';');audit.append(start).append('-').append(run.end).append('=').append(run.value);start=run.end+1;
                }
                audit.append('\n');reference.write(digest.digest());
                for(Object cache:caches)if(cache instanceof Map<?,?> map)map.clear();
                if((index+1)%32==0||index+1==entries)System.out.println("exported native item factories "+(index+1)+"/"+entries);
            }
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of(args[2]),audit);
        System.out.println("Numeric="+numbers.size()+" identifier="+identifiers.size()+" inputs="+states+" lossless ranges="+ranges);
    }
}
