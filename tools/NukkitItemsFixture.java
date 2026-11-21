import cn.nukkit.network.protocol.ItemComponentPacket;
import cn.nukkit.nbt.tag.CompoundTag;
import java.nio.file.*;


public class NukkitItemsFixture {
 public static void main(String[] args)throws Exception {
  var p=new ItemComponentPacket();p.protocol=2168;
  for(int id=1;id<=1976;id++)p.entries.add(new ItemComponentPacket.ItemDefinition(
   id==1?"minecraft:stone":id==2?"minecraft:shield":"test:item_"+id,id,id==1976,0,
   id==1976?new CompoundTag("").putString("label","§a原始颜色 §r"):null));
  p.encode();Files.write(Path.of(args[0]),p.getBuffer());
  System.out.println("Nukkit fixture entries="+p.entries.size()+" bytes="+p.getBuffer().length);
 }
}
