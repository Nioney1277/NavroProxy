package ac.navro.proxy.check.impl.prediction;

import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.prediction.PredictionSample;
import ac.navro.anticheat.util.math.Box;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;


public final class GroundSpoof extends PredictionSubCheck {
    public GroundSpoof(final WirePlayerState player, PredictionSample sample) {
        super(player, sample, "GroundSpoof");
    }

    boolean check(final float offset) {
        if (!canCheck(offset)) {
            return false;
        }

        if (sample.vehicleData != null) {
            return false;
        }
        final var tolerance = sample.entityTolerance;
        if (tolerance.collided()) {
            return false;
        }




        if (sample.verticalCollision && !sample.onGround && hasBlockAboveHead()) {
            return false;
        }




        final boolean claimed = sample.getInputData().contains(PlayerAuthInputData.VERTICAL_COLLISION);
        if (claimed == sample.verticalCollision) {
            return false;
        }

        failCheck("o: " + offset + ", expect: " + sample.verticalCollision
                + ", actual: " + claimed);
        return true;
    }

    private boolean hasBlockAboveHead() {
        final Box box = sample.boundingBox;
        final float minX = box.minX + 1.0E-4F;
        final float minZ = box.minZ + 1.0E-4F;
        final float maxX = box.maxX - 1.0E-4F;
        final float maxZ = box.maxZ - 1.0E-4F;
        final Box probe = new Box(minX, box.maxY + 0.01F, minZ,
                maxX, box.maxY + 0.02F, maxZ);
        return sample.compensatedWorld.collectBlockCollisionEntries(probe).stream()
                .anyMatch(entry -> entry.block() != null && entry.shape().intersects(probe));
    }
}
