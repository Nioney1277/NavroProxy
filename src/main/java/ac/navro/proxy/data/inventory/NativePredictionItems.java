package ac.navro.proxy.data.inventory;

import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;


public final class NativePredictionItems {
    public record Traits(int nativeId,boolean positiveNull,boolean canRelease,int duration,boolean food,String durationError,String foodError){}
    private record Binding(int factory,int fixedDamage){}
    private record Row(int[] ends,int[] offsets,byte[] data){}
    private record Key(int factory,int damage){}
    private static final Map<Key,Traits> CACHE=new LinkedHashMap<>(256,.75F,true){
        @Override protected boolean removeEldestEntry(Map.Entry<Key,Traits> entry){return size()>2048;}
    };
    private record Table(Map<Integer,Map<String,Binding>> protocols,Row[] factories){}
    private static final Table TABLE=load();
    private NativePredictionItems(){}
    public static void initialize(){if(TABLE.factories.length==0)throw new IllegalStateException("Empty prediction item traits");NativeItemDecoder.initialize();}
    public static Traits resolve(int protocol,ItemData item){return NativeItemDecoder.traits(protocol,item);}
    static int factoryCount(){return TABLE.factories.length;}
    static Traits factoryTraits(int factory,int damage){
        if(damage<0 || damage>65535)throw new IllegalArgumentException("Expected decoded native item damage");
        Row row=TABLE.factories[factory];int index=Arrays.binarySearch(row.ends,damage);
        int selected=index>=0?index:-index-1;Key key=new Key(factory,selected);
        synchronized(CACHE){
            Traits value=CACHE.get(key);if(value!=null)return value;
            try(var in=new DataInputStream(new ByteArrayInputStream(row.data,row.offsets[selected],row.data.length-row.offsets[selected]))){
                value=readTraits(in);CACHE.put(key,value);return value;
            }catch(IOException e){throw new IllegalStateException("Invalid detached item traits",e);}
        }
    }
    static Traits readTraits(DataInputStream in)throws IOException{
        return new Traits(in.readInt(),in.readBoolean(),in.readBoolean(),in.readInt(),in.readBoolean(),in.readUTF(),in.readUTF());
    }
    private static Table load(){
        var resource=NativePredictionItems.class.getResourceAsStream("/navro/prediction-items.bin.gz");
        if(resource==null)throw new IllegalStateException("Missing exact prediction item properties");
        try(var in=new DataInputStream(new GZIPInputStream(resource))){
            if(in.readInt()!=0x4e505531)throw new IOException("Invalid prediction item properties");
            int count=in.readInt();if(count<1 || count>100)throw new IOException("Invalid protocol count");
            var protocols=new HashMap<Integer,Map<String,Binding>>();
            for(int i=0;i<count;i++){
                int protocol=in.readInt(),size=in.readInt();if(size<1 || size>100000)throw new IOException("Invalid item count");
                var names=new HashMap<String,Binding>();for(int j=0;j<size;j++)names.put(in.readUTF(),new Binding(in.readInt(),in.readInt()));
                protocols.put(protocol,Map.copyOf(names));
            }
            int size=in.readInt();if(size<1 || size>100000)throw new IOException("Invalid factory count");Row[] rows=new Row[size];
            for(int i=0;i<size;i++){
                int length=in.readInt();if(length<1 || length>65536)throw new IOException("Invalid metadata range count");
                int[] ends=new int[length],offsets=new int[length];var data=new ByteArrayOutputStream();var bytes=new DataOutputStream(data);
                for(int j=0;j<length;j++){
                    ends[j]=in.readUnsignedShort();if(j>0&&ends[j]<=ends[j-1])throw new IOException("Invalid metadata range");
                    offsets[j]=data.size();bytes.write(in.readNBytes(11));
                    for(int error=0;error<2;error++){int utfLength=in.readUnsignedShort();bytes.writeShort(utfLength);bytes.write(in.readNBytes(utfLength));}
                }
                if(ends[length-1]!=65535)throw new IOException("Incomplete native item metadata");rows[i]=new Row(ends,offsets,data.toByteArray());
            }
            for(var names:protocols.values())for(var binding:names.values())if(binding.factory()<0||binding.factory()>=rows.length)throw new IOException("Invalid factory binding");
            if(in.read()!=-1)throw new IOException("Trailing prediction item data");return new Table(Map.copyOf(protocols),rows);
        }catch(IOException e){throw new IllegalStateException("Cannot load exact prediction item properties",e);}
    }
}
