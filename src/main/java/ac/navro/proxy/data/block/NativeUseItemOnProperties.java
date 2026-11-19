package ac.navro.proxy.data.block;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;


public final class NativeUseItemOnProperties {
    private static final Table TABLE=load();
    private NativeUseItemOnProperties() { }
    public record Properties(int flags,int bellAxis,int attachment,int vaultState) {
        public boolean cauldron(){return (flags&1)!=0;}
        public boolean candle(){return (flags&2)!=0;}
        public boolean litCandle(){return (flags&4)!=0;}
        public boolean lectern(){return (flags&8)!=0;}
        public boolean noteblock(){return (flags&16)!=0;}
        public boolean respawnAnchor(){return (flags&32)!=0;}
        public boolean anchorChargeBelowFour(){return (flags&64)!=0;}
        public boolean sign(){return (flags&128)!=0;}
        public boolean tnt(){return (flags&256)!=0;}
        public boolean undamagedCake(){return (flags&512)!=0;}
        public boolean berryDamageNotThree(){return (flags&1024)!=0;}
        public boolean bell(){return (flags&2048)!=0;}
    }
    private record Row(int[] ends,Properties[] values) { }
    private record Table(Row[] rows,int customBlockStart,Properties unknown) { }
    public static Properties forState(NetworkBlockState state){return forBlock(state.blockId(),state.blockData());}
    public static Properties forBlock(int id,int metadata) {
        if(metadata<0||metadata>BlockStateEncoding.DATA_MASK)throw new IllegalArgumentException("Expected native 13-bit block metadata");
        if(id>=TABLE.customBlockStart)throw new IllegalArgumentException("Custom block properties require the server's registered native implementation");
        if(id<0||id>=TABLE.rows.length)return TABLE.unknown;Row row=TABLE.rows[id];int i=Arrays.binarySearch(row.ends,metadata);return row.values[i>=0?i:-i-1];
    }
    private static Properties read(DataInputStream in)throws IOException {
        int flags=in.readUnsignedShort(),axis=in.readByte(),attachment=in.readByte(),vault=in.readUnsignedByte();
        if((flags&~4095)!=0||axis < -1||axis>2||attachment < -1||attachment>3||vault>2)throw new IOException("Invalid native item-on-block properties");return new Properties(flags,axis,attachment,vault);
    }
    private static Table load() {
        var resource=NativeUseItemOnProperties.class.getResourceAsStream("/navro/block-use-item-on.bin.gz");
        if(resource==null)throw new IllegalStateException("Missing native item-on-block properties");
        try(var in=new DataInputStream(new GZIPInputStream(resource))) {
            if(in.readInt()!=0x4e554f31||in.readInt()!=BlockStateEncoding.DATA_BITS)throw new IOException("Incompatible native item-on-block properties");
            int count=in.readInt(),custom=in.readInt();Properties unknown=read(in);if(count!=2048||custom!=10000)throw new IOException("Incompatible native block registry");Row[] rows=new Row[count];
            for(int id=0;id<count;id++) {
                int size=in.readInt();if(size<1||size>BlockStateEncoding.DATA_MASK+1)throw new IOException("Invalid item-on-block row length");int[] ends=new int[size];Properties[] values=new Properties[size];
                for(int i=0;i<size;i++){ends[i]=in.readUnsignedShort();values[i]=read(in);if(ends[i]>BlockStateEncoding.DATA_MASK||i>0&&ends[i]<=ends[i-1])throw new IOException("Invalid item-on-block metadata range");}
                if(ends[size-1]!=BlockStateEncoding.DATA_MASK)throw new IOException("Incomplete item-on-block metadata coverage");rows[id]=new Row(ends,values);
            }
            if(in.read()!=-1)throw new IOException("Trailing native item-on-block properties");return new Table(rows,custom,unknown);
        }catch(IOException e){throw new IllegalStateException("Cannot load native item-on-block properties",e);}
    }
}
