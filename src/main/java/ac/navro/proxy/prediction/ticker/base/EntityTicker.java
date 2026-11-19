package ac.navro.proxy.prediction.ticker.base;

import ac.navro.proxy.prediction.live.PredictionContext;


import ac.navro.anticheat.util.math.Mutable;
import ac.navro.proxy.collision.Collider;
import ac.navro.proxy.compensated.world.CompensatedWorldImpl;
import ac.navro.proxy.data.block.NavroBlockState;
import ac.navro.proxy.prediction.StickyBlockPhysics;

import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;

import ac.navro.anticheat.util.MathUtil;

import ac.navro.anticheat.util.math.Vec3;


public class EntityTicker {
    protected final PredictionContext player;
    public EntityTicker(PredictionContext player){this.player=player;}

    public void tick() {
        this.baseTick();
    }

    public void baseTick() {
        player.inBlockState = null;

    }

    protected void applyEffectsFromBlocks() {
        if (player.onGround) {

            final org.cloudburstmc.math.vector.Vector3i lv = player.compensatedWorld.getStandingBlockPosition(player.boundingBox);
            player.compensatedWorld.getBlockState(lv, 0).onSteppedOn(player, lv);
        }

        org.cloudburstmc.math.vector.Vector3i min = org.cloudburstmc.math.vector.Vector3i.from((int) Math.floor(player.boundingBox.minX + 0.001D), (int) Math.floor(player.boundingBox.minY + 0.001D), (int) Math.floor(player.boundingBox.minZ + 0.001D));
        org.cloudburstmc.math.vector.Vector3i max = org.cloudburstmc.math.vector.Vector3i.from((int) Math.floor(player.boundingBox.maxX - 0.001D), (int) Math.floor(player.boundingBox.maxY - 0.001D), (int) Math.floor(player.boundingBox.maxZ - 0.001D));

        final Mutable mutable = new Mutable();
        for (int i = min.getX(); i <= max.getX(); ++i) {for (int j = min.getY(); j <= max.getY(); ++j) {for (int k = min.getZ(); k <= max.getZ(); ++k) {
            mutable.set(i, j, k);

            final NavroBlockState state = player.compensatedWorld.getBlockState(i, j, k, 0);
            if (!state.isHoneyBlock()) state.entityInside(player, mutable);
        }}}



        if (!player.noClip()) {
            player.velocity = ac.navro.proxy.prediction.ProtocolBlockEffects.applyBubble(player.velocity, ac.navro.proxy.prediction.ProtocolBlockEffects.bubbleContacts(player.session.world(),player.boundingBox,player.session.world().dimension()));
        }


        for (int x = (int) Math.floor(player.boundingBox.minX);
                x <= (int) Math.floor(Math.nextDown(player.boundingBox.maxX)); x++) {
            for (int y = (int) Math.floor(player.boundingBox.minY);
                    y <= (int) Math.floor(Math.nextDown(player.boundingBox.maxY)); y++) {
                for (int z = (int) Math.floor(player.boundingBox.minZ);
                        z <= (int) Math.floor(Math.nextDown(player.boundingBox.maxZ)); z++) {
                    final NavroBlockState state = player.compensatedWorld.getBlockState(x, y, z, 0);
                    if (state.isHoneyBlock()) state.entityInside(player, mutable.set(x, y, z));
                }
            }
        }
    }

    public final void doSelfMove(Vec3 vec3) {

        player.bounceGravityCorrection = null;
        player.movementCollisionEntries = java.util.List.of();
        if (player.noClip()) {
            player.setResolvedMovement(player.boundingBox.offset(vec3), true);
            player.horizontalCollision = player.verticalCollision = player.onGround = false;
            player.beforeCollision = player.afterCollision = vec3.clone();
            return;
        }

        if (player.stuckSpeedMultiplier.lengthSquared() > 1.0E-7) {
            vec3 = vec3.multiply(player.stuckSpeedMultiplier);
            player.stuckSpeedMultiplier = Vec3.ZERO;
            player.velocity = Vec3.ZERO.clone();
        }

        final boolean wasOnGround = player.onGround;
        Vec3 oldVec3 = vec3.clone();
        player.lavaSampler.stage("beforeEdgeBackoff");
        player.ceilingSampler.stage("beforeEdgeBackoff");
        player.collisionSampler.stage("beforeEdgeBackoff");
        player.trapdoorSampler.stage("beforeEdgeBackoff");
        player.movementSampler.stage("beforeEdgeBackoff");
        final var edge = Collider.backOffFromEdge(player, vec3);
        player.velocity = edge.applyVelocity(player.velocity);
        final var solved = Collider.collideAndUpdateMove(player, vec3 = edge.movement());
        Vec3 vec32 = solved.movement;
        player.lavaSampler.edgeBackoff(oldVec3, vec3, vec32);
        player.ceilingSampler.edgeBackoff(oldVec3, vec3, vec32);
        player.collisionSampler.edgeBackoff(oldVec3, vec3, vec32);
        player.trapdoorSampler.edgeBackoff(oldVec3, vec3, vec32);
        player.movementSampler.edgeBackoff(oldVec3, vec3, vec32);
        player.setResolvedMovement(solved.aabb, true);

        boolean bl = !MathUtil.equal(vec3.x, vec32.x);
        boolean bl2 = !MathUtil.equal(vec3.z, vec32.z);
        player.horizontalCollision = bl || bl2;
        player.verticalCollision = vec3.y != vec32.y;

        player.onGround = player.verticalCollision
                ? vec3.y < 0.0F
                : vec3.y == 0.0F && wasOnGround;



        if (vec3.y == 0 && player.bestPossibility.getVelocity().y == 0 && player.bestPossibility.getType() == VectorType.VELOCITY && !MathUtil.equal(player.lastTickFinalVelocity.y, 0)) {
            player.verticalCollision = Collider.collide(player, player.lastTickFinalVelocity.clone()).y != player.lastTickFinalVelocity.y;
            player.onGround = player.verticalCollision && player.lastTickFinalVelocity.y < 0;
        }

        final StickyBlockPhysics.Restitution restitution = StickyBlockPhysics.restitute(
                player.boundingBox, player.movementCollisionEntries, player.velocity, vec3, vec32,
                bl, player.verticalCollision, bl2,
                player.getFlagTracker().has("SNEAKING"));
        player.velocity = restitution.velocity();
        player.bounceGravityCorrection = restitution.gravityCorrection();

        player.beforeCollision = vec3.clone();
        player.afterCollision = vec32.clone();
        player.lavaSampler.stage("afterCollision");
        player.ceilingSampler.stage("afterCollision");
        player.collisionSampler.stage("afterCollision");
        player.trapdoorSampler.stage("afterCollision");
        player.movementSampler.stage("afterCollision");

    }

}
