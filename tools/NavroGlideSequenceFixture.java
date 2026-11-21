import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.player.data.PlayerData;
import ac.navro.anticheat.player.data.tracker.FlagTracker;
import ac.navro.anticheat.prediction.engine.impl.GlidingPredictionEngine;
import ac.navro.anticheat.util.math.Vec3;
import sun.misc.Unsafe;
import java.io.*;
import java.util.*;
import com.google.gson.*;





public class NavroGlideSequenceFixture {
    static void set(Object p,Class<?> c,String name,Object v)throws Exception{var f=c.getDeclaredField(name);f.setAccessible(true);f.set(p,v);}
    static List<Float> vec(Vec3 v){return List.of(v.x,v.y,v.z);}
    public static void main(String[] args)throws Exception{
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var u=(Unsafe)f.get(null);
        var p=(NavroPlayer)u.allocateInstance(NavroPlayer.class);
        set(p,PlayerData.class,"flagTracker",new FlagTracker());set(p,PlayerData.class,"activeEffects",new HashMap<>());
        p.position=new Vec3(8,100,8);p.velocity=new Vec3(.15F,-.12F,.35F);p.yaw=179;p.pitch=-25;
        Gson gson=new Gson();
        try(var out=new PrintWriter(args[0])){
            out.println(gson.toJson(Map.of("initialPosition",vec(p.position),"initialVelocity",vec(p.velocity),"initialRotation",List.of(p.pitch,p.yaw))));
            for(int tick=10;tick<58;tick++){
                p.prevYaw=p.yaw;p.prevPitch=p.pitch;

                p.yaw=tick<24?179+(tick-10)*2F:tick<28?1400+(tick-24)*185F:35+(tick-28)*3F;
                p.pitch=tick<24?-25+(tick-10)*2F:tick<28?720+(tick-24)*91F:-15+(tick-28)*1.5F;
                boolean confirm=tick==28;
                boolean held=tick>=24&&tick<=28;
                if(tick==18)p.glideBoostTicks=6;
                if(confirm)p.velocity=Vec3.ZERO.clone();
                if(!held){p.velocity=new GlidingPredictionEngine(p).travel(p.velocity);p.position=p.position.add(p.velocity);}
                var row=new LinkedHashMap<String,Object>();row.put("tick",tick);row.put("rotation",List.of(p.pitch,p.yaw));row.put("held",held);row.put("boostEvent",tick==18?12:-1);
                row.put("confirm",confirm);row.put("previousRotation",List.of(p.prevPitch,p.prevYaw));
                row.put("position",vec(p.position));row.put("velocity",vec(p.velocity));row.put("boostBeforePostTick",p.glideBoostTicks);
                out.println(gson.toJson(row));p.glideBoostTicks--;
            }
        }
    }
}
