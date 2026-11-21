package ac.navro.proxy.data.block;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Direction;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;




public final class NativeFluidBlockProperties {
    public static final int WATER=1, LAVA=2, PASS_THROUGH=4, STURDY=8, FENCE=16,
            PANE=32, STAIRS=64, WALL=128, CHORUS=256, CHORUS_FLOWER=512,
            CONNECTION_EXCEPTION=1024, MAPPED_FENCE=2048, MAPPED_WALL=4096,
            FENCE_GATE=8192, SHULKER=16384, BAMBOO_SAPLING=32768;
    private record Key(String identifier, org.cloudburstmc.nbt.NbtMap states) { }
    private static final Map<Integer,Map<Key,Properties>> TABLES=new HashMap<>();
    private NativeFluidBlockProperties() { }
    public record Properties(int id,int damage,int flags,Direction gateFace,List<Box> generic,List<Box> wall,
                             int bedrockKind,List<Box> bedrockShape) {
        public boolean has(int flag){return (flags&flag)!=0;}
        public int getId(){return id;}
        public int getDamage(){return damage;}
    }
    private static Key key(NetworkBlockState state){return new Key(state.identifier(),state.stateTag().getCompound("states"));}
    public static Properties forState(int protocol,NetworkBlockState state){
        if(state==null)return null;
        return table(protocol).get(key(state));
    }
    private static synchronized Map<Key,Properties> table(int protocol){return TABLES.computeIfAbsent(protocol,NativeFluidBlockProperties::load);}
    private static Map<Key,Properties> load(int protocol){


        NetworkBlockStateRegistry.paletteProtocol(protocol);
        var result=new HashMap<Key,Properties>();
        var resource=NativeFluidBlockProperties.class.getResourceAsStream("/navro/fluid-blocks-"+protocol+".bin.gz");
        if(resource==null)throw new IllegalStateException("Missing original fluid block properties: "+protocol);
        try(var in=new DataInputStream(new GZIPInputStream(resource))){
            if(in.readInt()!=0x4e464232)throw new IOException("Invalid original fluid block table");
            int count=in.readInt();
            for(int i=0;i<count;i++){
                int length=in.readInt();
                if(length<=0 || length>65536)throw new IOException("Invalid fluid state identity length");
                org.cloudburstmc.nbt.NbtMap identity;
                try(var reader=org.cloudburstmc.nbt.NbtUtils.createReader(new ByteArrayInputStream(in.readNBytes(length)))){
                    identity=(org.cloudburstmc.nbt.NbtMap)reader.readTag();
                }
                int id=in.readInt(),damage=in.readInt(),flags=in.readInt(),face=in.readByte();
                var row=new Properties(id,damage,flags,face<0?null:Direction.values()[face],boxes(in),boxes(in),
                        in.readUnsignedByte(),boxes(in));


                result.putIfAbsent(new Key(identity.getString("name"),identity.getCompound("states")),row);
            }
            if(in.read()!=-1)throw new IOException("Trailing fluid block data");
        }catch(IOException e){throw new IllegalStateException("Cannot load original fluid block predicates",e);}
        return Map.copyOf(result);
    }
    private static List<Box> boxes(DataInputStream in)throws IOException{
        int count=in.readUnsignedShort();var result=new ArrayList<Box>(count);
        for(int i=0;i<count;i++)result.add(new Box(in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat()));
        return List.copyOf(result);
    }
}
