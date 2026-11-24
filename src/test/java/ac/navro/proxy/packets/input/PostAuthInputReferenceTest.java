package ac.navro.proxy.packets.input;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.prediction.RiptidePhysics;
import ac.navro.proxy.teleport.data.TeleportData;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class PostAuthInputReferenceTest {
    private static Vec3 vec(DataInputStream in)throws Exception{return new Vec3(in.readFloat(),in.readFloat(),in.readFloat());}
    private static void same(Vec3 expected,Vector3f actual){assertEquals(expected.x,actual.getX());assertEquals(expected.y,actual.getY());assertEquals(expected.z,actual.getZ());}
    private static int flags(PlayerAuthInputPacket p){return(p.getInputData().contains(PlayerAuthInputData.HORIZONTAL_COLLISION)?1:0)|(p.getInputData().contains(PlayerAuthInputData.VERTICAL_COLLISION)?2:0);}
    @Test void originalHighestPriorityListenerMatchesAllRepresentablePostControlBranches()throws Exception{
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/navro-post-input.bin.gz")))){
            assertEquals(0x50414931,in.readInt());int count=in.readInt();assertEquals(288,count);
            for(int row=0;row<count;row++){
                boolean spawned=in.readBoolean(),exempt=in.readBoolean(),movementExempt=in.readBoolean(),cancelled=in.readBoolean();
                int mode=in.readUnsignedByte();float offset=in.readFloat();int resync=in.readInt();Vec3 canonical=vec(in),velocity=vec(in),rawPosition=vec(in),rawDelta=vec(in);int rawFlags=in.readInt();
                var s=new SessionState();s.setProtocolVersion(2168);s.player().setProtocolVersion(2168);
                var start=new StartGamePacket();start.setRuntimeEntityId(7);start.setPlayerPosition(canonical.toVector3f());s.observeBackend(start);
                if(spawned)s.observeClient(new SetLocalPlayerAsInitializedPacket());
                if(offset!=1.62001F){
                    var entity=new AddEntityPacket();entity.setRuntimeEntityId(42);entity.setUniqueEntityId(42);
                    entity.setIdentifier(offset==.375F?"minecraft:boat":"minecraft:pig");entity.setPosition(Vector3f.ZERO);entity.setMotion(Vector3f.ZERO);
                    entity.getMetadata().put(EntityDataTypes.WIDTH,1F);entity.getMetadata().put(EntityDataTypes.HEIGHT,1F);s.observeBackend(entity);
                    var link=new SetEntityLinkPacket();link.setEntityLink(new EntityLinkData(42,7,EntityLinkData.Type.RIDER,false,false,0));s.observeBackend(link);
                }
                assertEquals(offset,s.playerYOffset());s.setDetectionEnabled(true);s.player().setVelocity(velocity);s.player().setCollisionResult(true,false,false);
                s.player().setFlying(movementExempt);s.player().setExempted(exempt);s.player().tickSinceBlockResync=resync;s.player().doingInventoryAction=true;
                var context=s.authInputPackets().context();context.thisTickRiptideLevel=3;
                context.thisTickSpinAttack=context.thisTickOnGroundSpinAttack=context.hasDepthStrider=true;context.riptideTickStartState=RiptidePhysics.snapshot(context);
                if(mode!=0){final boolean hard=mode==2;s.teleports().getQueuedTeleports().add(new TeleportData(canonical){@Override public boolean isHard(){return hard;}});}
                var packet=AuthInputPacketsTest.input(81,0,0,0,Vec3.ZERO);packet.setPosition(rawPosition.toVector3f());packet.setDelta(rawDelta.toVector3f());
                if((rawFlags&1)!=0)packet.getInputData().add(PlayerAuthInputData.HORIZONTAL_COLLISION);
                if((rawFlags&2)!=0)packet.getInputData().add(PlayerAuthInputData.VERTICAL_COLLISION);
                boolean forwarded=PostAuthInputPackets.onPacket(s,packet,cancelled);
                assertEquals(in.readBoolean(),forwarded,"row="+row);same(vec(in),packet.getPosition());same(vec(in),packet.getDelta());assertEquals(in.readInt(),flags(packet));
                assertEquals(in.readInt(),s.player().tickSinceBlockResync);assertEquals(in.readBoolean(),s.player().doingInventoryAction);
                assertEquals(in.readInt(),context.thisTickRiptideLevel);assertEquals(in.readBoolean(),context.thisTickSpinAttack);
                assertEquals(in.readBoolean(),context.thisTickOnGroundSpinAttack);assertEquals(in.readBoolean(),context.hasDepthStrider);
                assertEquals(in.readBoolean(),context.riptideTickStartState!=null);int history=in.readInt();assertEquals(history,s.teleports().getAuthInputHistory().size());
                if(history!=0)same(vec(in),s.teleports().getAuthInputHistory().get(81L).packet().getPosition());
                assertEquals(exempt?0:1,in.readInt());assertEquals(!exempt&&spawned&&mode==0?1:0,in.readInt());
            }
            assertEquals(-1,in.read());
        }
    }
}
