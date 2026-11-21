import cn.nukkit.block.*;
import cn.nukkit.block.custom.CustomBlockManager;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NukkitInteractionPropertiesExporter {
    static int flags(Block block,int metadata) {
        String c=block.getClass().getSimpleName();
        boolean furnace=c.equals("BlockFurnace")||c.equals("BlockFurnaceBurning")||c.equals("BlockBlastFurnace")||c.equals("BlockBlastFurnaceBurning");
        boolean usable=furnace||block instanceof BlockAnvil||block instanceof BlockBarrel||block instanceof BlockBeacon
                ||block instanceof BlockBed||block instanceof BlockBrewingStand||block instanceof BlockButton||block instanceof BlockChest
                ||block instanceof BlockLever||block instanceof BlockDoor||block instanceof BlockTrapdoor||block instanceof BlockFenceGate;
        boolean door=block instanceof BlockDoor,trapdoor=block instanceof BlockTrapdoor,gate=block instanceof BlockFenceGate;


        if((door||trapdoor||gate)&&block.getDamage()!=metadata)throw new AssertionError("activation metadata changed: "+block.getId()+":"+metadata+" -> "+block.getDamage());
        return (block.isWater()?1:0)|(Block.isLava(block.getId())?2:0)|(door?4:0)|(trapdoor?8:0)|(gate?16:0)|(usable?32:0);
    }
    record Run(int end,int flags){}
    public static void main(String[] args)throws Exception {
        Block.init();var field=Block.class.getDeclaredField("statePrototypes");field.setAccessible(true);Object[] caches=(Object[])field.get(null);
        var audit=new StringBuilder("id\tnative_class\tmetadata_end:flags\n");long runs=0;
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])));
            var refs=new DataOutputStream(new FileOutputStream(args[1]))) {
            out.writeInt(0x4e495031);out.writeInt(Block.DATA_BITS);out.writeInt(Block.MAX_BLOCK_ID);out.writeInt(CustomBlockManager.LOWEST_CUSTOM_BLOCK_ID);out.writeByte(flags(new BlockUnknown(-1),0));
            refs.writeInt(0x4e495231);refs.writeInt(Block.MAX_BLOCK_ID);
            for(int id=0;id<Block.MAX_BLOCK_ID;id++) {
                List<Run> row=new ArrayList<>();int last=-1;long checksum=0xcbf29ce484222325L;
                for(int data=0;data<Block.DATA_SIZE;data++) {
                    int f=flags(Block.get(id,data,null,0,0,0,0),data);checksum=(checksum^f)*0x100000001b3L;
                    if(last!=-1&&last!=f)row.add(new Run(data-1,last));last=f;
                }
                row.add(new Run(Block.DATA_MASK,last));out.writeInt(row.size());for(var run:row){out.writeShort(run.end);out.writeByte(run.flags);}runs+=row.size();refs.writeLong(checksum);
                audit.append(id).append('\t').append(Block.list[id]==null?"BlockUnknown":Block.list[id].getName()).append('\t');for(var run:row)audit.append(run.end).append(':').append(run.flags).append(';');audit.append('\n');
                if(caches[id] instanceof Map<?,?> cache)cache.clear();if((id+1)%512==0)System.out.println("interaction export "+(id+1)+"/"+Block.MAX_BLOCK_ID);
            }
            int[] invalid={-10000,-1,2048,4095,9999};int[] metas={0,1,7,15,8191};refs.writeInt(invalid.length*metas.length);
            for(int id:invalid)for(int meta:metas){refs.writeInt(id);refs.writeInt(meta);refs.writeByte(flags(Block.get(id,meta,null,0,0,0,0),meta));}
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of(args[2]),audit);System.out.println("Native interaction flags: 16777216 states, "+runs+" lossless ranges; activation metadata identity verified.");
    }
}
