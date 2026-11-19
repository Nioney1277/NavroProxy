package ac.navro.proxy.util.block;

import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;


class BlockReachReferenceTest {
    @Test void placementReachPolicyMatchesOriginalEyeCandidatesRangePrecedenceAndDiagnosticText() throws Exception {
        int cases=0;
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/navro-block-reach.bin.gz")))) {
            assertEquals(0x4e425231,in.readInt());int count=in.readInt();assertEquals(4096,count);
            for(int i=0;i<count;i++) {
                float x=in.readFloat(),y=in.readFloat(),z=in.readFloat(),eye=Float.intBitsToFloat(in.readInt());
                GameType type=GameType.values()[in.readInt()];int mode=in.readInt();float range=Float.intBitsToFloat(in.readInt());
                var p=new WirePlayerState();p.setResolvedPosition(new Vec3(x,y,z),Box.EMPTY,false);
                p.applyDimensions(EntityDimensions.changing(.6F,1.8F).withEyeHeight(eye));p.acknowledgeGameType(type);
                var attributes=new UpdateAttributesPacket();attributes.setRuntimeEntityId(p.runtimeId());
                if(mode==1||mode==3||mode==4||mode==5)attributes.getAttributes().add(new AttributeData("minecraft:block_interaction_range",0,1024,range,0));
                if(mode>=2)attributes.getAttributes().add(new AttributeData("block_interaction_range",0,1024,2.5F,0));
                p.apply(attributes);
                Vector3i target=in.readBoolean()?Vector3i.from(in.readInt(),in.readInt(),in.readInt()):null;
                assertEquals(in.readLong(),Double.doubleToRawLongBits(BlockReachUtil.maxReach(p)),"range case="+i);
                assertEquals(in.readLong(),Double.doubleToRawLongBits(BlockReachUtil.distanceSquared(p,target)),"distance case="+i);
                assertEquals(in.readBoolean(),BlockReachUtil.isFar(p,target),"far case="+i);
                if(target!=null){assertEquals(in.readUTF(),BlockReachUtil.describe(p,target),"detail case="+i);assertEquals(in.readUTF(),BlockReachUtil.distanceDetail(BlockReachUtil.describe(p,target)));}
                cases++;
            }assertEquals(-1,in.read());
        }assertEquals(4096,cases);
    }
}
