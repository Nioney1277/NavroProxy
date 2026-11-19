package ac.navro.proxy.packets.input.legacy;

import ac.navro.anticheat.util.math.Vec3;
import org.cloudburstmc.math.vector.Vector3f;


public final class InputPositionDecoder {
    public static boolean usesMovementCoordinates(final boolean spawned, final boolean handleTeleport) {


        return spawned || handleTeleport;
    }

    static Vec3 decode(final Vector3f position, final float movementYOffset,
                       final boolean movementCoordinates) {
        return new Vec3(position.getX(), position.getY() - (movementCoordinates ? movementYOffset : 0),
                position.getZ());
    }
}
