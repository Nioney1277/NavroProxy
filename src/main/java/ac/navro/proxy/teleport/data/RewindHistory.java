package ac.navro.proxy.teleport.data;

import ac.navro.proxy.data.input.PredictionData;
import ac.navro.anticheat.data.MovementAabbSnapshot;
import ac.navro.proxy.prediction.live.PredictionContext;
import ac.navro.anticheat.util.math.Vec3;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket;


public record RewindHistory(long tick, Vec3 position, PredictionData data,
                            MovementAabbSnapshot aabb, float yOffset, boolean onGround) {
    public RewindHistory {
        position = position.clone();
        data = copy(data);
    }

    public RewindHistory(long tick, Vec3 position, PredictionData data) {
        this(tick, position, data, null, 0, data.before().y != data.after().y && data.before().y < 0);
    }

    private static PredictionData copy(PredictionData value) {
        return new PredictionData(value.before().clone(), value.after().clone(), value.tickEnd().clone());
    }

    @Override public Vec3 position() { return position.clone(); }
    @Override public PredictionData data() { return copy(data); }

    public static RewindHistory capture(final PredictionContext player, final long tick, final float yOffset) {
        return capture(player, tick, yOffset, player.predictionResult);
    }

    public static RewindHistory capture(final PredictionContext player, final long tick, final float yOffset,
                                        final PredictionData data) {
        final boolean physicalActor = player.vehicleData == null && player.boundingBox.isValid();
        final Vec3 physical = physicalActor ? player.boundingBox.feetCenter() : player.position.clone();
        final MovementAabbSnapshot aabb = physicalActor ? new MovementAabbSnapshot(player.dimensions,
                physical, player.boundingBox, player.aabbResizeRevision()) : null;
        return new RewindHistory(tick, physical.up(yOffset), data, aabb, yOffset, player.onGround);
    }

    public boolean matches(final Vec3 wirePosition, final Vec3 delta, final boolean ground) {
        return same(position, wirePosition) && same(data.tickEnd(), delta) && onGround == ground;
    }

    private static boolean same(final Vec3 first, final Vec3 second) {
        return first.x == second.x && first.y == second.y && first.z == second.z;
    }

    public CorrectPlayerMovePredictionPacket createPacket(final boolean vehicle) {
        final CorrectPlayerMovePredictionPacket packet = new CorrectPlayerMovePredictionPacket();
        packet.setPosition(position.toVector3f());
        packet.setDelta(data.tickEnd().toVector3f());
        packet.setOnGround(onGround);
        packet.setTick(tick);
        packet.setVehicleRotation(Vector2f.ZERO);
        packet.setPredictionType(vehicle ? org.cloudburstmc.protocol.bedrock.data.PredictionType.VEHICLE
                : org.cloudburstmc.protocol.bedrock.data.PredictionType.PLAYER);
        return packet;
    }
}
