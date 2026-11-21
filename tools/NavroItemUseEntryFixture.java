import ac.navro.anticheat.Navro;
import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.player.data.PlayerData;
import ac.navro.anticheat.player.data.tracker.FlagTracker;
import ac.navro.anticheat.compensated.CompensatedInventory;
import ac.navro.anticheat.data.inventory.ItemCache;
import ac.navro.anticheat.validator.inventory.ItemTransactionValidator;
import ac.navro.anticheat.util.ItemUtil;
import ac.navro.anticheat.util.math.Vec3;
import cn.nukkit.*;
import cn.nukkit.block.Block;
import cn.nukkit.item.*;
import cn.nukkit.inventory.transaction.data.UseItemData;
import cn.nukkit.math.BlockVector3;
import cn.nukkit.network.protocol.InventoryTransactionPacket;
import cn.nukkit.network.protocol.types.NetworkInventoryAction;
import sun.misc.Unsafe;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroItemUseEntryFixture {
    public static final class Session extends Player {
        public Session(){super(null,0L,null);}
        @Override public GameVersion getGameVersion(){return GameVersion.getLastVersion();}
    }
    static void set(Object o,Class<?> c,String name,Object value)throws Exception{var f=c.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static void item(DataOutputStream out,NavroPlayer player,Item item)throws Exception{
        out.writeBoolean(item!=null);if(item==null)return;
        out.writeInt(item.getId());out.writeInt(item.getDamage());out.writeInt(item.getCount());
        String name=ItemUtil.identifier(player,item);out.writeBoolean(name!=null);if(name!=null)out.writeUTF(name);out.writeInt(ItemUtil.runtimeId(player,item));
    }
    static Item stack(int kind,int count){Item item=Item.get(new int[]{1,260,0,261}[Math.floorMod(kind,4)],kind%5,count);item.setCount(count);return item;}
    public static void main(String[] args)throws Exception{
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var unsafe=(Unsafe)f.get(null);
        set(null,Server.class,"instance",unsafe.allocateInstance(Server.class));Navro.setConfig(new ac.navro.anticheat.config.Config());Block.init();RuntimeItems.init();Item.init();cn.nukkit.item.enchantment.Enchantment.init();
        var use=ItemTransactionValidator.class.getDeclaredMethod("handleUse",InventoryTransactionPacket.class);use.setAccessible(true);
        var armor=ItemTransactionValidator.class.getDeclaredMethod("handleLegacyArmorEquip",InventoryTransactionPacket.class,UseItemData.class);armor.setAccessible(true);
        var block=ItemTransactionValidator.class.getDeclaredMethod("handleClickBlock",UseItemData.class);block.setAccessible(true);
        var air=ItemTransactionValidator.class.getDeclaredMethod("handleClickAir",UseItemData.class);air.setAccessible(true);
        Random random=new Random(0x4e4156524fL);
        int[][] positions={{0,0,0},{100,-100,0},{Integer.MAX_VALUE,1,Integer.MIN_VALUE},{Integer.MIN_VALUE,Integer.MIN_VALUE,0},{12,0,0},{13,0,0},{8,8,4},{16777217,0,0},{Integer.MAX_VALUE,Integer.MAX_VALUE,2}};
        float[][] playerPos={{0,0,0},{0.000001f,0,0},{-0.000001f,0,0},{16777216,0,0},{Float.NaN,0,0},{Float.POSITIVE_INFINITY,0,0},{100,-100,0}};
        int[] slotValues={-1,0,1,2,8,9,Integer.MIN_VALUE,Integer.MAX_VALUE};int[] armorSlots={0,1,2,3,4,35,36,127,128,255};
        int cases=6144;
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))){
            out.writeInt(0x4e554531);out.writeInt(cases);
            for(int i=0;i<cases;i++){
                var player=(NavroPlayer)unsafe.allocateInstance(NavroPlayer.class);set(player,NavroPlayer.class,"session",unsafe.allocateInstance(Session.class));
                var flags=new FlagTracker();flags.set(FlagTracker.USING_ITEM,true);set(player,PlayerData.class,"flagTracker",flags);
                var inv=new CompensatedInventory(player);set(player,NavroPlayer.class,"compensatedInventory",inv);inv.heldItemSlot=random.nextInt(9);
                var aliases=new IdentityHashMap<ItemCache,Integer>();
                for(int s=0;s<9;s++){inv.inventoryContainer.set(s,stack(random.nextInt(4),new int[]{0,-1,1,7}[random.nextInt(4)]));aliases.put(inv.inventoryContainer.get(s),s);}
                for(int s=0;s<36;s++){inv.armorContainer.set(s,stack(random.nextInt(4),s+1));aliases.put(inv.armorContainer.get(s),9+s);}
                float[] p=playerPos[random.nextInt(playerPos.length)];player.position=new Vec3(p[0],p[1],p[2]);
                var packet=new InventoryTransactionPacket();var data=new UseItemData();
                int mode=i%3;packet.transactionType=InventoryTransactionPacket.TYPE_USE_ITEM;
                boolean hasData=i%37!=0;packet.transactionData=hasData?data:null;
                data.hotbarSlot=random.nextInt(3)==0?slotValues[random.nextInt(slotValues.length)]:inv.heldItemSlot;
                Item held=inv.inventoryContainer.getHeldItemData();
                data.itemInHand=switch(random.nextInt(5)){case 0->null;case 1->stack(random.nextInt(4),random.nextInt(3)-1);default->held.clone();};
                int[] pos=positions[random.nextInt(positions.length)];data.blockPos=i%31==0?null:new BlockVector3(pos[0],pos[1],pos[2]);
                data.actionType=random.nextInt(5)-1;data.clientInteractPrediction=random.nextInt(2);data.face=null;
                int actionCount=i%13==0?-1:random.nextInt(4);packet.actions=actionCount<0?null:new NetworkInventoryAction[actionCount];
                for(int a=0;a<Math.max(0,actionCount);a++){
                    if(i%17==0&&a==0)continue;
                    var action=new NetworkInventoryAction();action.sourceType=999;action.inventorySlot=random.nextInt(4)==0?slotValues[random.nextInt(slotValues.length)]:random.nextInt(9);
                    action.oldItem=random.nextBoolean()?inv.inventoryContainer.getItemFromSlot(action.inventorySlot).getData().clone():stack(random.nextInt(4),random.nextInt(3));
                    if(i%23==0)action.oldItem=null;packet.actions[a]=action;
                }
                int legacyCount=i%19==0?-1:random.nextInt(3);packet.legacySlots=legacyCount<0?null:new ArrayList<>();
                for(int l=0;l<Math.max(0,legacyCount);l++){
                    if(i%29==0){packet.legacySlots.add(null);continue;}
                    int containerId=random.nextInt(4)==0?120:6;
                    byte[] slots=i%41==0?null:i%43==0?new byte[0]:new byte[]{(byte)armorSlots[random.nextInt(armorSlots.length)],(byte)random.nextInt(256)};packet.legacySlots.add(new InventoryTransactionPacket.LegacySetItemSlotData(containerId,slots));
                }
                out.writeInt(mode);out.writeBoolean(hasData);out.writeInt(inv.heldItemSlot);for(float n:p)out.writeFloat(n);
                for(int s=0;s<9;s++)item(out,player,inv.inventoryContainer.get(s).getData());for(int s=0;s<36;s++)item(out,player,inv.armorContainer.get(s).getData());
                out.writeInt(data.hotbarSlot);item(out,player,data.itemInHand);out.writeBoolean(data.blockPos!=null);if(data.blockPos!=null){out.writeInt(pos[0]);out.writeInt(pos[1]);out.writeInt(pos[2]);}
                out.writeInt(data.actionType);out.writeInt(data.clientInteractPrediction);out.writeInt(actionCount);
                for(int a=0;a<Math.max(0,actionCount);a++){var action=packet.actions[a];out.writeBoolean(action!=null);if(action!=null){out.writeInt(action.inventorySlot);item(out,player,action.oldItem);}}
                out.writeInt(legacyCount);for(int l=0;l<Math.max(0,legacyCount);l++){var legacy=packet.legacySlots.get(l);out.writeBoolean(legacy!=null);if(legacy!=null){out.writeInt(legacy.containerId);out.writeInt(legacy.slots==null?-1:legacy.slots.length);if(legacy.slots!=null)out.write(legacy.slots);}}

                boolean child=true;if(data.blockPos!=null&&(data.actionType==0||data.actionType==1))child=(boolean)(data.actionType==0?block:air).invoke(new ItemTransactionValidator(player),data);
                out.writeBoolean(child);
                String error="";boolean accepted=true;
                try{var validator=new ItemTransactionValidator(player);if(mode==0)accepted=(boolean)use.invoke(validator,packet);else if(mode==1)armor.invoke(validator,packet,data);else accepted=validator.handle(packet);}
                catch(java.lang.reflect.InvocationTargetException e){error=e.getCause().getClass().getSimpleName()+":"+e.getCause().getMessage();}
                out.writeBoolean(accepted);out.writeUTF(error);
                for(int s=0;s<9;s++)out.writeInt(aliases.getOrDefault(inv.inventoryContainer.get(s),-1));for(int s=0;s<36;s++)out.writeInt(aliases.getOrDefault(inv.armorContainer.get(s),-1));
            }
        }
        System.out.println("Original ITEM_USE entry / armor helper: "+cases+" cases (child outputs exported separately).");
    }
}
