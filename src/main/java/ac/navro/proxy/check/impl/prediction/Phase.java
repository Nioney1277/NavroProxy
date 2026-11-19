package ac.navro.proxy.check.impl.prediction;

import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.prediction.PredictionSample;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;


public final class Phase extends PredictionSubCheck {
    public Phase(final WirePlayerState player, PredictionSample sample) {
        super(player, sample, "Phase");
    }

    boolean check(final float offset) {
        if (!canCheck(offset)) {
            return false;
        }

        if (sample.vehicleData != null) {
            return false;
        }

        final Box oldBox = sample.predictionStartBoundingBox;


        final Box clientBox = sample.boxAtProjectedPosition(sample.unvalidatedPosition);
        if (!oldBox.isValid() || !clientBox.isValid()
                || !sample.compensatedWorld.isChunkLoadedAt(clientBox.minX, clientBox.minZ)
                || !sample.compensatedWorld.isChunkLoadedAt(clientBox.maxX, clientBox.maxZ)) {
            return false;
        }
        final float epsilon = Math.max(Box.COLLISION_EPSILON, sample.getMaxOffset());
        for (final var entry : sample.compensatedWorld.collectBlockCollisionEntries(clientBox)) {
            if (entry.block() != null && newlyEntered(oldBox, clientBox, entry.shape(), epsilon)) {
                sample.observePhase("entered",offset,entry);
                failCheck("o: " + offset + ", entered solid block, shape=" + entry.shape());
                return true;
            }
        }




        for (final var entry : sample.movementCollisionEntries) {
            if (entry.block() != null && crossedBlockingShape(oldBox, clientBox,
                    sample.boundingBox, entry.shape(), sample.beforeCollision,
                    sample.afterCollision, epsilon)) {
                sample.observePhase("crossed",offset,entry);
                failCheck("o: " + offset + ", crossed solid block, shape=" + entry.shape());
                return true;
            }
        }
        return false;
    }

    static boolean newlyEntered(final Box oldBox, final Box clientBox,
                                final Box shape, final float epsilon) {


        return clientBox.expand(-epsilon).intersects(shape)
                && !oldBox.intersects(shape);
    }

    static boolean crossedBlockingShape(final Box start, final Box client,
                                        final Box predicted, final Box shape,
                                        final Vec3 requested, final Vec3 resolved,
                                        final float epsilon) {
        if (start.intersects(shape)) return false;

        if (Math.abs((start.maxX - start.minX) - (client.maxX - client.minX)) > epsilon
                || Math.abs((start.maxY - start.minY) - (client.maxY - client.minY)) > epsilon
                || Math.abs((start.maxZ - start.minZ) - (client.maxZ - client.minZ)) > epsilon) {
            return false;
        }
        final boolean crossed = (Math.abs(requested.x - resolved.x) > epsilon
                && crossedAxis(start.minX, start.maxX, client.minX, client.maxX,
                predicted.minX, predicted.maxX, shape.minX, shape.maxX, epsilon))
                || (Math.abs(requested.y - resolved.y) > epsilon
                && crossedAxis(start.minY, start.maxY, client.minY, client.maxY,
                predicted.minY, predicted.maxY, shape.minY, shape.maxY, epsilon))
                || (Math.abs(requested.z - resolved.z) > epsilon
                && crossedAxis(start.minZ, start.maxZ, client.minZ, client.maxZ,
                predicted.minZ, predicted.maxZ, shape.minZ, shape.maxZ, epsilon));
        return crossed && sweepsThrough(start.expand(-epsilon), client.expand(-epsilon), shape);
    }

    private static boolean crossedAxis(final float startMin, final float startMax,
                                       final float clientMin, final float clientMax,
                                       final float predictedMin, final float predictedMax,
                                       final float shapeMin, final float shapeMax,
                                       final float epsilon) {
        return startMax <= shapeMin + epsilon && clientMin >= shapeMax - epsilon
                && predictedMax <= shapeMin + epsilon
                || startMin >= shapeMax - epsilon && clientMax <= shapeMin + epsilon
                && predictedMin >= shapeMax - epsilon;
    }

    private static boolean sweepsThrough(final Box start, final Box end, final Box shape) {
        double entry = 0.0, exit = 1.0;
        for (int axis = 0; axis < 3; axis++) {
            final double startMin = axis == 0 ? start.minX : axis == 1 ? start.minY : start.minZ;
            final double startMax = axis == 0 ? start.maxX : axis == 1 ? start.maxY : start.maxZ;
            final double endMin = axis == 0 ? end.minX : axis == 1 ? end.minY : end.minZ;
            final double shapeMin = axis == 0 ? shape.minX : axis == 1 ? shape.minY : shape.minZ;
            final double shapeMax = axis == 0 ? shape.maxX : axis == 1 ? shape.maxY : shape.maxZ;
            final double delta = endMin - startMin;
            if (delta == 0.0) {
                if (startMin >= shapeMax || startMax <= shapeMin) return false;
                continue;
            }
            final double first = (delta > 0 ? shapeMin - startMax : shapeMax - startMin) / delta;
            final double last = (delta > 0 ? shapeMax - startMin : shapeMin - startMax) / delta;
            entry = Math.max(entry, first);
            exit = Math.min(exit, last);
            if (entry >= exit) return false;
        }
        return entry < exit;
    }
}
