package ac.navro.bridge.nukkit;

import cn.nukkit.*;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.item.EntityElytraFirework;
import cn.nukkit.entity.item.EntityFirework;
import cn.nukkit.event.server.DataPacketSendEvent;
import cn.nukkit.level.Level;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.level.format.LevelProvider;
import cn.nukkit.math.Vector3;
import cn.nukkit.network.protocol.*;
import sun.misc.Unsafe;

import java.lang.reflect.Proxy;
import java.util.*;




public final class FireworkBridgeIntegration {
    private static final Unsafe UNSAFE;
    static {
        try { var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);UNSAFE=(Unsafe)f.get(null); }
        catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    static void set(Object target,Class<?> owner,String name,Object value)throws Exception{
        var f=owner.getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    static void check(boolean result,String detail){if(!result)throw new AssertionError(detail);}
    public static class World extends Level {
        Map<Long,Entity> entities;
        World(){super(null,"fixture","",(Class<? extends LevelProvider>)null);}
        @Override public Entity getEntity(long id){return entities.get(id);}
    }
    public static class Peer extends Player {
        NavroFireworkBridge bridge;
        List<DataPacket> delivered;
        List<DataPacket> suppressed;
        Peer(){super(null,0L,new java.net.InetSocketAddress(0));}
        @Override public boolean isOnline(){return true;}
        @Override public boolean isGliding(){return true;}
        @Override public float getEyeHeight(){return .42F;}
        @Override public void addMotion(double x,double y,double z) { }
        @Override public void updateMovement() { }
        @Override public boolean dataPacket(DataPacket p){
            var event=new DataPacketSendEvent(this,p);
            bridge.onPacketSend(event);
            if(event.isCancelled()){suppressed.add(p);return false;}
            delivered.add(p);
            for(var callback:event.getPostSendActions())callback.run();
            return true;
        }
    }
    public static class Rocket extends EntityElytraFirework {
        boolean damage;
        Rocket(){super(null,null,null);}
        @Override public boolean entityBaseTick(int diff){return true;}
        @Override public boolean isAlive(){return true;}
        @Override public boolean setPosition(Vector3 p){return true;}
        @Override public void updateMovement(){ }
        @Override protected boolean hasExplosions(){return false;}
        @Override protected Entity findCollisionEntity(Vector3 movement){return damage?getFollowingPlayer():null;}
        @Override protected void explode(){


            getFollowingPlayer().setMotion(new Vector3(.75,.45,-.25));
        }
    }
    static Peer peer(NavroFireworkBridge bridge,World world,long id)throws Exception{
        var p=(Peer)UNSAFE.allocateInstance(Peer.class);p.bridge=bridge;p.delivered=new ArrayList<>();p.suppressed=new ArrayList<>();
        p.protocol=2168;p.spawned=true;p.justCreated=true;p.level=world;
        set(p,Entity.class,"id",id);set(p,Entity.class,"server",Server.getInstance());
        set(p,Entity.class,"temporalVector",new Vector3());
        p.chunk=(FullChunk)Proxy.newProxyInstance(FullChunk.class.getClassLoader(),new Class<?>[]{FullChunk.class},
                (proxy,method,args)->{throw new AssertionError("Unexpected chunk read "+method);});
        return p;
    }
    static Rocket rocket(Peer owner,long id)throws Exception{
        var p=(Rocket)UNSAFE.allocateInstance(Rocket.class);p.level=owner.level;p.justCreated=true;
        set(p,EntityFirework.class,"lifetime",100);
        set(p,Entity.class,"id",id);set(p,EntityElytraFirework.class,"followingPlayer",owner);
        set(p,EntityElytraFirework.class,"fireworkAge",1);
        ((World)owner.level).entities.put(id,p);return p;
    }
    static AddEntityPacket spawn(long id){var p=new AddEntityPacket();p.entityRuntimeId=id;return p;}
    static RemoveEntityPacket remove(long id){var p=new RemoveEntityPacket();p.eid=id;return p;}
    static long effects(Peer p,int duration){return p.delivered.stream().filter(v->v instanceof MovementEffectPacket m&&m.effectDuration==duration).count();}
    public static void main(String[] args)throws Exception{
        set(null,Server.class,"instance",UNSAFE.allocateInstance(Server.class));
        var bridge=new NavroFireworkBridge();var world=(World)UNSAFE.allocateInstance(World.class);world.entities=new HashMap<>();
        var player=peer(bridge,world,7);var other=peer(bridge,world,8);
        var first=rocket(player,100);var second=rocket(player,101);

        var cancelled=new DataPacketSendEvent(player,spawn(100));cancelled.setCancelled(true);bridge.onPacketSend(cancelled);
        check(cancelled.getPostSendActions().isEmpty(),"Cancelled spawn must not stage a boost");
        check(effects(player,1_000_000)==0,"Cancelled spawn must not send effect");

        other.dataPacket(spawn(100));check(effects(other,1_000_000)==0,"Remote viewer cannot own local boost");
        check(player.dataPacket(spawn(100)),"Real accepted spawn");
        check(player.delivered.get(0) instanceof AddEntityPacket,"Spawn goes first");
        check(effects(player,1_000_000)==1,"First attached spawn starts effect");
        player.dataPacket(spawn(100));check(effects(player,1_000_000)==1,"Duplicate visible spawn must not restart effect");
        player.setMotion(new Vector3(.1,.2,.3));
        check(player.delivered.get(player.delivered.size()-1) instanceof SetEntityMotionPacket,"Ordinary knockback passes while boosting");
        int suppressed=player.suppressed.size();
        first.onUpdate(1);
        check(player.suppressed.size()==suppressed+1,"Actual native propulsion is suppressed");
        check(player.motionZ>.3,"Native rocket/player motion code really executed");
        check(effects(player,1_000_000)==1,"Motion cannot repeatedly start boosts");

        first.damage=true;int before=player.delivered.size();
        first.onUpdate(2);
        check(player.suppressed.size()==suppressed+2,"Only direct propulsion is filtered under explosion update");
        check(player.delivered.size()==before+1,"Nested explosion knockback must pass");
        var damage=(SetEntityMotionPacket)player.delivered.get(before);
        check(damage.motionX==.75F&&damage.motionY==.45F&&damage.motionZ==-.25F,"Explosion motion unchanged");

        player.dataPacket(spawn(101));check(effects(player,1_000_000)==2,"Second attached rocket tracked");
        player.dataPacket(remove(999));check(effects(player,0)==0,"Unrelated removal cannot stop boost");
        player.dataPacket(remove(100));check(effects(player,0)==0,"Overlapping rocket keeps boost");
        player.dataPacket(remove(101));check(effects(player,0)==1,"Last removal stops boost");
        check(player.delivered.get(player.delivered.size()-2) instanceof RemoveEntityPacket,"Remove precedes stop effect");
        first.onUpdate(3);check(player.suppressed.size()==suppressed+2,"No active effect means no motion filtering");
        System.out.println("FIREWORK_BRIDGE_INTEGRATION_PASSED native propulsion, explosion knockback, overlaps, cancel/order and remote viewer");
    }
}
