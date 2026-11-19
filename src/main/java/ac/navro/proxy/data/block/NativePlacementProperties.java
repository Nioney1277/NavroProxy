package ac.navro.proxy.data.block;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;


public final class NativePlacementProperties {
    private static final Table TABLE=load();
    private NativePlacementProperties() { }
    private record Row(int[] ends,boolean[] values) { }
    private record Table(Row[] rows,int customBlockStart,boolean unknown) { }

    public static boolean canBeReplaced(NetworkBlockState state) {
        return canBeReplaced(state.blockId(),state.blockData());
    }
    public static boolean canBeReplaced(int blockId,int metadata) {
        if(metadata<0||metadata>BlockStateEncoding.DATA_MASK)
            throw new IllegalArgumentException("Expected native 13-bit block metadata");
        if(blockId>=TABLE.customBlockStart)
            throw new IllegalArgumentException("Custom block properties require the server's registered native implementation");
        if(blockId<0||blockId>=TABLE.rows.length)return TABLE.unknown;
        Row row=TABLE.rows[blockId];int index=Arrays.binarySearch(row.ends,metadata);
        return row.values[index>=0?index:-index-1];
    }
    private static boolean readBoolean(DataInputStream in)throws IOException {
        int value=in.readUnsignedByte();if(value>1)throw new IOException("Invalid native replacement flag");return value!=0;
    }
    private static Table load() {
        var resource=NativePlacementProperties.class.getResourceAsStream("/navro/block-replaceability.bin.gz");
        if(resource==null)throw new IllegalStateException("Missing native replacement properties");
        try(var in=new DataInputStream(new GZIPInputStream(resource))) {
            if(in.readInt()!=0x4E525031||in.readInt()!=BlockStateEncoding.DATA_BITS)
                throw new IOException("Incompatible native replacement properties");
            int count=in.readInt(),custom=in.readInt();boolean unknown=readBoolean(in);
            if(count!=2048||custom!=10000)throw new IOException("Incompatible native block registry");
            Row[] rows=new Row[count];
            for(int id=0;id<count;id++) {
                int size=in.readInt();if(size<1||size>BlockStateEncoding.DATA_MASK+1)throw new IOException("Invalid replacement row length");
                int[] ends=new int[size];boolean[] values=new boolean[size];
                for(int i=0;i<size;i++) {
                    ends[i]=in.readUnsignedShort();values[i]=readBoolean(in);
                    if(ends[i]>BlockStateEncoding.DATA_MASK||i>0&&ends[i]<=ends[i-1])throw new IOException("Invalid replacement metadata range");
                }
                if(ends[size-1]!=BlockStateEncoding.DATA_MASK)throw new IOException("Incomplete replacement metadata coverage");
                rows[id]=new Row(ends,values);
            }
            if(in.read()!=-1)throw new IOException("Trailing replacement properties");
            return new Table(rows,custom,unknown);
        }catch(IOException error){throw new IllegalStateException("Cannot load native replacement properties",error);}
    }
}
