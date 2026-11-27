package ac.navro.proxy.prediction.vehicle;

import ac.navro.anticheat.collision.CollisionQuery;
import ac.navro.anticheat.collision.ProtocolCollisionSolver;
import ac.navro.anticheat.util.MathUtil;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.collision.EntityCollisionPhysics;
import ac.navro.proxy.compensated.cache.entity.EntityCache;
import ac.navro.proxy.data.FluidState;
import ac.navro.proxy.data.block.NavroBlockState;
import ac.navro.proxy.data.input.PredictionData;
import ac.navro.proxy.packets.input.AuthInputPackets;
import ac.navro.proxy.prediction.ProtocolGroundAirPhysics;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;

import java.util.ArrayList;
import java.util.HashMap;


public final class VehiclePrediction {
    private final SessionState session;
    private EntityCache actor;
    private Vec3 position, velocity;
    private float yaw;
    private boolean grounded;
    private int stillTicks;
    private long positionRevision, motionRevision, rotationRevision, physicsRevision;
    private int pendingJump = -1;
    private boolean horseJumping;
    private HorseControl horseControl = new HorseControl();
    private Vec3 slowdown;
    private final VehicleEnvironment environment;
    private VehiclePhysics.Boat boat = new VehiclePhysics.Boat();
    private String status = "unmounted";
    private final java.util.NavigableMap<Long, Frame> history = new java.util.TreeMap<>();
    private record Drive(Vec3 control, float riderYaw, boolean valid, boolean analog, boolean left, boolean right,
                         boolean jumpDown, int charge, long simulationFrame) { }
    private record Carry(Vec3 position, Vec3 velocity, float yaw, boolean grounded, VehiclePhysics.Boat boat, int pendingJump, boolean horseJumping, HorseControl horseControl, Vec3 slowdown) { }
    private record Frame(Drive input, Carry after, float yOffset) { }
    private record Step(Vec3 submitted, Vec3 moved, boolean horizontal, boolean vertical) { }
    private Carry capture() { return new Carry(position.clone(), velocity.clone(), yaw, grounded, boat.copy(), pendingJump, horseJumping, horseControl.copy(), slowdown == null ? null : slowdown.clone()); }
    private void restore(Carry saved) {
        position = saved.position.clone(); velocity = saved.velocity.clone(); yaw = saved.yaw;
        grounded = saved.grounded; boat = saved.boat.copy(); pendingJump = saved.pendingJump; horseJumping = saved.horseJumping; horseControl = saved.horseControl.copy(); slowdown = saved.slowdown == null ? null : saved.slowdown.clone();
    }
    public VehiclePrediction(SessionState session) { this.session = session; this.environment = new VehicleEnvironment(session); }
    public String status() { return status; }
    public float yaw() { return yaw; }
    public Vec3 currentPosition() { return position == null ? null : position.clone(); }
    public int stillTicks() { return stillTicks; }

    public void mounted(EntityCache entity) {
        bind(entity);
        velocity=Vec3.ZERO.clone();grounded=false;boat=new VehiclePhysics.Boat();
        entity.publishVehiclePose(position,position,velocity,yaw,0,false);
        session.player().setVelocity(Vec3.ZERO.clone());
        session.synchronizeVehicleMount(position,velocity,yaw,false,entity.boat()?0F:null);
    }
    public void reset() { stillTicks = 0; actor = null; position = velocity = null; boat = new VehiclePhysics.Boat(); status = "reset"; pendingJump = -1; horseJumping = false; horseControl = new HorseControl(); slowdown = null; history.clear(); }

