import ac.navro.anticheat.Navro;
import ac.navro.anticheat.compensated.CompensatedInventory;
import ac.navro.anticheat.data.*;
import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.player.data.PlayerData;
import ac.navro.anticheat.player.data.tracker.FlagTracker;
import ac.navro.anticheat.prediction.RiptidePhysics;
import ac.navro.anticheat.validator.inventory.ItemTransactionValidator;
import cn.nukkit.*;
import cn.nukkit.block.Block;
import cn.nukkit.entity.Entity;
import cn.nukkit.inventory.PlayerInventory;
import cn.nukkit.inventory.transaction.data.*;
import cn.nukkit.item.*;
import cn.nukkit.item.enchantment.Enchantment;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.InventoryTransactionPacket;
import cn.nukkit.network.protocol.ProtocolInfo;
import com.google.gson.*;
import sun.misc.Unsafe;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroAirReleaseFixture {
    static boolean capture;static List<String> events=new ArrayList<>();
    public static final class Inventory extends PlayerInventory {Item held,chest;public Inventory(){super(null);}@Override public Item getItemInHand(){if(capture)events.add("serverHeld");return held;}@Override public Item getChestplateFast(){if(capture)events.add("serverChest");return chest;}}
    public static final class Session extends Player {Inventory inventory;boolean using,gliding;public Session(){super(null,0L,null);}@Override public GameVersion getGameVersion(){return GameVersion.getLastVersion();}@Override public boolean isUsingItem(){return using;}@Override public boolean isGliding(){if(capture)events.add("serverGliding");return gliding;}@Override public PlayerInventory getInventory(){if(capture)events.add("serverInventory");return inventory;}}
    static Item item(int index){return NavroItemUseTrackerFixture.item(index);}static void set(Object o,Class<?> c,String n,Object v)throws Exception{NavroItemUseTrackerFixture.set(o,c,n,v);}
    public static void main(String[] args)throws Exception{
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var u=(Unsafe)f.get(null);NavroItemUseTrackerFixture.unsafe=u;set(null,Server.class,"instance",u.allocateInstance(Server.class));Navro.setConfig(new ac.navro.anticheat.config.Config());Block.init();RuntimeItems.init();Item.init();cn.nukkit.potion.Effect.init();Enchantment.init();
        var pool=NavroItemUseTrackerFixture.pool;for(int id:new int[]{261,455,455,455,1,401,444,0,260,471})pool.add(Item.get(id,0,1));pool.get(2).addEnchantment(Enchantment.getEnchantment(Enchantment.ID_TRIDENT_RIPTIDE).setLevel(1));pool.get(3).addEnchantment(Enchantment.getEnchantment(Enchantment.ID_TRIDENT_RIPTIDE).setLevel(3));pool.add(new Item(401,0,1));pool.add(new Item(444,0,1));for(int i=0;i<pool.size();i++){var tag=pool.get(i).getNamedTag();if(tag==null)tag=new CompoundTag();tag.putInt("fixture_profile",i);pool.get(i).setNamedTag(tag);}
        var click=ItemTransactionValidator.class.getDeclaredMethod("handleClickAir",UseItemData.class);click.setAccessible(true);var release=ItemTransactionValidator.class.getDeclaredMethod("handleRelease",InventoryTransactionPacket.class);release.setAccessible(true);Gson gson=new GsonBuilder().serializeNulls().create();Random random=new Random(0x41495252454cL);int cases=6144;
        try(var out=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(args[0])),StandardCharsets.UTF_8))){
            var p=(NavroPlayer)u.allocateInstance(NavroPlayer.class);var session=(Session)u.allocateInstance(Session.class);session.protocol=ProtocolInfo.CURRENT_PROTOCOL;set(p,NavroPlayer.class,"session",session);List<Object> profiles=new ArrayList<>();for(Item item:pool){var profile=NavroItemUseTrackerFixture.profile(p,item);profile.put("firework",item instanceof ItemFirework);profile.put("elytraClass",item instanceof ItemElytra);profile.put("riptide",RiptidePhysics.level(item));profiles.add(profile);}var airProfile=NavroItemUseTrackerFixture.profile(p,Item.AIR_ITEM);airProfile.put("riptide",0);out.write(gson.toJson(Map.of("magic","NAR1","cases",cases,"profiles",profiles,"air",airProfile)));out.newLine();
            for(int n=0;n<cases;n++){
                capture=false;events.clear();p=(NavroPlayer)u.allocateInstance(NavroPlayer.class);session=(Session)u.allocateInstance(Session.class);session.protocol=ProtocolInfo.CURRENT_PROTOCOL;set(p,NavroPlayer.class,"session",session);set(p,PlayerData.class,"flagTracker",new FlagTracker());set(p,NavroPlayer.class,"itemUseDebug",new ItemUseDebug(p));var tracker=new ItemUseTracker(p);set(p,NavroPlayer.class,"itemUseTracker",tracker);var inventory=new CompensatedInventory(p);set(p,NavroPlayer.class,"compensatedInventory",inventory);inventory.heldItemSlot=n%9;p.tick=n;
                int mode=n%3,heldIndex=mode==2?5:random.nextInt(pool.size()),chestIndex=new int[]{6,11,4,7}[random.nextInt(4)],serverIndex=random.nextInt(pool.size()+2)-2,serverChest=new int[]{6,11,4,7}[random.nextInt(4)],seed=random.nextInt(pool.size());int count=new int[]{-1,0,1,7}[random.nextInt(4)],chestCount=new int[]{-1,0,1}[random.nextInt(3)],since=new int[]{0,9,10,11,Integer.MAX_VALUE,Integer.MIN_VALUE}[random.nextInt(6)],boost=new int[]{0,1,100,Integer.MAX_VALUE,Integer.MIN_VALUE}[random.nextInt(5)],flight=new int[]{Integer.MIN_VALUE,-1,0,1,2,127,255,Integer.MAX_VALUE,Integer.MAX_VALUE-1,214748363}[random.nextInt(10)];
                Item raw=pool.get(heldIndex).clone();raw.setCount(count);int flightKind=n%11==0?1:n%13==0?2:0;var tag=raw.getNamedTag();tag.putCompound("Fireworks",flightKind==1?new CompoundTag().putString("Flight","bad"):flightKind==2?new CompoundTag().putFloat("Flight",Float.POSITIVE_INFINITY):new CompoundTag().putInt("Flight",flight));raw.setNamedTag(tag);inventory.inventoryContainer.set(inventory.heldItemSlot,raw);Item held=inventory.inventoryContainer.getHeldItemData();Item chest=pool.get(chestIndex).clone();chest.setCount(chestCount);inventory.armorContainer.set(1,chest);
                boolean hasInventory=n%29!=0,serverGlide=random.nextBoolean(),compGlide=random.nextBoolean(),serverUsing=random.nextBoolean(),pending=random.nextBoolean(),latch=random.nextBoolean(),using=random.nextBoolean(),action=random.nextBoolean(),wait=random.nextBoolean(),dirtySeed=random.nextBoolean(),expectedRocket=random.nextBoolean();session.gliding=serverGlide;session.using=serverUsing;if(hasInventory){session.inventory=(Inventory)u.allocateInstance(Inventory.class);session.inventory.held=item(serverIndex);session.inventory.chest=pool.get(serverChest);}
                tracker.use(pool.get(seed),pool.get(seed).getId(),true);tracker.setUseButtonLatched(latch);if(wait){tracker.onMovementMetadataAcknowledged(false);tracker.use(pool.get(seed),pool.get(seed).getId(),false);}p.getFlagTracker().set(FlagTracker.USING_ITEM,using);p.getFlagTracker().set(Entity.DATA_FLAG_ACTION,action);p.getFlagTracker().set(Entity.DATA_FLAG_GLIDING,compGlide);p.sinceTridentUse=since;p.glideBoostTicks=boost;p.nukkitGlideBoostPending=pending;p.dirtyRiptide=dirtySeed;p.riptideItem=dirtySeed?pool.get(3).clone():Item.AIR_ITEM;
                var use=new UseItemData();use.itemInHand=n%31==0?null:n%17==0?Item.get(471,0,1):held.clone();var packet=new InventoryTransactionPacket();packet.transactionData=n%37==0?null:new ReleaseItemData();int releaseAction=n%7==0?1:n%7==1?99:0;if(packet.transactionData instanceof ReleaseItemData data)data.actionType=releaseAction;
                Map<String,Object> row=new LinkedHashMap<>();row.put("mode",mode);row.put("tick",n);row.put("slot",inventory.heldItemSlot);row.put("held",heldIndex);row.put("count",count);row.put("chest",chestIndex);row.put("chestCount",chestCount);row.put("serverHeld",serverIndex);row.put("serverChest",serverChest);row.put("seed",seed);row.put("hasInventory",hasInventory);row.put("serverGlide",serverGlide);row.put("compGlide",compGlide);row.put("serverUsing",serverUsing);row.put("pending",pending);row.put("latch",latch);row.put("using",using);row.put("action",action);row.put("wait",wait);row.put("dirtySeed",dirtySeed);row.put("expectedRocket",expectedRocket);row.put("since",since);row.put("boost",boost);row.put("flightKind",flightKind);row.put("flightInput",flight);row.put("releaseData",packet.transactionData!=null);row.put("releaseAction",releaseAction);row.put("claim",use.itemInHand==null?-1:n%17==0?9:heldIndex);
                String flightError="";int actualFlight=0;if(held instanceof ItemFirework firework)try{actualFlight=firework.getFlight();}catch(Exception e){flightError=e.getClass().getSimpleName();}row.put("flight",actualFlight);row.put("flightError",flightError);
                capture=true;boolean result=true;String error="";try{var validator=new ItemTransactionValidator(p);if(mode==0)result=(boolean)click.invoke(validator,use);else if(mode==1)result=(boolean)release.invoke(validator,packet);else p.beginNukkitGlideBoost((ItemFirework)held,expectedRocket);}catch(java.lang.reflect.InvocationTargetException e){error=e.getCause().getClass().getSimpleName();}catch(Exception e){error=e.getClass().getSimpleName();}capture=false;
                row.put("result",result);row.put("error",error);row.put("events",new ArrayList<>(events));row.put("state",NavroItemUseTrackerFixture.state(p,tracker,new ItemUseTracker.Snapshot[4]));row.put("boostAfter",p.glideBoostTicks);row.put("pendingAfter",p.nukkitGlideBoostPending);row.put("dirtyAfter",p.dirtyRiptide);row.put("riptideAfter",NavroItemUseTrackerFixture.view(p.riptideItem));row.put("heldAfter",held.getCount());row.put("chestAfter",inventory.armorContainer.get(1).count());out.write(gson.toJson(row));out.newLine();
            }
        }
        System.out.println("Current original click-air/release/boost: "+cases+" cases, real tracker, native firework/elytra types, riptide candidate priority and failures.");
    }
}
