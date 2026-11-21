import cn.nukkit.block.*;
import cn.nukkit.block.custom.CustomBlockManager;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NukkitPlacementPropertiesExporter {
    private record Run(int end,boolean replaceable) { }
    public static void main(String[] args)throws Exception {
        Block.init();var field=Block.class.getDeclaredField("statePrototypes");field.setAccessible(true);
        Object[] caches=(Object[])field.get(null);var digest=MessageDigest.getInstance("SHA-256");
        long ranges=0;var audit=new StringBuilder("id\tnative_class\tcanBeReplaced() by metadata\n");
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])));
            var reference=new DataOutputStream(new FileOutputStream(args[1]));
            var hashed=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))) {
            out.writeInt(0x4E525031);out.writeInt(Block.DATA_BITS);out.writeInt(Block.MAX_BLOCK_ID);
            out.writeInt(CustomBlockManager.LOWEST_CUSTOM_BLOCK_ID);out.writeBoolean(new BlockUnknown(-1).canBeReplaced());
            reference.writeInt(0x4E525231);reference.writeInt(Block.DATA_BITS);reference.writeInt(Block.MAX_BLOCK_ID);
            for(int id=0;id<Block.MAX_BLOCK_ID;id++) {
                var row=new ArrayList<Run>();boolean previous=false;
                for(int data=0;data<Block.DATA_SIZE;data++) {
                    boolean value=Block.get(id,data,null,0,0,0,0).canBeReplaced();
                    hashed.writeInt(data);hashed.writeBoolean(value);
                    if(data>0&&previous!=value)row.add(new Run(data-1,previous));previous=value;
                }
                row.add(new Run(Block.DATA_MASK,previous));out.writeInt(row.size());ranges+=row.size();
                audit.append(id).append('\t').append(Block.list[id]==null?"BlockUnknown":Block.list[id].getName()).append('\t');
                int start=0;for(var run:row) {
                    out.writeShort(run.end);out.writeBoolean(run.replaceable);
                    if(start>0)audit.append(';');audit.append(start).append('-').append(run.end).append('=').append(run.replaceable);start=run.end+1;
                }
                audit.append('\n');reference.write(digest.digest());
                if(caches[id] instanceof Map<?,?> cache)cache.clear();
                if((id+1)%256==0)System.out.println("exported placement IDs "+(id+1)+"/"+Block.MAX_BLOCK_ID);
            }
            int[] ids={Integer.MIN_VALUE,-1,2048,8191,9999};int[] metadata={0,1,7,8,63,8191};
            reference.writeInt(ids.length*metadata.length);
            for(int id:ids)for(int data:metadata){reference.writeInt(id);reference.writeInt(data);reference.writeBoolean(Block.get(id,data,null,0,0,0,0).canBeReplaced());}
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of(args[2]),audit);
        System.out.println("16,777,216 native replacement inputs; lossless ranges="+ranges+"; 30 unknown-ID cases.");
    }
}
