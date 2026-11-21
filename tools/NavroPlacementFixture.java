import ac.navro.anticheat.util.block.BlockPlacementPhysics;
import cn.nukkit.block.*;
import cn.nukkit.math.BlockVector3;
import java.io.*;
import java.util.ArrayList;


public final class NavroPlacementFixture {
 public static void main(String[] args) throws Exception {
  var blocks=new ArrayList<Block>();for(int i=0;i<8;i++)blocks.add(new BlockSnowLayer(i));
  blocks.add(new BlockTallGrass(1));blocks.add(new BlockTallGrass(2));
  blocks.add(new BlockDoublePlant(BlockDoublePlant.TALL_GRASS));blocks.add(new BlockDoublePlant(BlockDoublePlant.LARGE_FERN));
  blocks.add(new BlockWater());blocks.add(new BlockWater(3));blocks.add(new BlockLava());
  blocks.add(new BlockPlanks());blocks.add(new BlockStone());blocks.add(new BlockTorch());
  BlockVector3[] positions={new BlockVector3(306,70,82),new BlockVector3(-1,-64,-17),new BlockVector3(Integer.MAX_VALUE,70,Integer.MIN_VALUE)};
  try(var out=new DataOutputStream(new FileOutputStream(args[0]))) {
   out.writeInt(0x4E504F31);out.writeInt(Block.DATA_BITS);out.writeInt(blocks.size()*positions.length*8);
   for(var position:positions)for(var block:blocks)for(int face=-1;face<7;face++) {
    out.writeInt(position.x);out.writeInt(position.y);out.writeInt(position.z);out.writeInt(face);
    out.writeInt(block.getFullId());out.writeBoolean(block.canBeReplaced());out.writeInt(new BlockPlanks().getFullId());
    var target=BlockPlacementPhysics.target(position,face,block);
    out.writeInt(target.x);out.writeInt(target.y);out.writeInt(target.z);
   }
  }
 }
}
