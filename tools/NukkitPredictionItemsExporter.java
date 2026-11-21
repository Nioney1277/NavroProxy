import cn.nukkit.*;
import cn.nukkit.block.Block;
import cn.nukkit.item.*;
import cn.nukkit.item.food.Food;
import cn.nukkit.utils.Utils;
import sun.misc.Unsafe;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public class NukkitPredictionItemsExporter {
 record Factory(Integer number,String name) implements Comparable<Factory>{public int compareTo(Factory b){return toString().compareTo(b.toString());}}
 record Binding(Factory factory,int fixedDamage){}
 record Traits(int nativeId,boolean positiveNull,boolean release,int duration,boolean food,String durationError,String foodError){
  static Traits of(Item item){int duration=0;boolean food=false;String durationError="",foodError="";
   try{duration=item.getUseDuration();}catch(RuntimeException e){durationError=e.getClass().getName()+"\n"+e.getMessage();}
   try{food=Food.getByRelative(item)!=null;}catch(RuntimeException e){foodError=e.getClass().getName()+"\n"+e.getMessage();}
   return new Traits(item.getId(),item.isNull(),item.canRelease(),duration,food,durationError,foodError);
  }
  void write(DataOutputStream out)throws Exception{out.writeInt(nativeId);out.writeBoolean(positiveNull);out.writeBoolean(release);out.writeInt(duration);out.writeBoolean(food);out.writeUTF(durationError);out.writeUTF(foodError);}
 }
 record Run(int end,Traits value){}
 public static void main(String[] args)throws Exception{
  var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var u=(Unsafe)f.get(null);f=Server.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,u.allocateInstance(Server.class));
  Block.init();RuntimeItems.init();Item.init();cn.nukkit.potion.Effect.init();cn.nukkit.item.enchantment.Enchantment.init();
  int[] protocols={729,776,786,800,819,844,860,898,924,944,975,1001,2168,2169,2192,2193,2225};var tables=new LinkedHashMap<Integer,Map<String,Binding>>();var factories=new TreeSet<Factory>();
  for(int protocol:protocols){var map=RuntimeItems.getMapping(protocol);var table=new TreeMap<String,Binding>();
   for(var entry:map.getItemPaletteEntries()){
    String name=entry.getIdentifier();Integer id=null;int fixed=-1;
    if(!Item.isRegisteredStringItemIdentifier(name)){
     try{var legacy=map.fromRuntime(entry.getRuntimeId());if(Utils.hasItemOrBlock(legacy.getLegacyId())){id=legacy.getLegacyId();if(legacy.isHasDamage())fixed=legacy.getDamage();}}catch(IllegalArgumentException ignored){}
    }
    var factory=new Factory(id,id==null?name:null);factories.add(factory);table.put(name,new Binding(factory,fixed));
   }
   tables.put(protocol,table);
  }
  var cacheField=Block.class.getDeclaredField("statePrototypes");cacheField.setAccessible(true);Object[] caches=(Object[])cacheField.get(null);
  var indices=new HashMap<Factory,Integer>();int index=0;for(var factory:factories)indices.put(factory,index++);
  try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))){
   out.writeInt(0x4e505531);out.writeInt(tables.size());for(var t:tables.entrySet()){
    out.writeInt(t.getKey());out.writeInt(t.getValue().size());for(var entry:t.getValue().entrySet()){out.writeUTF(entry.getKey());out.writeInt(indices.get(entry.getValue().factory()));out.writeInt(entry.getValue().fixedDamage());}
   }
   out.writeInt(factories.size());index=0;long samples=0;
   for(var factory:factories){
    var row=new ArrayList<Run>();Traits previous=null;Item prototype=factory.number()==null?Item.fromString(factory.name()):null;
    for(int damage=0;damage<=65535;damage++){
     Item item;if(factory.number()!=null)item=Item.get(factory.number(),damage,1);else{prototype.setDamage(damage);item=prototype;}
     Traits value=Traits.of(item);if(previous!=null&&!previous.equals(value))row.add(new Run(damage-1,previous));previous=value;samples++;
    }
    row.add(new Run(65535,previous));out.writeInt(row.size());for(var run:row){out.writeShort(run.end());run.value().write(out);}
    for(Object cache:caches)if(cache instanceof Map<?,?> map)map.clear();
    if(++index%64==0)System.out.println("Prediction item factories "+index+"/"+factories.size()+", samples="+samples);
   }
   System.out.println("Native decoder factories="+factories.size()+", all metadata samples="+samples+", protocols="+tables.size());
  }
 }
}
