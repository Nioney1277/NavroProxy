package ac.navro.proxy.state;

import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.anticheat.data.MovementAabbSnapshot;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.prediction.fluid.ProtocolFluidStateTracker;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WireSnapshotReplayTest {
    @Test void snapshotInputAndAttributesCannotBeMutatedByAReplayCandidate() {
        WirePlayerState player = new WirePlayerState();
        StartGamePacket start = new StartGamePacket();
        start.setRuntimeEntityId(7);
        start.setPlayerPosition(Vector3f.from(0, 64, 0));
        player.apply(start);
        UpdateAttributesPacket attributes = new UpdateAttributesPacket();
        attributes.setRuntimeEntityId(7);
        attributes.setAttributes(List.of(new AttributeData("minecraft:movement", 0, 1, 0.1F, 0.1F)));
        player.apply(attributes);
        PlayerAuthInputPacket input = new PlayerAuthInputPacket();
        input.setTick(0);
        input.setPosition(Vector3f.from(0, 65.62001F, 0));
        input.setRotation(Vector3f.ZERO);
        input.setMotion(Vector2f.from(0, 1));
        input.setRawMoveVector(Vector2f.from(0, 1));
        ProtocolFluidStateTracker fluid = new ProtocolFluidStateTracker();
        player.apply(input, new CompensatedWorld(), fluid);
        WireTickSnapshot saved = player.snapshot(fluid);
        saved.input().z = 90;
        saved.attributes().get("minecraft:movement").setValue(8);
        player.input().z = 70;
        player.attributes().get("minecraft:movement").setValue(9);
        player.restore(saved, fluid);
        assertEquals(1, player.input().z);
        assertEquals(0.1F, player.attributes().get("minecraft:movement").getValue());
        assertEquals(1, saved.input().z);
    }

    @Test void replayOnlyRebuildsEqualDimensionsWhenResizeRevisionChanged() {
        WirePlayerState player = new WirePlayerState();
        player.acceptPosition(new Vec3(256.31F, 75, 82.29F));
        MovementAabbSnapshot before = player.snapshotAabb();
        var box = player.boundingBox();
        player.prepareReplayAabb(before, before.resizeRevision());
        assertSame(box, player.boundingBox());
        EntityDimensions dimensions = before.dimensions();
        MovementAabbSnapshot acknowledged = new MovementAabbSnapshot(dimensions, before.position(), before.box(),
                before.resizeRevision() + 2);
        player.prepareReplayAabb(acknowledged, before.resizeRevision());
        assertNotSame(box, player.boundingBox());
        assertEquals(acknowledged.resizeRevision(), player.aabbResizeRevision());
    }
}
