package ac.navro.proxy.data.inventory;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;





public final class NativeItemPlacementProperties {
    public record Properties(boolean canBePlaced, boolean hasMappedBlock, int blockId,
                             int defaultBlockId, int defaultLegacyFullId) { }
    private record Row(int[] ends, Properties[] values) { }
    private record Table(Map<Integer, Row> numeric, Map<String, Row> named) { }
    private static final Table TABLE=load();
    private NativeItemPlacementProperties() { }

    public static Set<Integer> numericIds() { return TABLE.numeric.keySet(); }
    public static Set<String> identifiers() { return TABLE.named.keySet(); }
    public static Properties forNumeric(int nativeItemId, int constructorDamage) {
        return resolve(TABLE.numeric.get(nativeItemId), constructorDamage);
    }
    public static Properties forIdentifier(String identifier, int setterDamage) {
        String normalized=identifier.trim().replace(' ','_').toLowerCase(Locale.ROOT);
        if(!normalized.contains(":"))normalized="minecraft:"+normalized;
        return resolve(TABLE.named.get(normalized), setterDamage);
    }
    private static Properties resolve(Row row, int damage) {
        if(damage<0||damage>65535)throw new IllegalArgumentException("Expected an explicitly decoded 16-bit native item damage");
        if(row==null)throw new IllegalArgumentException("Native item factory is not exported; its registered implementation is required");
        int index=Arrays.binarySearch(row.ends,damage);return row.values[index>=0?index:-index-1];
    }
    private static boolean bool(DataInputStream in)throws IOException {
        int value=in.readUnsignedByte();if(value>1)throw new IOException("Invalid native item property flag");return value!=0;
    }
    private static Row row(DataInputStream in)throws IOException {
        int count=in.readInt();if(count<1||count>65536)throw new IOException("Invalid native item property row length");
        int[] ends=new int[count];Properties[] values=new Properties[count];
        for(int i=0;i<count;i++) {
            ends[i]=in.readUnsignedShort();boolean canPlace=bool(in),mapped=bool(in);
            if(i>0&&ends[i]<=ends[i-1]||canPlace&&!mapped)throw new IOException("Invalid native item property range");
            values[i]=new Properties(canPlace,mapped,in.readInt(),in.readInt(),in.readInt());
        }
        if(ends[count-1]!=65535)throw new IOException("Incomplete native item damage coverage");return new Row(ends,values);
    }
    private static Table load() {
        var resource=NativeItemPlacementProperties.class.getResourceAsStream("/navro/item-placement.bin.gz");
        if(resource==null)throw new IllegalStateException("Missing native item placement properties");
        try(var in=new DataInputStream(new GZIPInputStream(resource))) {
            if(in.readInt()!=0x4E495031||in.readInt()!=65536)throw new IOException("Incompatible native item placement properties");
            int count=in.readInt();if(count<1||count>100000)throw new IOException("Invalid native item factory count");
            var numeric=new HashMap<Integer,Row>();var named=new HashMap<String,Row>();
            for(int i=0;i<count;i++) {
                if(bool(in)){int id=in.readInt();if(numeric.put(id,row(in))!=null)throw new IOException("Duplicate native numeric item factory");}
                else{String id=in.readUTF();if(named.put(id,row(in))!=null)throw new IOException("Duplicate native named item factory");}
            }
            if(in.read()!=-1)throw new IOException("Trailing native item placement properties");
            var extra=NativeItemPlacementProperties.class.getResourceAsStream("/navro/item-placement-decoder-extra.bin.gz");
            if(extra==null)throw new IOException("Missing native decoder placement constructors");
            try(var added=new DataInputStream(new GZIPInputStream(extra))) {
                if(added.readInt()!=0x4E495032||added.readInt()!=65536)throw new IOException("Incompatible extra placement properties");
                int extraCount=added.readInt();if(extraCount<1||extraCount>100000)throw new IOException("Invalid extra factory count");
                for(int i=0;i<extraCount;i++) {
                    if(bool(added)){int id=added.readInt();if(numeric.putIfAbsent(id,row(added))!=null)throw new IOException("Duplicate extra numeric factory");}
                    else{String id=added.readUTF();if(named.putIfAbsent(id,row(added))!=null)throw new IOException("Duplicate extra named factory");}
                }
                if(added.read()!=-1)throw new IOException("Trailing extra placement properties");
            }
            return new Table(Map.copyOf(numeric),Map.copyOf(named));
        }catch(IOException error){throw new IllegalStateException("Cannot load native item placement properties",error);}
    }
}
