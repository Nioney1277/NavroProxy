package ac.navro.proxy.collision;

import ac.navro.proxy.collision.block.ShulkerCollisionState;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.compensated.cache.entity.EntityCache;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;


public final class CollisionPhysicsState {
    private long inputTick = Long.MIN_VALUE;
    private boolean active, replaying, enforceBoatStep, collidedPreviousTick;
    private float selectedStartY;
    private EntityCollisionPhysics.Tolerance tolerance;
    private List<EntityCollisionPhysics.Actor> actors = List.of();
    private List<ShulkerCollisionState.Frame> shulkers = List.of();
    private Map<Query, List<CollisionShapeEntry>> blockQueries = new HashMap<>();
    private List<CollisionShapeEntry> movementEntries = List.of();
    private Box movementQuery;
    private Vec3 blockPush = Vec3.ZERO.clone();
    private List<Box> oneWayBlocks = List.of();
    private boolean pushTowardsClosestSpace;
    private boolean closestSpaceRequested, inputStartClosestSpaceRequested;
    private boolean resolveAcceptedOverlaps, inputStartResolveAcceptedOverlaps;

    public record Query(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        static Query of(final Box box) {
            return new Query(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        }
    }

    public record Snapshot(long inputTick, List<EntityCollisionPhysics.Actor> actors,
                           List<ShulkerCollisionState.Frame> shulkers,
                           Map<Query, List<CollisionShapeEntry>> blockQueries,
                           List<CollisionShapeEntry> movementEntries, Box movementQuery,
                           EntityCollisionPhysics.Tolerance tolerance, boolean enforceBoatStep,
                           float selectedStartY, Vec3 blockPush,
                           List<Box> oneWayBlocks, boolean pushTowardsClosestSpace,
                           boolean inputStartResolveAcceptedOverlaps,
                           boolean resolveAcceptedOverlaps,
                           boolean closestSpaceRequested, boolean inputStartClosestSpaceRequested) {
        public Snapshot {
            actors = List.copyOf(actors);
            shulkers = List.copyOf(shulkers);
            Map<Query, List<CollisionShapeEntry>> queries = new HashMap<>();
            blockQueries.forEach((query, entries) -> queries.put(query, List.copyOf(entries)));
            blockQueries = Map.copyOf(queries);
            movementEntries = List.copyOf(movementEntries);
            blockPush = blockPush.clone();
            oneWayBlocks = List.copyOf(oneWayBlocks);
        }
        @Override public Vec3 blockPush() { return blockPush.clone(); }
    }

    public void begin(final CompensatedWorld world, final Map<Long, EntityCache> entities, final long tick, final Snapshot saved) {
        collidedPreviousTick = inputTick == tick - 1 && tolerance != null && tolerance.collided();
        inputTick = tick;
        active = true;
        replaying = saved != null;
        beginOverlapResolutionInput(saved);
        beginClosestSpaceInput(saved);
        tolerance = saved == null ? null : saved.tolerance();
        actors = saved == null ? EntityCollisionPhysics.snapshotActors(entities) : saved.actors();
        shulkers = saved == null ? world.snapshotShulkerFrames() : saved.shulkers();
        blockQueries = saved == null ? new HashMap<>() : new HashMap<>(saved.blockQueries());
        movementEntries = saved == null ? List.of() : saved.movementEntries();
        movementQuery = saved == null ? null : saved.movementQuery();
        blockPush = Vec3.ZERO.clone();
        if (saved != null) {
            enforceBoatStep = saved.enforceBoatStep();
            selectedStartY = saved.selectedStartY();
        }
    }

    public List<CollisionShapeEntry> blocks(final Box query,
                                            final Supplier<List<CollisionShapeEntry>> fetch) {
        if (!active) return fetch.get();
        final Query key = Query.of(query);
        final List<CollisionShapeEntry> saved = blockQueries.get(key);
        if (saved != null) return saved;
        final List<CollisionShapeEntry> shapes = List.copyOf(fetch.get());
        blockQueries.put(key, shapes);
        return shapes;
    }


    public List<CollisionShapeEntry> movementBlocks(final Box query,
                                                   final Supplier<List<CollisionShapeEntry>> fetch) {
        if (active && contains(movementQuery, query)) return movementEntries;
        return blocks(query, fetch);
    }

    public void commitMovement(final Box query, final List<CollisionShapeEntry> entries) {

        if (contains(movementQuery, query)) return;
        movementQuery = query;
        movementEntries = List.copyOf(entries);
    }

    public Snapshot snapshot() {
        return new Snapshot(inputTick, actors, shulkers, Map.copyOf(blockQueries),
                movementEntries, movementQuery, tolerance, enforceBoatStep, selectedStartY, blockPush.clone(),
                oneWayBlocks, pushTowardsClosestSpace, inputStartResolveAcceptedOverlaps, resolveAcceptedOverlaps,
                closestSpaceRequested, inputStartClosestSpaceRequested);
    }

    public void restore(final Snapshot snapshot) {
        reset();
        if (snapshot == null) return;
        inputTick = snapshot.inputTick();
        actors = snapshot.actors();
        shulkers = snapshot.shulkers();
        blockQueries = new HashMap<>(snapshot.blockQueries());
        movementEntries = snapshot.movementEntries();
        movementQuery = snapshot.movementQuery();
        tolerance = snapshot.tolerance();
        enforceBoatStep = snapshot.enforceBoatStep();
        selectedStartY = snapshot.selectedStartY();
        blockPush = snapshot.blockPush().clone();
        oneWayBlocks = snapshot.oneWayBlocks();
        pushTowardsClosestSpace = snapshot.pushTowardsClosestSpace();
        inputStartResolveAcceptedOverlaps = snapshot.inputStartResolveAcceptedOverlaps();
        resolveAcceptedOverlaps = snapshot.resolveAcceptedOverlaps();
        closestSpaceRequested = snapshot.closestSpaceRequested();
        inputStartClosestSpaceRequested = snapshot.inputStartClosestSpaceRequested();
    }

    public void reset() {
        inputTick = Long.MIN_VALUE;
        active = replaying = enforceBoatStep = collidedPreviousTick = false;
        selectedStartY = 0;
        tolerance = null;
        actors = List.of();
        shulkers = List.of();
        blockQueries = new HashMap<>();
        movementEntries = List.of();
        movementQuery = null;
        blockPush = Vec3.ZERO.clone();
        oneWayBlocks = List.of();
        pushTowardsClosestSpace = false;
        closestSpaceRequested = inputStartClosestSpaceRequested = false;
        inputStartResolveAcceptedOverlaps = resolveAcceptedOverlaps = false;
    }






    public void acceptClientPosition() { resolveAcceptedOverlaps = true; }

    void beginOverlapResolutionInput(final Snapshot saved) {


        inputStartResolveAcceptedOverlaps = saved == null
                ? resolveAcceptedOverlaps : saved.inputStartResolveAcceptedOverlaps();
        resolveAcceptedOverlaps = inputStartResolveAcceptedOverlaps;
    }

    public boolean resolveAcceptedOverlaps() { return resolveAcceptedOverlaps; }


    public void finishOverlapResolution() { resolveAcceptedOverlaps = false; }

    public void addInsideOneWay(final Box box) {
        if (box == null || !box.isValid()
                || oneWayBlocks.stream().anyMatch(saved -> Query.of(saved).equals(Query.of(box)))) return;
        final var boxes = new java.util.ArrayList<>(oneWayBlocks);
        boxes.add(box);
        oneWayBlocks = List.copyOf(boxes);
    }


    public boolean hasOneWayCollision(final Box actor, final boolean commit) {
        final List<Box> retained = oneWayBlocks.stream().filter(box -> box.intersects(actor)).toList();
        if (commit) oneWayBlocks = retained;
        return pushTowardsClosestSpace || !retained.isEmpty();
    }

    public void updatePushTowardsClosestSpace(final boolean value) { pushTowardsClosestSpace = value; }


    public void requestClosestSpace(final boolean enabled, final boolean penetrating) {
        closestSpaceRequested |= enabled && penetrating;
    }

    public boolean consumeClosestSpaceRequest() {
        final boolean requested = closestSpaceRequested;
        closestSpaceRequested = false;
        return requested;
    }

    void beginClosestSpaceInput(final Snapshot saved) {
        inputStartClosestSpaceRequested = saved == null ? closestSpaceRequested : saved.inputStartClosestSpaceRequested();
        closestSpaceRequested = inputStartClosestSpaceRequested;
    }

    public void setTolerance(final EntityCollisionPhysics.ToleranceSample sample) {
        tolerance = sample.tolerance();
        enforceBoatStep = sample.enforceBoatStep();
    }
    public boolean active() { return active; }
    public void endInput() { active = false; replaying = false; }
    public boolean replaying() { return replaying; }
    public boolean enforceBoatStep() { return enforceBoatStep; }
    public boolean collidedPreviousTick() { return collidedPreviousTick; }
    public long inputTick() { return inputTick; }
    public EntityCollisionPhysics.Tolerance tolerance() { return tolerance; }
    public float selectedStartY() { return selectedStartY; }
    public void selectedStartY(final float value) {

        if (!replaying || tolerance == null) selectedStartY = value;
    }
    public List<EntityCollisionPhysics.Actor> actors() { return actors; }
    public List<ShulkerCollisionState.Frame> shulkers() { return shulkers; }
    public void addBlockPush(final Vec3 applied) { blockPush = blockPush.add(applied); }
    public boolean pushedByBlock() { return active && blockPush.lengthSquared() > 1.0E-12F; }

    public static boolean contains(final Box outer, final Box inner) {
        return outer != null && outer.isValid() && outer.minX <= inner.minX
                && outer.minY <= inner.minY && outer.minZ <= inner.minZ
                && outer.maxX >= inner.maxX && outer.maxY >= inner.maxY && outer.maxZ >= inner.maxZ;
    }
}
