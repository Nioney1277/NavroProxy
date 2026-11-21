import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import java.io.*;
import java.nio.ByteOrder;
import java.util.zip.GZIPOutputStream;


public final class NativeNbtNumberFixture {
 public static void main(String[] args) throws Exception {
  try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))) {
   out.writeInt(0x4E4E4231);out.writeInt(20);
   for(int i=0;i<20;i++) {
    var tag=new CompoundTag();
    switch(i%10) {
     case 0->tag.putByte("value",i==0?37:-127);
     case 1->tag.putShort("value",i==1?37:-30000);
     case 2->tag.putInt("value",i==2?65573:Integer.MIN_VALUE);
     case 3->tag.putLong("value",i==3?0x100000025L:Long.MIN_VALUE);
     case 4->tag.putFloat("value",i==4?37.9F:Float.NaN);
     case 5->tag.putDouble("value",i==5?37.9:Double.POSITIVE_INFINITY);
     case 6->tag.putString("value","37");
     case 7->tag.putCompound("value",new CompoundTag());
     case 8->tag.putByteArray("value",new byte[]{37});
     default->{ }
    }
    byte[] bytes=NBTIO.write(tag,ByteOrder.BIG_ENDIAN,false);out.writeInt(bytes.length);out.write(bytes);
    try { int value=tag.getInt("value");out.writeBoolean(true);out.writeInt(value); }
    catch(RuntimeException e) {out.writeBoolean(false);out.writeUTF(e.getClass().getSimpleName());}
    try { int value=tag.getShort("value");out.writeBoolean(true);out.writeInt(value); }
    catch(RuntimeException e) {out.writeBoolean(false);out.writeUTF(e.getClass().getSimpleName());}
   }
  }
 }
}
