package ac.navro.proxy.collision;

import ac.navro.proxy.prediction.live.PredictionContext;

import ac.navro.proxy.prediction.live.*;
import ac.navro.anticheat.util.math.*;
import ac.navro.anticheat.collision.*;
import ac.navro.anticheat.util.MathUtil;


import ac.navro.anticheat.collision.CollisionQuery;
import ac.navro.anticheat.collision.ProtocolCollisionSolver;
import ac.navro.anticheat.collision.SneakEdgePhysics;
import ac.navro.anticheat.collision.SweptMovement;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import java.util.ArrayList;
import java.util.List;


public final class Collider {
    private Collider() { }
    private static float stepHeight(WirePlayerState player) { return player.wasdControlled() ? 1.0625F : 0.5625F; }

    public static boolean canFallAtLeast(WirePlayerState player, ProtocolCollisionWorld world, float x, float z, float distance) {
        Box support = supportBox(player.boundingBox(), x, z, distance);
        return world.collectColliders(world.entityCollisions(support, player.boundingBox()), support).isEmpty();
    }
    public static Vec3 maybeBackOffFromEdge(WirePlayerState player, ProtocolCollisionWorld world, Vec3 movement) {
        return backOffFromEdge(player, world, movement).movement();
    }
    public static SneakEdgePhysics.Result backOffFromEdge(WirePlayerState player, ProtocolCollisionWorld world, Vec3 movement) {
        Vec3 submitted = CollisionQuery.clamp(movement);
        boolean inputSneaking = player.inputData().contains(PlayerAuthInputData.SNEAK_DOWN);
        if (!SneakEdgePhysics.active(inputSneaking, player.sneaking(), player.onGround(), player.flag6()))
            return SneakEdgePhysics.unchanged(submitted);
        float step = stepHeight(player);
        Box query = CollisionQuery.buildCollisionQuery(player.boundingBox(), submitted, step);
        return SneakEdgePhysics.resolve(player.boundingBox(), world.collectColliders(
                world.entityCollisions(query, player.boundingBox()), query), submitted, step);
    }
    private static Box supportBox(Box box, float x, float z, float distance) {
        float minX = box.minX + 0.025F, maxX = box.maxX - 0.025F;
        float minZ = box.minZ + 0.025F, maxZ = box.maxZ - 0.025F;
        if (minX > maxX) minX = maxX = (box.minX + box.maxX) * 0.5F;
        if (minZ > maxZ) minZ = maxZ = (box.minZ + box.maxZ) * 0.5F;
        return new Box(minX + x, box.minY - distance, minZ + z, maxX + x, box.maxY - distance, maxZ + z);
    }

    public static void acceptInputPosition(WirePlayerState player, ProtocolCollisionWorld world, Vec3 accepted) {
        Box current = player.boundingBox();
        if (player.usesVehiclePosition() || !current.isValid()) {
            player.acceptInputPosition(accepted, List.of());
            return;
        }
        Vec3 correction = accepted.subtract(current.feetCenter());
        if (correction.x == 0 && correction.y == 0 && correction.z == 0) {
            player.acceptInputPosition(accepted, List.of());
            return;
        }
        Box query = CollisionQuery.buildCollisionQuery(current, correction, 0);
        List<Box> collisions = new ArrayList<>();
        for (CollisionShapeEntry entry : world.movementEntries(query)) collisions.add(entry.shape());
        collisions.addAll(world.nearbyEntityShapes(current));
        player.acceptInputPosition(accepted, collisions);
    }

