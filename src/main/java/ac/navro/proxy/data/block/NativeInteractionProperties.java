package ac.navro.proxy.data.block;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;


public final class NativeInteractionProperties {
    private static final Table TABLE=load();
    private NativeInteractionProperties() { }
    public record Properties(int flags) {
        public boolean water(){return (flags&1)!=0;}
        public boolean lava(){return (flags&2)!=0;}
        public boolean door(){return (flags&4)!=0;}
        public boolean trapdoor(){return (flags&8)!=0;}
        public boolean fenceGate(){return (flags&16)!=0;}
        public boolean canUseWithoutItem(){return (flags&32)!=0;}
    }
    private record Row(int[] ends,Properties[] values) { }
    private record Table(Row[] rows,int customBlockStart,Properties unknown) { }
    public static Properties forState(NetworkBlockState state) {return forBlock(state.blockId(),state.blockData());}
    public static Properties forBlock(int blockId,int metadata) {
        if(metadata<0||metadata>BlockStateEncoding.DATA_MASK)throw new IllegalArgumentException("Expected native 13-bit block metadata");
        if(blockId>=TABLE.customBlockStart)throw new IllegalArgumentException("Custom block properties require the server's registered native implementation");
        if(blockId<0||blockId>=TABLE.rows.length)return TABLE.unknown;
        Row row=TABLE.rows[blockId];int index=Arrays.binarySearch(row.ends,metadata);return row.values[index>=0?index:-index-1];
    }
    private static Properties read(DataInputStream in)throws IOException {
        int flags=in.readUnsignedByte();if((flags&~63)!=0)throw new IOException("Invalid native interaction flags");return new Properties(flags);
    }
    private static Table load() {
        var resource=NativeInteractionProperties.class.getResourceAsStream("/navro/block-interactions.bin.gz");
        if(resource==null)throw new IllegalStateException("Missing native interaction properties");
        try(var in=new DataInputStream(new GZIPInputStream(resource))) {
            if(in.readInt()!=0x4e495031||in.readInt()!=BlockStateEncoding.DATA_BITS)throw new IOException("Incompatible native interaction properties");
            int count=in.readInt(),custom=in.readInt();Properties unknown=read(in);
            if(count!=2048||custom!=10000)throw new IOException("Incompatible native block registry");Row[] rows=new Row[count];
            for(int id=0;id<count;id++) {
                int size=in.readInt();if(size<1||size>BlockStateEncoding.DATA_MASK+1)throw new IOException("Invalid interaction row length");
                int[] ends=new int[size];Properties[] values=new Properties[size];
                for(int i=0;i<size;i++) {
                    ends[i]=in.readUnsignedShort();values[i]=read(in);
                    if(ends[i]>BlockStateEncoding.DATA_MASK||i>0&&ends[i]<=ends[i-1])throw new IOException("Invalid interaction metadata range");
                }
                if(ends[size-1]!=BlockStateEncoding.DATA_MASK)throw new IOException("Incomplete interaction metadata coverage");rows[id]=new Row(ends,values);
            }
            if(in.read()!=-1)throw new IOException("Trailing native interaction properties");return new Table(rows,custom,unknown);
        }catch(IOException e){throw new IllegalStateException("Cannot load native interaction properties",e);}
    }
}
