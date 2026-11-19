package ac.navro.proxy.collision;

import ac.navro.anticheat.collision.CollisionQuery;
import ac.navro.anticheat.collision.ProtocolCollisionSolver;
import ac.navro.anticheat.collision.SweptMovement;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.compensated.cache.entity.EntityCache;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;

import java.util.List;


public final class ProtocolCollisionWorld {
    private final CompensatedWorld world;
    private final int dimension;
    private final java.util.Map<Long, EntityCache> entities;
    private final long localRuntimeId;
    private final boolean localStackable;
    private final CollisionPhysicsState collisionPhysics;

    public ProtocolCollisionWorld(CompensatedWorld world, int dimension) {
        this(world, dimension, java.util.Map.of());
    }

    public ProtocolCollisionWorld(CompensatedWorld world, int dimension,
                                  java.util.Map<Long, EntityCache> entities) {
        this(world, dimension, entities, -1L);
    }

    public ProtocolCollisionWorld(CompensatedWorld world, int dimension,
                                  java.util.Map<Long, EntityCache> entities,
                                  long localRuntimeId) {
        this(world, dimension, entities, localRuntimeId, false);
    }

    public ProtocolCollisionWorld(CompensatedWorld world, int dimension,
                                  java.util.Map<Long, EntityCache> entities,
                                  long localRuntimeId, boolean localStackable) {
        this(world, dimension, entities, localRuntimeId, localStackable, world.collisionPhysics());
    }

    private ProtocolCollisionWorld(CompensatedWorld world, int dimension,
                                    java.util.Map<Long, EntityCache> entities,
                                    long localRuntimeId, boolean localStackable, CollisionPhysicsState collisionPhysics) {
        this.world = world;
        this.dimension = dimension;
        this.entities = entities;
        this.localRuntimeId = localRuntimeId;
        this.localStackable = localStackable;
        this.collisionPhysics = collisionPhysics;
    }

    public List<Box> collect(Box query) {
        return collect(query, query);
    }

    public List<Box> collect(Box query, Box actor) {
        java.util.ArrayList<Box> result = new java.util.ArrayList<>(blockEntries(query).stream().map(CollisionShapeEntry::shape).toList());
        result.addAll(nearbyEntityShapes(actor));
        return List.copyOf(result);
    }

    public List<Box> collectMovement(Box query, Box actor) {
        java.util.ArrayList<Box> result = new java.util.ArrayList<>(movementEntries(query).stream().map(CollisionShapeEntry::shape).toList());
        result.addAll(nearbyEntityShapes(actor));
        return List.copyOf(result);
    }


    public List<Box> collectColliders(List<Box> entities, Box query) {
        var result = new java.util.ArrayList<>(entities);
        for (var entry : blockEntries(query)) result.add(entry.shape());
        return List.copyOf(result);
    }
    public List<Box> entityCollisions(Box query, Box actor) {
        var result = new java.util.ArrayList<Box>();
        for (Box shape : nearbyEntityShapes(actor)) if (query.expand(1.0E-7F).intersects(shape)) result.add(shape);
        return List.copyOf(result);
    }

    public List<Box> nearbyEntityShapes(Box actor) {
        return EntityCollisionPhysics.nearbyShapes(collisionPhysics.active() ? collisionPhysics.actors()
                : EntityCollisionPhysics.snapshotActors(entities), actor, localRuntimeId, dimension, localStackable);
    }

    public List<CollisionShapeEntry> blockEntries(Box query) {
        return world.collisionEntries(query, dimension, collisionPhysics);
    }
    public List<CollisionShapeEntry> movementEntries(Box query) {
        return world.movementCollisionEntries(query, dimension, collisionPhysics);
    }
    public CollisionPhysicsState collisionPhysics() { return collisionPhysics; }


    public ProtocolCollisionWorld replay(long tick, CollisionPhysicsState.Snapshot saved) {
        CollisionPhysicsState state = new CollisionPhysicsState();
        state.restore(saved);
        state.begin(world, entities, tick, saved);
        return new ProtocolCollisionWorld(world, dimension, entities, localRuntimeId, localStackable, state);
    }

    public CompensatedWorld world() {
        return world;
    }

    public int dimension() {
        return dimension;
    }

    public SweptMovement.MoveResult solve(Box actor, Vec3 movement,
                                          float maxDepenetration, boolean yxz) {
        Vec3 submitted = CollisionQuery.clamp(movement);
        Box query = CollisionQuery.buildCollisionQuery(actor, submitted, 0.5625F);
        return ProtocolCollisionSolver.solve(submitted, actor, collect(query, actor),
                new Vec3(maxDepenetration, maxDepenetration, maxDepenetration), yxz);
    }
}
