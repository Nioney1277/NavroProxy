import ac.navro.anticheat.Navro;
import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.packets.input.PostAuthInputPackets;
import ac.navro.anticheat.util.math.Vec3;
import cn.nukkit.event.server.DataPacketReceiveEvent;
import cn.nukkit.network.protocol.PlayerAuthInputPacket;
import cn.nukkit.network.protocol.types.AuthInputAction;
import cn.nukkit.math.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroPostInputFixture {
 static void vec(DataOutputStream out,Vector3f v)throws IOException{out.writeFloat(v.x);out.writeFloat(v.y);out.writeFloat(v.z);}
 static void vec(DataOutputStream out,Vec3 v)throws IOException{out.writeFloat(v.x);out.writeFloat(v.y);out.writeFloat(v.z);}
 static int flags(PlayerAuthInputPacket p){return (p.getInputData().contains(AuthInputAction.HORIZONTAL_COLLISION)?1:0)|(p.getInputData().contains(AuthInputAction.VERTICAL_COLLISION)?2:0);}
 public static void main(String[] args)throws Exception{
  int count=0;Random random=new Random(0x504f5354494e5055L);
  try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))){
   out.writeInt(0x50414931);out.writeInt(288);
   for(boolean spawned:new boolean[]{false,true})for(boolean exempt:new boolean[]{false,true})for(boolean movementExempt:new boolean[]{false,true})for(boolean cancelled:new boolean[]{false,true})for(int mode=0;mode<3;mode++)for(float offset:new float[]{1.62001F,.375F,0})for(int resync:new int[]{0,5}){
    var p=new NavroPlayer();Navro.player=p;p.session.spawned=spawned;p.exempt=exempt;p.movementExempt=movementExempt;p.offset=offset;p.tickSinceBlockResync=resync;p.teleports.mode=mode;
    p.position=new Vec3(random.nextFloat()*200-100,64+random.nextFloat(),random.nextFloat()*200-100);p.velocity=new Vec3(.125F,-.0784F,.25F);p.horizontalCollision=true;p.verticalCollision=false;
    var packet=new PlayerAuthInputPacket();packet.setTick(81);packet.setPosition(new Vector3f(700,800,900));packet.setDelta(new Vector3f(1,2,3));packet.setMotion(new Vector2(0,0));packet.setInputData(new HashSet<>(Set.of(AuthInputAction.VERTICAL_COLLISION)));
    out.writeBoolean(spawned);out.writeBoolean(exempt);out.writeBoolean(movementExempt);out.writeBoolean(cancelled);out.writeByte(mode);out.writeFloat(offset);out.writeInt(resync);vec(out,p.position);vec(out,p.velocity);vec(out,packet.getPosition());vec(out,packet.getDelta());out.writeInt(flags(packet));
    var event=new DataPacketReceiveEvent(p.session,packet);event.setCancelled(cancelled);new PostAuthInputPackets().onPacket(event);
    out.writeBoolean(!event.isCancelled());vec(out,packet.getPosition());vec(out,packet.getDelta());out.writeInt(flags(packet));out.writeInt(p.tickSinceBlockResync);out.writeBoolean(p.doingInventoryAction);out.writeInt(p.thisTickRiptideLevel);out.writeBoolean(p.thisTickSpinAttack);out.writeBoolean(p.thisTickOnGroundSpinAttack);out.writeBoolean(p.hasDepthStrider);out.writeBoolean(p.riptideTickStartState!=null);out.writeInt(p.teleports.history.size());if(!p.teleports.history.isEmpty())vec(out,p.teleports.history.get(81L).packet().getPosition());out.writeInt(p.finishedFluid);out.writeInt(p.teleports.polls);count++;
   }
  }
  System.out.println("Original PostAuthInputPackets: "+count+" cases; exact listener/TickData/correctInputData; session, diagnostic and physics snapshot boundaries are explicit test doubles.");
 }
}