    public static Vec3 collide(WirePlayerState player, ProtocolCollisionWorld world, Vec3 movement) {
        return collideMove(player, world, movement).movement;
    }
    public static SweptMovement.MoveResult collideMove(WirePlayerState player, ProtocolCollisionWorld world, Vec3 movement) {
        return resolve(player, world, movement, false, false).result();
    }
    public static Vec3 collideAndUpdate(WirePlayerState player, ProtocolCollisionWorld world, Vec3 movement) {
        return collideAndUpdateMove(player, world, movement).movement;
    }
    public static SweptMovement.MoveResult collideAndUpdateMove(WirePlayerState player, ProtocolCollisionWorld world, Vec3 movement) {
        var move = resolve(player, world, movement, true, true);
        updatePenetration(player, move.ordinaryOverlapDepth());
        return move.result();
    }
    public static SweptMovement.MoveResult collideBlockPush(WirePlayerState player, ProtocolCollisionWorld world, Vec3 movement) {
        var move = resolve(player, world, movement, false, true);
        updatePenetration(player, move.ordinaryOverlapDepth());
        return move.result();
    }
    private static void updatePenetration(WirePlayerState player, float ordinaryOverlapDepth) {
        player.setCollisionPenetratingFrames(ordinaryOverlapDepth >= Box.COLLISION_EPSILON
                ? Math.min(2, player.collisionPenetratingFrames() + 1) : 0);
        player.collisionPhysics.updatePushTowardsClosestSpace(player.pushTowardsClosestSpace());
        player.collisionPhysics.requestClosestSpace(player.pushTowardsClosestSpace(), ordinaryOverlapDepth >= Box.COLLISION_EPSILON);
        player.collisionPhysics.finishOverlapResolution();
    }
    private static ProtocolCollisionSolver.ResolvedMove resolve(WirePlayerState player, ProtocolCollisionWorld world,
                                                                Vec3 movement, boolean commitShapes, boolean commitState) {
        Vec3 submitted = CollisionQuery.clamp(movement);
        Box box = player.boundingBox();
        float step = stepHeight(player);
        Box query = CollisionQuery.buildCollisionQuery(box, submitted, step);
        List<CollisionShapeEntry> entries = new ArrayList<>(world.movementEntries(query));
        if (commitShapes) player.collisionPhysics.commitMovement(query, entries);
        List<Box> collisions = new ArrayList<>(entries.size());
        for (CollisionShapeEntry entry : entries) collisions.add(entry.shape());
        List<Box> nearby = world.nearbyEntityShapes(box);
        for (Box shape : nearby) {
            collisions.add(shape);
            entries.add(new CollisionShapeEntry(shape, null));
        }
        if (commitShapes) player.setMovementCollisionEntries(entries);
        boolean rememberedOneWay = player.collisionPhysics.hasOneWayCollision(box, commitState);
        boolean oneWay = player.collisionPenetratingFrames() >= 2 || rememberedOneWay;
        Vec3 minimum = oneWay ? nearby.isEmpty() ? Vec3.ZERO : EntityCollisionPhysics.SOLID_DEPENETRATION.clone() : new Vec3(1, 1, 1);


        return ProtocolCollisionSolver.solveResolvedDetailed(submitted, box, collisions, minimum, step,
                player.onGround() || player.flying());
    }

 public static Vec3 maybeBackOffFromEdge(PredictionContext p,Vec3 v){p.syncWire();return ac.navro.proxy.collision.Collider.maybeBackOffFromEdge(p.wire,p.session.collisionWorld(),v);}
 public static SneakEdgePhysics.Result backOffFromEdge(PredictionContext p,Vec3 v){p.syncWire();return ac.navro.proxy.collision.Collider.backOffFromEdge(p.wire,p.session.collisionWorld(),v);}
 public static SweptMovement.MoveResult collideMove(PredictionContext p,Vec3 v){p.syncWire();return ac.navro.proxy.collision.Collider.collideMove(p.wire,p.session.collisionWorld(),v);}
 public static Vec3 collide(PredictionContext p,Vec3 v){return collideMove(p,v).movement;}
 public static SweptMovement.MoveResult collideAndUpdateMove(PredictionContext p,Vec3 v){p.syncWire();var r=ac.navro.proxy.collision.Collider.collideAndUpdateMove(p.wire,p.session.collisionWorld(),v);p.movementCollisionEntries=p.wire.movementCollisionEntries();return r;}

}
