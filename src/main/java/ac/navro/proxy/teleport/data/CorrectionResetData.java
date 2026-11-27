package ac.navro.proxy.teleport.data;

import ac.navro.anticheat.data.input.InputPosition;
import ac.navro.anticheat.util.math.Vec3;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;


public final class CorrectionResetData extends TeleportData {
    private final long sourceTick;
    private final float yOffset;
    private final Vec3 physicalTarget;
    private final Vec3 setbackVelocity;
    private volatile boolean motionSent;
    private long externalMotionSequence = -1;
    private ac.navro.proxy.prediction.engine.data.Vector includedExternalImpulse;
    public record VehicleState(ac.navro.proxy.compensated.cache.entity.EntityCache actor,
                               Vec3 velocity,float yaw,boolean grounded,Float angularVelocity) { }
    private VehicleState vehicle;
    public VehicleState vehicle() { return vehicle; }
    public void bindVehicle(ac.navro.proxy.compensated.cache.entity.EntityCache actor,Vec3 velocity,
                            float yaw,boolean grounded,Float angularVelocity) {
        vehicle=new VehicleState(actor,velocity.clone(),yaw,grounded,angularVelocity);
    }

    public void bindExternalMotion(long sequence, ac.navro.proxy.prediction.engine.data.Vector impulse) {
        externalMotionSequence = sequence;
        includedExternalImpulse = impulse;
    }

    public long externalMotionSequence() { return externalMotionSequence; }
    public ac.navro.proxy.prediction.engine.data.Vector includedExternalImpulse() { return includedExternalImpulse; }

    public CorrectionResetData(final RewindHistory source) {
        this(source, null);
    }

    public Vec3 setbackVelocity() { return setbackVelocity == null ? null : setbackVelocity.clone(); }

    public void motionSent() { motionSent = true; }

    public Vec3 deliveredVelocity() { return motionSent ? setbackVelocity() : null; }

    public CorrectionResetData(final RewindHistory source, final Vec3 velocity) {
        super(source.position(), source.onGround());
        setbackVelocity = velocity == null || !Float.isFinite(velocity.x) || !Float.isFinite(velocity.y)
                || !Float.isFinite(velocity.z) || velocity.lengthSquared() == 0 ? null : velocity.clone();
        sourceTick = source.tick();
        yOffset = source.yOffset();


        physicalTarget = source.position().down(yOffset);
    }

    public long sourceTick() { return sourceTick; }
    public Vec3 nativeTargetFoot() { return physicalTarget.clone(); }

    public boolean confirmedBy(final PlayerAuthInputPacket packet) {
        if(vehicle!=null && (packet.getPredictedVehicle()!=vehicle.actor().runtimeId()
                ||!packet.getInputData().contains(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE)))return false;
        return packet.getTick() >= 0 && matchesConfirmation(new Vec3(packet.getPosition()),
                packet.getInputData().contains(PlayerAuthInputData.HANDLE_TELEPORT));
    }


    public Vec3 confirmedFoot(final PlayerAuthInputPacket packet) {
        if (!confirmedBy(packet)) throw new IllegalArgumentException("Correction target has not been confirmed");
        return InputPosition.capture(packet.getPosition(), yOffset).physicalTarget(physicalTarget);
    }
}
