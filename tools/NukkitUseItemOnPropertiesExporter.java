import cn.nukkit.block.*;
import cn.nukkit.block.custom.CustomBlockManager;
import cn.nukkit.math.BlockFace;
import ac.navro.anticheat.validator.inventory.ItemTransactionValidator;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NukkitUseItemOnPropertiesExporter {
    static java.lang.reflect.Method isSign;
    record Properties(int flags,int bellAxis,int attachment,int vaultState) {
        void write(DataOutputStream out)throws IOException{out.writeShort(flags);out.writeByte(bellAxis);out.writeByte(attachment);out.writeByte(vaultState);}
    }
    static Properties properties(Block block)throws Exception {
        int id=block.getId();boolean candle=block instanceof BlockCandle,anchor=block instanceof BlockRespawnAnchor;
        int flags=(block instanceof BlockCauldron?1:0)|(candle?2:0)|(candle&&((BlockCandle)block).isLit()?4:0)
                |(block instanceof BlockLectern?8:0)|(block instanceof BlockNoteblock?16:0)|(anchor?32:0)
                |(anchor&&((BlockRespawnAnchor)block).getCharge()<4?64:0)|((boolean)isSign.invoke(null,block)?128:0)
                |(block instanceof BlockTNT?256:0)|(id==BlockID.CAKE_BLOCK&&block.getDamage()==0?512:0)
                |(id==BlockID.SWEET_BERRY_BUSH&&block.getDamage()!=3?1024:0)|(block instanceof BlockBell?2048:0);
        int axis=-1,attachment=-1,vault=0;
        if(block instanceof BlockBell bell){axis=switch(bell.getBlockFace().getAxis()){case X->0;case Y->1;case Z->2;};attachment=bell.getAttachmentType();}
        if(id==BlockID.VAULT)try{Object value=block.getClass().getMethod("getVaultState").invoke(block);if(value!=null)vault="active".equalsIgnoreCase(value.toString())?1:2;}catch(Exception ignored){}
        return new Properties(flags,axis,attachment,vault);
    }
    record Run(int end,Properties value){}
    static long checksum(long hash,Properties p){for(int value:new int[]{p.flags,p.bellAxis,p.attachment,p.vaultState})hash=(hash^value)*0x100000001b3L;return hash;}
    public static void main(String[] args)throws Exception {
        Block.init();isSign=ItemTransactionValidator.class.getDeclaredMethod("isSign",Block.class);isSign.setAccessible(true);
        var field=Block.class.getDeclaredField("statePrototypes");field.setAccessible(true);Object[] caches=(Object[])field.get(null);
        var audit=new StringBuilder("id\tnative_class\tmetadata_end:flags,bellAxis,attachment,vaultState\n");long runs=0;
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])));var refs=new DataOutputStream(new FileOutputStream(args[1]))) {
            out.writeInt(0x4e554f31);out.writeInt(Block.DATA_BITS);out.writeInt(Block.MAX_BLOCK_ID);out.writeInt(CustomBlockManager.LOWEST_CUSTOM_BLOCK_ID);properties(new BlockUnknown(-1)).write(out);
            refs.writeInt(0x4e555231);refs.writeInt(Block.MAX_BLOCK_ID);
            for(int id=0;id<Block.MAX_BLOCK_ID;id++) {
                List<Run> row=new ArrayList<>();Properties previous=null;long hash=0xcbf29ce484222325L;
                for(int data=0;data<Block.DATA_SIZE;data++) {
                    var p=properties(Block.get(id,data,null,0,0,0,0));hash=checksum(hash,p);
                    if(previous!=null&&!previous.equals(p))row.add(new Run(data-1,previous));previous=p;
                }
                row.add(new Run(Block.DATA_MASK,previous));out.writeInt(row.size());for(var r:row){out.writeShort(r.end);r.value.write(out);}runs+=row.size();refs.writeLong(hash);
                audit.append(id).append('\t').append(Block.list[id]==null?"BlockUnknown":Block.list[id].getName()).append('\t');for(var r:row)audit.append(r.end).append(':').append(r.value.flags).append(',').append(r.value.bellAxis).append(',').append(r.value.attachment).append(',').append(r.value.vaultState).append(';');audit.append('\n');
                if(caches[id] instanceof Map<?,?> cache)cache.clear();if((id+1)%512==0)System.out.println("use-item-on export "+(id+1)+"/"+Block.MAX_BLOCK_ID);
            }
            int[] ids={-10000,-1,2048,4095,9999},metas={0,1,7,15,8191};refs.writeInt(ids.length*metas.length);for(int id:ids)for(int meta:metas){refs.writeInt(id);refs.writeInt(meta);properties(Block.get(id,meta,null,0,0,0,0)).write(refs);}
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of(args[2]),audit);System.out.println("Native use-item-on properties: 16777216 states, "+runs+" lossless ranges.");
    }
}
