import ac.navro.anticheat.player.data.PlayerData;
import ac.navro.anticheat.data.vanilla.AttributeInstance;
import ac.navro.anticheat.player.data.tracker.FlagTracker;
import cn.nukkit.entity.Attribute;
import com.google.gson.Gson;
import sun.misc.Unsafe;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroSprintAttributeFixture {
    static final String NAME="minecraft:movement";
    static void set(Object target,String field,Object value)throws Exception {
        var f=PlayerData.class.getDeclaredField(field);f.setAccessible(true);f.set(target,value);
    }
    static Map<String,Object> state(PlayerData p,int pending) {
        var speed=p.attributes.get(NAME);
        return Map.of("base",Float.floatToRawIntBits(speed.getBaseValue()),"value",Float.floatToRawIntBits(speed.getValue()),
                "modifiers",speed.getModifiers().size(),"client",p.isClientSprinting(),"server",p.isServerSprinting(),
                "flag",p.getFlagTracker().has(3),"prediction",p.isPredictionSprinting(),"pending",pending);
    }
    public static void main(String[] args)throws Exception {
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var unsafe=(Unsafe)f.get(null);
        Attribute.init();var random=new Random(0x535052494e544143L);var gson=new Gson();int sessions=32,steps=128;
        int[] prefix={0,1,3,0,1,0,0,3,0,2,3,4};int rows=0;
        try(var out=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(args[0])),StandardCharsets.UTF_8))) {
            out.write(gson.toJson(Map.of("magic","NSA1","sessions",sessions,"steps",steps)));out.newLine();
            for(int sn=0;sn<sessions;sn++) {
                var p=(PlayerData)unsafe.allocateInstance(PlayerData.class);set(p,"flagTracker",new FlagTracker());
                set(p,"attributes",new HashMap<String,AttributeInstance>());p.attributes.put(NAME,new AttributeInstance(.1F));
                var pending=new ArrayDeque<Runnable>();
                for(int step=0;step<steps;step++) {
                    int op=step<prefix.length?prefix[step]:random.nextInt(5);
                    if(op==3 && pending.isEmpty())op=4;
                    boolean sprint=step==0 || step==5 || step==8 || random.nextBoolean();if(step==3||step==6)sprint=false;
                    float base=new float[]{.1F,.08F,.2F}[random.nextInt(3)];float value=base*(random.nextBoolean()?1.3F:1);
                    if(step==1){base=.1F;value=.13F;}if(step==4){base=.1F;value=.1F;}
                    var row=new LinkedHashMap<String,Object>();row.put("op",op);row.put("sprint",sprint);row.put("base",base);row.put("value",value);
                    switch(op) {
                        case 0 -> p.setSprinting(sprint);
                        case 1 -> {final float b=base,v=value;pending.add(()->p.setAttribute(NAME,b,v));}

                        case 2 -> {final boolean b=sprint;pending.add(()->{p.setServerSprinting(b);p.getFlagTracker().set(3,b);});}
                        case 3 -> pending.removeFirst().run();
                        case 4 -> p.snapshotPredictionMovementState();
                        default -> throw new AssertionError(op);
                    }
                    row.put("state",state(p,pending.size()));out.write(gson.toJson(row));out.newLine();rows++;
                }
            }
        }
        System.out.println("Original sprint/attribute transitions: "+rows);
    }
}