    public AuthInputPackets.Result run(PlayerAuthInputPacket packet) {
        var wire = session.player();
        var mount = wire.vehicleData;
        var entity = mount == null ? null : session.entity(mount.vehicleRuntimeId);
        var parameters = entity != null && entity.boat() ? BoatParameters.read(entity) : null;
        boolean horse = entity != null && entity.horse()
                && Float.isFinite(entity.attribute("minecraft:movement", Float.NaN))
                && Float.isFinite(entity.attribute("minecraft:horse.jump_strength", Float.NaN));
        boolean eligible = entity != null && entity.getCurrent() != null && mount.driver
                && session.player().protocolVersion() >= 944 && entity.dimension() == session.world().dimension()
                && entity.attribute("minecraft:health", 1) > 0
                && (entity.boat() && parameters != null || horse && entity.flag(EntityFlag.SADDLED))
                && (!entity.metadata().containsKey(EntityDataTypes.CONTROLLING_RIDER_SEAT_INDEX)
                    || entity.metadata().get(EntityDataTypes.CONTROLLING_RIDER_SEAT_INDEX, Byte.class) == 0);
        mount.canWeControlThisVehicle = eligible;
        if (!eligible || wire.isMovementExempted()) {
            reset(); status = !eligible ? "unsupported_or_passenger" : "exempt";
            publishDebug(packet, entity, wire.position(), null, null);
            wire.setResolvedPosition(wire.unvalidatedPosition().clone(), wire.boundingBox(), false, true);
            wire.completePredictionInput();
            return null;
        }
        wire.collisionPhysics.begin(session.world(), session.entities(), wire.tick(), null);
        if (actor != entity) bind(entity);
        if (positionRevision != entity.forcedPositionRevision()) {
            position = entity.acknowledgedPosition(); positionRevision = entity.forcedPositionRevision();
            history.clear();
            stillTicks = 0;
            boat = new VehiclePhysics.Boat();
        }
        if (motionRevision != entity.motionRevision()) { history.clear(); velocity = motion(entity); motionRevision = entity.motionRevision(); }
        if (rotationRevision != entity.rotationRevision()) { history.clear(); yaw = entity.yaw(); rotationRevision = entity.rotationRevision(); boat.angularVelocity = 0; }
        if (physicsRevision != entity.physicsRevision()) { history.clear(); physicsRevision = entity.physicsRevision(); }
        Box bounds = box(entity, position);
        if (!terrainKnown(bounds.expand(1.1F))) {
            status = "waiting_for_terrain";
            publishDebug(packet, entity, position, null, null);
            resolve(Vec3.ZERO, false, false);
            wire.completePredictionInput();
            return new AuthInputPackets.Result(wire.tick(), position.distanceTo(wire.unvalidatedPosition()), 0,
                    position.clone(), velocity.clone(), false);
        }
        boolean valid = packet.getInputData().contains(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE)
                && packet.getPredictedVehicle() == entity.runtimeId()
                && packet.getVehicleRotation() != null && MathUtil.isValid(packet.getVehicleRotation())
                && packet.getPosition() != null && MathUtil.isValid(packet.getPosition())
                && packet.getRotation() != null && MathUtil.isValid(packet.getRotation())
                && VehicleControlInput.valid(packet)
                && (packet.getAnalogMoveVector() == null || MathUtil.isValid(packet.getAnalogMoveVector()));

        var drive = new Drive(valid ? VehicleControlInput.decode(packet, entity.boat()) : Vec3.ZERO, packet.getRotation() == null ? yaw : packet.getRotation().getY(),
                valid, VehicleControlInput.analogPaddles(packet),
                valid && packet.getInputData().contains(PlayerAuthInputData.PADDLE_LEFT),
                valid && packet.getInputData().contains(PlayerAuthInputData.PADDLE_RIGHT),
                valid && packet.getInputData().contains(PlayerAuthInputData.JUMPING), mount.jumpCharge, wire.simulationFrame());
        mount.jumpCharge = -1;
        Carry before = capture();
        var step = advance(drive, entity, parameters);
        if (step == null) {
            restore(before); status = "waiting_for_terrain";
            publishDebug(packet, entity, before.position, null, null);
            resolve(Vec3.ZERO, false, false); wire.completePredictionInput();
            return new AuthInputPackets.Result(wire.tick(), position.distanceTo(wire.unvalidatedPosition()), 0,
                    position.clone(), velocity.clone(), false);
        }
        Vec3 travel = position.subtract(before.position);

        stillTicks = travel.horizontalLengthSquared() <= 9.801E-5F && travel.y == 0 ? stillTicks + 1 : 0;
        history.put(wire.simulationFrame(), new Frame(drive, capture(), session.playerYOffset()));
        while (history.size() > session.rewindHistorySize()) history.pollFirstEntry();
        float raw = position.distanceTo(wire.unvalidatedPosition());
        float rotationOffset = valid ? Math.abs(MathUtil.wrapDegrees(packet.getVehicleRotation().getY() - yaw)) : Float.POSITIVE_INFINITY;

        float tolerance = session.authInputPackets().context().maxOffset;
        boolean accepted = valid && raw <= tolerance;
        status = accepted ? "predicted" : valid ? "offset" : "invalid_vehicle_input";
        resolve(step.moved, step.horizontal, step.vertical);
        var context = session.authInputPackets().context();
        context.begin(); context.beforeCollision = step.submitted; context.afterCollision = step.moved;
        context.lastTickFinalVelocity = velocity.clone();
        context.predictionResult = new PredictionData(step.submitted, step.moved, velocity.clone());
        if(valid&&!accepted&&!session.teleports().hasPendingCorrection()) {
            var check=(ac.navro.proxy.check.impl.prediction.VehiclePredictionCheck)wire.getCheckHolder()
                    .get(ac.navro.proxy.check.impl.prediction.VehiclePredictionCheck.class);
            if(check!=null)check.check(raw,step.moved,wire.unvalidatedPosition().subtract(before.position));
        }
        session.cachePredictionFrame(context);
        if (!accepted) session.correctVehicle(position, velocity, yaw, grounded, actor.boat() ? boat.angularVelocity : null);
        publishDebug(packet, entity, before.position, step.moved, raw);
        var reportedRotation = packet.getVehicleRotation();
        packet.setVehicleRotation(Vector2f.from(0, yaw));
        wire.completePredictionInput();
        session.teleports().finishCorrectionCooldown();
        var trace = wire.worldDiagnostics();
        if (trace != null) trace.event("vehicle_prediction", ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                "vehicle", entity.runtimeId(), "kind", entity.identifier(), "status", status, "offset", Float.isFinite(raw) ? raw : null,
                "rotationOffset", Float.isFinite(rotationOffset) ? rotationOffset : null, "yaw", yaw, "grounded", grounded,
                "inputMode", String.valueOf(packet.getInputMode()), "interactionModel", String.valueOf(packet.getInputInteractionModel()),
                "motion", String.valueOf(packet.getMotion()), "analogMoveVector", String.valueOf(packet.getAnalogMoveVector()),
                "reportedRotation", String.valueOf(reportedRotation), "control", drive.control.toString(), "analogPaddles", drive.analog,
                "position", position.toString(), "clientPosition", wire.unvalidatedPosition().toString(),
                "velocity", velocity.toString(), "clientVelocity", wire.unvalidatedTickEnd().toString(),
                "angularVelocity", boat.angularVelocity, "leftPower", boat.left.power(), "rightPower", boat.right.power()));
        return new AuthInputPackets.Result(wire.tick(), raw, raw, position.clone(), velocity.clone(), accepted);
    }

    private void publishDebug(PlayerAuthInputPacket input, EntityCache entity, Vec3 origin,
                              Vec3 predictedMovement, Float offset) {
        if (!session.hasDebugSubscribers()) return;
        var wire = session.player();
        var rotation = input.getVehicleRotation();
        ac.navro.proxy.check.impl.prediction.DebugOffsetA.publishVehicle(session,
                new ac.navro.proxy.check.impl.prediction.DebugOffsetA.VehicleFrame(
                        entity == null ? "unknown" : entity.identifier(), wire.vehicleData.vehicleRuntimeId, status,
                        predictedMovement, wire.unvalidatedPosition().subtract(origin),
                        predictedMovement == null ? null : velocity.clone(), wire.unvalidatedTickEnd().clone(),
                        offset, predictedMovement == null ? null : yaw, rotation == null ? null : rotation.getY()));
    }

    private Step advance(Drive drive, EntityCache entity, BoatParameters parameters) {
        Box bounds = box(entity, position);
        boolean wasGrounded = grounded;
        Vec3 starting = velocity.clone();
        Vec3 input = drive.control;
        var medium = environment.medium(bounds);
        Vec3 flow = entity.boat() ? environment.flow(bounds, parameters) : environment.flow(bounds);
        Vec3 previousPosition = position.clone();
        boolean slowed = slowdown != null;
        boolean prevented = environment.preventsJump(position, grounded);
        boolean climbing = environment.climbing(position), scaffolding = environment.scaffolding(bounds);
        float horseFriction = frictionForHorse(bounds, medium, grounded);
        Vec3 submitted;
        float friction = environment.friction(environment.block(position.down(.1F)));
        float step = entity.boat() ? Math.max(0, entity.attribute("minecraft:step_height", 0)) : 0;
        if (entity.boat()) {
            var surface = boatSurface(position, parameters);
            if (surface.submerged) boat.submergedSteps++;
            else if (surface.floating && !entity.flag(EntityFlag.OUT_OF_CONTROL)) boat.submergedSteps = 0;
            boolean analog = drive.analog;
            submitted = boat.accelerate(starting.add(flow), yaw, input.x, input.z, analog,
                    drive.left,
                    drive.right,
                    surface.friction, surface.airborne);
            if (boat.submergedSteps <= 24) yaw += boat.angularVelocity;
        } else {
            yaw = VehiclePhysics.horseYaw(yaw, drive.valid ? drive.riderYaw : yaw);
            horseControl.metadata(entity.standingRevision(), entity.flag(EntityFlag.STANDING));
            boolean suppressed = horseControl.suppressed(grounded, drive.charge);
            horseControl.request(drive.valid ? drive.charge : -1);
            starting = starting.add(flow);

            if (drive.valid && drive.jumpDown) {
                if (scaffolding) starting = new Vec3(starting.x, .15F, starting.z);
                else if (climbing) starting = new Vec3(starting.x, .2F, starting.z);
                else if (medium != FluidState.Type.EMPTY) starting = starting.add(0, .04F, 0);
            }
            if (horseControl.canJump(grounded)) {

                float scale = horseControl.jump;
                int charge = drive.charge;
                submitted = horseJumpScaled(starting, yaw, entity.attribute("minecraft:horse.jump_strength", .7F),
                        scale, input.z > 0, prevented ? .6F : 1, entity.effectLevel(8, drive.simulationFrame));
                horseControl.launched();
            } else submitted = starting;
            float speed = entity.attribute("minecraft:movement", .225F);
            float accel = switch (medium) {
                case WATER -> entity.attribute("minecraft:underwater_movement", .02F);
                case LAVA -> entity.attribute("minecraft:lava_movement", .02F);
                case EMPTY -> grounded ? horseGroundAcceleration(speed, friction, bounds, climbing) : speed * .1F;
            };
            submitted = submitted.add(VehiclePhysics.horseInput(suppressed ? Vec3.ZERO : input, accel, yaw));
            step = prevented ? .5625F : 1.0625F;
            submitted = environment.honeySlide(submitted, bounds);
        }
        if (slowed) submitted = submitted.multiply(slowdown.x, slowdown.y, slowdown.z);

        Box query = CollisionQuery.buildCollisionQuery(bounds, submitted, step);
        if (!terrainKnown(query)) return null;
        var shapes = colliders(query, bounds);
        var moved = ProtocolCollisionSolver.solveResolved(submitted, bounds, shapes, new Vec3(.1F, .1F, .1F), step, grounded);
        boolean vertical = submitted.y != moved.movement.y;
        boolean horizontal = submitted.x != moved.movement.x || submitted.z != moved.movement.z;


        grounded = vertical && submitted.y < 0
                || wasGrounded && !vertical && Math.abs(submitted.y) <= 1.0E-9;
        if (!entity.boat()) horseControl.finish(wasGrounded, grounded);
        position = moved.aabb.feetCenter();
        Vec3 clipped = new Vec3(submitted.x == moved.movement.x ? submitted.x : 0,
                vertical ? 0 : submitted.y, submitted.z == moved.movement.z ? submitted.z : 0);
        if (entity.boat()) {
            var surface = boatSurface(position, parameters);
            float y = VehiclePhysics.boatVertical(clipped.y, parameters.buoyant(), parameters.gravity(),
                    surface.floating, surface.submerged, position.y + entity.getYOffset(), parameters.base());
            velocity = environment.honey(new Vec3(clipped.x, bubbleColumnY(y, box(entity, position)), clipped.z), box(entity, position));
        } else {
            if (slowed) clipped = Vec3.ZERO.clone();
            var support = environment.support(box(entity, position));
            boolean bounced = false;
            if (vertical && grounded && submitted.y < 0 && support.getBlockBounciness() > 0) {
                float falling = submitted.y;
                if (Math.abs(falling) >= .08F) {
                    float rebound = -falling * support.getBlockBounciness();
                    float distance = Math.abs(position.y - previousPosition.y);
                    float root = (float) Math.sqrt(falling*falling + (.08F+.08F)*distance);
                    float correction = -.08F * (1 - Math.abs((Math.abs(falling)-root)/-.08F));
                    clipped = new Vec3(clipped.x, (rebound+correction)*.98F, clipped.z);
                    bounced = true;
                }
            }
            boolean autoClimb = medium == FluidState.Type.EMPTY && !scaffolding && horizontal && environment.climbing(position);
            float y = clipped.y;
            if (autoClimb) y = .2F;
            if (!bounced && !autoClimb) {
                if (medium == FluidState.Type.WATER) y = y*.8F-.02F;
                else if (medium == FluidState.Type.LAVA) y = y*.5F-.02F;
                else {
                    int levitation = entity.effectLevel(24, drive.simulationFrame);
                    y = levitation > 0 ? starting.y+(.05F*levitation-starting.y)*.2F
                            : y-(starting.y<0 && entity.effectLevel(27,drive.simulationFrame)>0?.01F:.08F);
                    y *= .98F;
                }
            }
            velocity = new Vec3(clipped.x, y, clipped.z);
            if (grounded && (support.isSlimeBlock() || support.isHoneyBlock()) && Math.abs(y)<.1F) {
                float factor=Math.abs(y)*.2F+.4F;
                velocity=new Vec3(velocity.x*factor,velocity.y,velocity.z*factor);
            }
            velocity=new Vec3((float)(velocity.x*horseFriction),velocity.y,(float)(velocity.z*horseFriction));
            if (medium!=FluidState.Type.EMPTY && horizontal) {
                Box clearance=box(entity,position).offset(velocity.x,
                        velocity.y+.6F-position.y+previousPosition.y,velocity.z);
                if(environment.clear(clearance))velocity=new Vec3(velocity.x,.3F,velocity.z);
            }
            velocity=environment.honey(new Vec3(velocity.x,bubbleColumnY(velocity.y,box(entity,position)),velocity.z),box(entity,position));
            if(slowed)velocity=new Vec3(0,velocity.y,0);
        }
        slowdown = entity.boat() ? null : environment.slowdown(box(entity,position),entity.effectLevel(31,drive.simulationFrame)>0);

        return new Step(submitted, moved.movement, horizontal, vertical);
    }

    private void bind(EntityCache entity) {
        reset(); actor = entity;
        position = entity.getCurrent().getPos().clone(); velocity = motion(entity); yaw = entity.yaw();
        positionRevision = entity.forcedPositionRevision(); physicsRevision = entity.physicsRevision(); motionRevision = entity.motionRevision(); rotationRevision = entity.rotationRevision();
        Box bounds = box(entity, position);
        grounded = colliders(bounds.expand(.01F), bounds).stream().anyMatch(b -> bounds.offset(0, -.001F, 0).intersects(b));
        var seed = entity.vehiclePose();
        if (seed != null) {
            velocity = seed.velocity().clone(); grounded = seed.ground();
            boat.angularVelocity = seed.angularVelocity();
        }
        var log = session.player().worldDiagnostics();
        if (log != null) log.event("vehicle_prediction_start", ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                "vehicle", entity.runtimeId(), "position", position.toString(), "yaw", yaw,
                "velocity", velocity.toString(), "retainedActor", seed != null));
    }
    private static Vec3 motion(EntityCache entity) {
        var motion = entity.motion(); return motion == null ? Vec3.ZERO.clone() : new Vec3(motion.x(), motion.y(), motion.z());
    }
    private void resolve(Vec3 moved, boolean horizontal, boolean vertical) {
        if (actor != null && session.entity(actor.runtimeId()) == actor)
            actor.publishVehiclePose(position.subtract(moved), position, velocity, yaw, boat.angularVelocity, grounded);
        var wire = session.player(); wire.setResolvedPosition(position.clone(), wire.boundingBox(), false, true);
        wire.setVelocity(velocity); wire.setCollisionResult(horizontal, vertical, grounded); wire.setAfterCollision(moved);
    }
    private static Box box(EntityCache entity, Vec3 at) {
        float width = entity.getDimensions().width(), height = entity.getDimensions().height();
        if (entity.boat()) {
            var metadata = entity.metadata();
            var specifiedWidth = ac.navro.proxy.util.EntityMetadataUtil.getFloat(metadata, EntityDataTypes.WIDTH);
            var specifiedHeight = ac.navro.proxy.util.EntityMetadataUtil.getFloat(metadata, EntityDataTypes.HEIGHT);
            var scale = ac.navro.proxy.util.EntityMetadataUtil.getFloat(metadata, EntityDataTypes.SCALE);
            width = (specifiedWidth == null ? 1.375F : specifiedWidth) * (scale == null ? 1 : scale);
            height = (specifiedHeight == null ? .5625F : specifiedHeight) * (scale == null ? 1 : scale);
        }
        float radius = width * .5F;
        return new Box(at.x - radius, at.y, at.z - radius, at.x + radius, at.y + height, at.z + radius);
    }
    private boolean terrainKnown(Box query) {
        int minX = (int) Math.floor(query.minX), maxX = (int) Math.floor(Math.nextDown(query.maxX));
        int minZ = (int) Math.floor(query.minZ), maxZ = (int) Math.floor(Math.nextDown(query.maxZ));
        var world = session.world();
        if (!world.hasChunksAt(minX, minZ, maxX, maxZ, world.dimension())) return false;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++)
            if (!world.areSectionsKnown(cx << 4, cz << 4, (int) Math.floor(query.minY), (int) Math.floor(query.maxY))) return false;
        return true;
    }
    private java.util.List<Box> colliders(Box query, Box bounds) {
        var result = new ArrayList<Box>();
        for (var entry : session.collisionWorld().movementEntries(query)) result.add(entry.shape());
        var other = new HashMap<Long, EntityCache>();
        session.entities().forEach((id, value) -> { if (value != actor && !value.inVehicle()) other.put(id, value); });
        result.addAll(EntityCollisionPhysics.nearbyShapes(EntityCollisionPhysics.snapshotActors(other), bounds,
                session.player().runtimeId(), session.world().dimension(), actor != null && actor.stackable()));
        return result;
    }
    private NavroBlockState block(float x, float y, float z) {
        var world = session.world();
        return new NavroBlockState(world.networkBlockState(world.getRawBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), 0)));
    }
    private FluidState fluid(Vec3 at) {
        return session.world().fluidAt((int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z), session.world().dimension());
    }
    private float bubbleColumnY(float velocityY, Box bounds) {
        float result = velocityY;
        for (int y = (int) Math.floor(bounds.minY + .001F); y <= Math.floor(bounds.maxY - .001F); y++) {
            boolean up = false, down = false;
            for (int x = (int) Math.floor(bounds.minX); x <= Math.floor(Math.nextDown(bounds.maxX)); x++)
                for (int z = (int) Math.floor(bounds.minZ); z <= Math.floor(Math.nextDown(bounds.maxZ)); z++) {
                    var block = block(x,y,z).getNetworkState();
                    if (!block.is("minecraft:bubble_column")) continue;
                    if (block.booleanProperty("drag_down", false)) down = true; else up = true;
                }
            if (up) result = VehiclePhysics.bubbleColumnY(result,false);
            if (down) result = VehiclePhysics.bubbleColumnY(result,true);
        }
        return result;
    }
    private record BoatSurface(float friction, boolean airborne, boolean floating, boolean submerged) { }
    private BoatSurface boatSurface(Vec3 feet, BoatParameters parameters) {
        var origin=feet.up(actor.getYOffset());
        int x=(int)Math.floor(origin.x), y=(int)Math.floor(origin.y), z=(int)Math.floor(origin.z);
        var liquid=environment.liquid(x,y,z);var above=environment.liquid(x,y+1,z);
        boolean valid=liquid!=null&&parameters.accepts(liquid.identifier());
        boolean submerged=valid&&above!=null&&parameters.accepts(above.identifier());
        boolean floating=valid&&!submerged&&origin.y<VehicleEnvironment.surface(liquid,y);
        if(floating||submerged||VehicleEnvironment.kind(liquid)==FluidState.Type.WATER)
            return new BoatSurface(.9F,false,floating,submerged);
        var current=environment.block(x,y,z,0);
        boolean belowFriction=liquid==null||liquid.is("minecraft:air")||liquid.is("minecraft:snow_layer");
        if(grounded) {
            var cell=new Box(x,y,z,x+1,y+1,z+1);
            boolean collision=session.collisionWorld().blockEntries(cell).stream().anyMatch(e->e.shape().intersects(cell));
            return new BoatSurface(environment.friction(belowFriction||!collision?environment.block(x,y-1,z,0):current),false,false,false);
        }
        if(belowFriction&&VehicleEnvironment.kind(environment.liquid(x,y-1,z))==FluidState.Type.WATER)
            return new BoatSurface(.9F,false,false,false);
        return new BoatSurface(actor.flag(EntityFlag.LEASHED)?.91F:1,true,false,false);
    }

    private float frictionForHorse(Box box, FluidState.Type medium, boolean ground) {
        if (medium == FluidState.Type.WATER) return .8F;
        if (medium == FluidState.Type.LAVA) return .5F;
        var feet=box.feetCenter();
        var under=environment.block(feet.down(.1F));
        var at=environment.block(feet);
        var surface=(at==null||at.is("minecraft:air")||at.is("minecraft:snow_layer"))?under:at;
        return (float)((ground ? environment.friction(surface) : 1)*.91D)
                *actor.attribute("minecraft:friction_modifier",1);
    }
    private float horseGroundAcceleration(float speed,float friction,Box bounds,boolean climbing) {
        var floor=environment.block(bounds.feetCenter().down(.1F));
        boolean powder=environment.block(bounds.feetCenter()).is("minecraft:powder_snow");
        float control=(float)(.21600002D/(friction*(double)friction*friction));
        if(powder||climbing)control=(float)(.21600002D/(.6D*.6D*.6D));
        if(floor.is("minecraft:soul_sand")) {
            float normal=friction*.91F;
            control=control*(float)Math.pow(normal/(normal*1.225F),3);
        }
        return speed*control;
    }
    private static Vec3 horseJumpScaled(Vec3 v,float yaw,float strength,float scale,boolean forward,float factor,int boost) {

        return VehiclePhysics.horseJumpWithScale(v,yaw,strength,scale,forward,factor,boost);
    }

    public void correctCurrent() {
        if (actor != null && position != null) session.correctVehicle(position, velocity, yaw, grounded, actor.boat() ? boat.angularVelocity : null);
    }

    public void applyAcknowledgedHardReset(EntityCache expected,Vec3 target,Vec3 targetVelocity,float targetYaw,boolean ground) {
        if(actor!=expected)return;
        position=target.clone();velocity=targetVelocity.clone();yaw=targetYaw;grounded=ground;
        history.clear();stillTicks=0;


        resolve(Vec3.ZERO,false,false);
    }
    public boolean applyHardCorrection(ac.navro.proxy.teleport.data.CorrectionResetData reset) {
        var target=reset.vehicle();var mount=session.player().vehicleData;
        if(target==null||mount==null||mount.vehicleRuntimeId!=target.actor().runtimeId()
                ||session.entity(mount.vehicleRuntimeId)!=target.actor())return false;
        if(actor!=target.actor())bind(target.actor());
        position=reset.nativeTargetFoot();velocity=target.velocity().clone();yaw=target.yaw();grounded=target.grounded();
        if(target.angularVelocity()!=null)boat.angularVelocity=target.angularVelocity();
        history.clear();stillTicks=0;
        session.player().setResolvedPosition(position.clone(),session.player().boundingBox(),false,true);
        resolve(Vec3.ZERO,false,false);
        session.authInputPackets().context().begin();
        status="hard_correction";return true;
    }


    public void correctFromServer(CorrectPlayerMovePredictionPacket correction) {
        var mount = session.player().vehicleData;
        if (mount == null) return;
        var entity = session.entity(mount.vehicleRuntimeId);
        if (entity == null || entity.getCurrent() == null) return;
        if (actor != entity) bind(entity);
        if (physicsRevision != entity.physicsRevision() || motionRevision != entity.motionRevision()
                || positionRevision != entity.forcedPositionRevision()) {
            history.clear(); status = "backend_correction_superseded"; return;
        }
        var source = history.get(correction.getTick());
        if (!history.isEmpty() && source == null && correction.getTick() < history.lastKey()) {
            status = "backend_correction_missing_history"; correctCurrent(); return;
        }
        Carry live = capture();
        if (source != null) restore(source.after);
        position = new Vec3(correction.getPosition()).down(source == null ? session.playerYOffset() : source.yOffset);
        velocity = new Vec3(correction.getDelta()); grounded = correction.isOnGround();
        if (correction.getVehicleRotation() != null) yaw = correction.getVehicleRotation().getY();
        if (actor.boat() && correction.getVehicleAngularVelocity()!=null && Float.isFinite(correction.getVehicleAngularVelocity()))
            boat.angularVelocity=correction.getVehicleAngularVelocity();
        var replay = new java.util.TreeMap<Long, Frame>();
        if (source != null) replay.put(correction.getTick(), new Frame(source.input, capture(), source.yOffset));
        var parameters = actor.boat() ? BoatParameters.read(actor) : null;
        if (actor.boat() && parameters == null) { restore(live); return; }
        for (var entry : history.tailMap(correction.getTick(), false).entrySet()) {
            session.player().collisionPhysics.begin(session.world(), session.entities(), entry.getKey(), null);
            if (advance(entry.getValue().input, actor, parameters) == null) {
                restore(live); status = "backend_correction_missing_terrain"; correctCurrent(); return;
            }
            replay.put(entry.getKey(), new Frame(entry.getValue().input, capture(), entry.getValue().yOffset));
        }
        history.putAll(replay);
        resolve(Vec3.ZERO, false, false);
        session.resetMovementHistory();
        status = "backend_correction";
    }
}
