package ac.navro.proxy.check.impl.place;

import ac.navro.proxy.util.block.PlacementContext;

import ac.navro.anticheat.util.Pair;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.anticheat.util.math.Direction;
import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.check.api.impl.PacketCheck;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.packets.input.PacketEvent;
import ac.navro.proxy.util.reach.CameraCandidates;
import ac.navro.proxy.util.reach.PlacementCameraSnapshot;
import ac.navro.proxy.util.block.PlacementContext.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.InputInteractionModel;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


@CheckInfo(name = "RotationPlace", requiresServerTick = true)
public class RotationPlace extends PacketCheck {
    private static final double EPSILON = 1.0E-5;
    private static final int MAX_PENDING = 64, MAX_CELLS = 8192;
    private static final int MAX_FALLBACK_CELLS = 32768;
    private static final int CLIENT_PREDICTION_FAILURE = 0;
    private static final Point DOWN = new Point(0, -1, 0);
    private static final double SCREEN_SELECTION_RANGE = 12.0F;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private Frame previous, latest;
    private long epoch = Long.MIN_VALUE, generation;
    private Object level;
    private PacketEvent stagedEvent;
    private Staged staged;
    private RotationPlaceBridge bridge = new RotationPlaceBridge();
    private int bridgeSlot = -1, bridgeItemId, bridgeItemDamage;

    record Point(double x, double y, double z) {
        double at(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
        Point minus(Point other) { return new Point(x - other.x, y - other.y, z - other.z); }
        boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
    }
    private record Frame(long tick, Point position, InputMode inputMode, InputInteractionModel interactionModel,
                         RotationPlaceInterpolation.Rotation camera, Point motion, boolean sneaking,
                         PlacementCameraSnapshot selectionCamera, double cameraYOffset) { }
    private record Staged(long generation, Frame frame, Point direction, Placement placement) { }
    private record Pending(Placement placement, InventoryTransactionPacket transaction, Object level) { }
    private record Placement(long tick, Point from, Point to, Cell target,
                             Direction face, Box outline, List<Box> blockers, double selectionRange,
                             String source, String targetState, List<String> blockerStates,
                             int triggerType, Point clickPos, Point reportedPosition,
                             Cell placed, int hotbarSlot, InputMode inputMode,
                             InputInteractionModel interactionModel, RotationPlaceInterpolation.Rotation camera,
                             double uiSelectionRange, double alternateUiRange, RotationPlaceBridge bridge,
                             Point motion, boolean sneaking, PredictionEvidence prediction, CameraCandidates.Result cameraCandidates,
                             double cameraYOffsetA, double cameraYOffsetB) { }
    private static final class PredictionEvidence {
        final UseItemData transaction;
        final boolean failed;
        boolean placed;
        PredictionEvidence(UseItemData transaction) {
            this.transaction = transaction;
            this.failed = transaction.clientInteractPrediction == CLIENT_PREDICTION_FAILURE;
        }
    }
    private record Interval(double min, double max) { }
    private record Vertex(double alpha, double distance) { }
    private record Obstruction(int index, Interval covered, int remainingIntervals) { }


    static final class Trace {
        String reason, fallbackReason;
        String rotationResult;
        String pickPath, uiFallbackReason, screenFallbackReason;
        String bridgeReason;
        int rotationNodes;
        Interval allowed;
        Point candidateOrigin, candidateHit;
        double candidateDistance, distanceAtStart, distanceAtEnd;
        int obstructionCount;
        final List<Obstruction> obstructions = new ArrayList<>();
        int ordinaryCutCount;
        final List<Obstruction> ordinaryCuts = new ArrayList<>();

        private void cut(int index, Interval covered, int remaining) {
            obstructionCount++;
            Obstruction obstruction = new Obstruction(index, covered, remaining);

            if (obstructions.size() < 16) obstructions.add(obstruction);
            else obstructions.set(15, obstruction);
        }
    }

    private final PlacementContext context;
    public RotationPlace(WirePlayerState player,PlacementContext context) { super(player);this.context=java.util.Objects.requireNonNull(context); }


    @Override
    protected void appendViolationDetails(StringBuilder builder, int vl, String verbose) {
        builder.append(" §7x§a").append(vl);
    }

    public void onPacketReceive(PacketEvent event) {


        if (event.isCancelled() || stagedEvent == event) return;
        if (epoch != context.epoch() || level != context.levelIdentity()) {
            reset();
            epoch = context.epoch();
            level = context.levelIdentity();
        }
        if (!available()) { reset(); return; }


        if (event.getPacket() instanceof PlayerActionPacket action
                && action.getAction() == org.cloudburstmc.protocol.bedrock.data.PlayerActionType.STOP_ITEM_USE_ON
                || event.getPacket() instanceof MobEquipmentPacket equipment
                && equipment.getRuntimeEntityId() == context.runtimeId() && equipment.getHotbarSlot() != bridgeSlot) {
            bridge = new RotationPlaceBridge();
            bridgeSlot = -1;
        }
        if (event.getPacket() instanceof PlayerAuthInputPacket input) {
            final var position = input.getPosition();
            final var rotation = input.getInteractRotation();
            Point raw = position == null ? null : new Point(position.getX(), position.getY(), position.getZ());
            Point direction = rotation == null ? null : direction(rotation.getX(), rotation.getY());
            Frame frame = raw != null && raw.finite()
                    ? new Frame(input.getTick(), raw, input.getInputMode(), input.getInputInteractionModel(),
                    new RotationPlaceInterpolation.Rotation((input.getRotation()==null?0:input.getRotation().getX()),(input.getRotation()==null?0:input.getRotation().getY())), null,
                    input.getInputData().contains(PlayerAuthInputData.SNEAKING),
                    new PlacementCameraSnapshot(input.getTick(), latest == null ? -1 : latest.tick,
                            input.getInputMode(), input.getInputInteractionModel(), input.getPlayMode(),
                            input.getCameraOrientation() == null ? null : new Vec3(input.getCameraOrientation()), null), 0) : null;
            stagedEvent = event;
            staged = new Staged(generation, frame, direction, capture(context.embeddedTransaction(input), "embedded", frame));
        } else if (event.getPacket() instanceof InventoryTransactionPacket transaction) {
            stagedEvent = event;
            staged = new Staged(generation, null, null, capture(transaction, "standalone", latest));
        }
    }


    public void finishPacket(PacketEvent event) {
        if (stagedEvent != event) return;
        Staged observation = staged;
        stagedEvent = null;
        staged = null;
        if (observation == null) return;
        if (event.isCancelled() || observation.generation != generation || !available()
                || epoch != context.epoch()
                || level != context.levelIdentity()) { reset(); return; }
        if (event.getPacket() instanceof PlayerAuthInputPacket input) {
            Frame frame = observation.frame;
            if (frame == null || observation.direction == null) { reset(); return; }
            if (latest != null && frame.tick != latest.tick + 1) {
                reset();
            } else {
                List<Pending> rejected = new ArrayList<>();
                while (!pending.isEmpty()) {
                    Pending held = pending.removeFirst();
                    if (frame.tick != held.placement.tick + 1 || evaluate(held.placement, observation.direction,frame.camera)) {
                        rejected.add(held);
                    } else {
                        try {


                            if (held.transaction != null) applyPlacement(held.transaction);
                        } catch (RuntimeException error) {
                            rejected.add(held);
                            debug("skip=deferred_transaction_error error=" + error.getClass().getSimpleName());
                        }
                    }
                }


                rejected.forEach(this::discard);
                if (observation.generation != generation) return;

                if (observation.placement != null && input.getItemUseTransaction() != null) {
                    if (evaluate(observation.placement, observation.direction,frame.camera)) {
                        input.setItemUseTransaction(null);
                        input.getInputData().remove(PlayerAuthInputData.PERFORM_ITEM_INTERACTION);
                        discard(new Pending(observation.placement, null, level));
                    }
                }
            }
            previous = latest;


            final var motion = context.acceptedMotion();



            double cameraYOffset = cameraYOffset(player, frame.sneaking);
            Point cameraPosition = new Point(frame.position.x, frame.position.y + cameraYOffset, frame.position.z);
            latest = new Frame(frame.tick, cameraPosition, frame.inputMode, frame.interactionModel, frame.camera,
                    motion == null ? null : new Point(motion.x, motion.y, motion.z), frame.sneaking,
                    frame.selectionCamera, cameraYOffset);
        } else if (observation.placement != null) {
            InventoryTransactionPacket transaction = (InventoryTransactionPacket) event.getPacket();
            boolean failed = observation.placement.prediction.failed;
            Pending held = new Pending(observation.placement, failed ? null : transaction, level);


            if (!failed) event.setCancelled(true);
            if (pending.size() >= MAX_PENDING) {
                debug("skip=pending_limit");
                discard(held);
                return;
            }
            pending.addLast(held);
            scheduleExpiry(() -> {
                if (pending.remove(held)) {
                    debug("skip=interaction_timeout");
                    discard(held);
                }
            });
        }
    }






    static double cameraYOffset(WirePlayerState player, boolean sneaking) {
        if (player.horizontalPoseApplied()) return .42D - 1.62D;
        return sneaking ? -.35D : 0D;
    }

    public void onPacketSend(PacketEvent event) {
        if (event.isCancelled()) return;
        if (event.getPacket() instanceof ChangeDimensionPacket
                || event.getPacket() instanceof CorrectPlayerMovePredictionPacket
                || event.getPacket() instanceof MovePlayerPacket move
                && move.getRuntimeEntityId() == context.runtimeId() && move.getMode() != MovePlayerPacket.Mode.HEAD_ROTATION) reset();
    }

    private boolean available() {
        return context.available();
    }

    private void reset() {
        generation++;
        while (!pending.isEmpty()) discard(pending.removeFirst());
        previous = latest = null;
        bridge = new RotationPlaceBridge();
        bridgeSlot = -1;
    }


    public void disable() {
        var released = new ArrayList<Pending>(pending);
        pending.clear();
        for (Pending held : released) {
            held.placement.bridge.stop();
            if (held.level == context.levelIdentity() && held.transaction != null && !held.placement.prediction.failed)
                applyPlacement(held.transaction);
        }
        reset();
        stagedEvent = null;
        staged = null;
    }

    public void skipPacket(PacketEvent event) {
        if (stagedEvent == event) {
            stagedEvent = null;
            staged = null;
        }
    }



    public void predictedPlacement(UseItemData data, Cell position, int blockId) {
        Placement placement = staged == null ? null : staged.placement;
        if (placement == null || placement.prediction.transaction != data
                || !placement.placed.equals(position) || data.itemInHand == null || !data.itemInHand.mapped()
                || data.itemInHand.blockId() != blockId) return;
        placement.prediction.placed = context.block(position).id() == blockId;
    }

    protected void scheduleExpiry(Runnable expiry) { context.scheduleExpiry(expiry,20); }
    protected void applyPlacement(InventoryTransactionPacket transaction) { context.applyPlacement(transaction); }

    private void discard(Pending held) {
        if (held.placement.prediction.failed) return;
        held.placement.bridge.stop();
        if (held.level == context.levelIdentity()) resyncPlacement(held.placement.target,held.placement.placed);
        resyncSlot(held.placement.hotbarSlot);
    }
    protected void resyncPlacement(Cell target,Cell placed) { context.resyncPlacement(target,placed); }
    protected void resyncSlot(int slot) { if(slot>=0&&slot<=8)context.resyncSlot(slot); }

    private Placement capture(InventoryTransactionPacket transaction, String source, Frame controls) {
        if (transaction == null || transaction.getTransactionType() != org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType.ITEM_USE
                ) return null;
        UseItemData data=context.placementData(transaction);
        if (data == null || data.actionType != 0
                || data.blockPos == null || data.face == null || !(data.itemInHand!=null&&data.itemInHand.placeable()&&data.itemInHand.blockId()!=0&&data.itemInHand.blockId()!=420)) return null;
        if (data.triggerType == 1 || bridgeSlot != data.hotbarSlot
                || bridgeItemId != data.itemInHand.id() || bridgeItemDamage != data.itemInHand.damage()) {
            bridge = new RotationPlaceBridge();
            bridgeSlot = data.hotbarSlot;
            bridgeItemId = data.itemInHand.id();
            bridgeItemDamage = data.itemInHand.damage();
        }




        if (data.clientInteractPrediction == CLIENT_PREDICTION_FAILURE) {
            debug("skip=client_prediction_failure source=" + source
                    + " tick=" + (latest == null ? "unknown" : latest.tick)
                    + " target=" + data.blockPos.x() + "," + data.blockPos.y() + "," + data.blockPos.z()
                    + " face=" + (data.face==null?null:data.face.name().toLowerCase(Locale.ROOT)) + " clientPrediction=" + data.clientInteractPrediction);


            if (data.triggerType != 2) return null;
        }
        if (previous == null || latest == null || latest.tick != previous.tick + 1) {
            debug("skip=missing_history");
            return null;
        }
        Point click = data.clickPos == null ? null : new Point(data.clickPos.x, data.clickPos.y, data.clickPos.z);
        InputMode inputMode = controls == null ? null : controls.inputMode;
        InputInteractionModel interactionModel = controls == null ? null : controls.interactionModel;
        Point a = previous.position, b = latest.position;

        Point delta = b.minus(a);
        if (delta.x * delta.x + delta.y * delta.y + delta.z * delta.z > 16) {
            debug("skip=position_discontinuity");
            return null;
        }
        Cell target = new Cell(data.blockPos.x(), data.blockPos.y(), data.blockPos.z());
        if (!context.chunkLoaded(target.x(), target.z())) {
            debug("skip=unloaded_target");
            return null;
        }
        BlockView state = context.block(target);
        if (state.air()) { debug("skip=unknown_target"); return null; }
        Cell placed = (state.replaceable()?target:new Cell(target.x()+data.face.getXOffset(),target.y()+data.face.getYOffset(),target.z()+data.face.getZOffset()));
        if (!context.chunkLoaded(placed.x(), placed.z())) {
            debug("skip=unloaded_destination"); return null;
        }
        BlockView destination = context.block(placed);




        if (data.clientInteractPrediction != CLIENT_PREDICTION_FAILURE
                && !destination.air() && !destination.replaceable()
                && destination.sturdy()
                && !(data.itemInHand.slab()
                && destination.slab()
                && data.itemInHand.blockId() == destination.id())) {
            debug("skip=occupied_destination source=" + source + " tick=" + latest.tick
                    + " target=" + target.x() + "," + target.y() + "," + target.z() + " face=" + (data.face==null?null:data.face.name().toLowerCase(Locale.ROOT))
                    + " placed=" + placed.x() + "," + placed.y() + "," + placed.z()
                    + " destinationState=" + describeState(destination)
                    + " clientPrediction=" + data.clientInteractPrediction);
            return null;
        }


        final var bounds = state.outline();
        Box outline = bounds == null ? null : bounds.clone();
        if (outline == null || !outline.isValid()) { debug("skip=unknown_outline"); return null; }
        if (touchSelection(inputMode, interactionModel)) {


            var candidates = CameraCandidates.assess(controls.selectionCamera,
                    new Pair<>(new Vec3(a.x, a.y, a.z), new Vec3(b.x, b.y, b.z)),
                    0, outline, SCREEN_SELECTION_RANGE);
            return new Placement(latest.tick, a, b, target, data.face, outline, List.of(), SCREEN_SELECTION_RANGE,
                    source, describeState(state), List.of(), data.triggerType, click, null,
                    placed, data.hotbarSlot, inputMode, interactionModel, latest.camera,
                    Double.NaN, Double.NaN, bridge, latest.motion, latest.sneaking,
                    new PredictionEvidence(data), candidates, previous.cameraYOffset, latest.cameraYOffset);
        }


        double reach = SCREEN_SELECTION_RANGE;
        int minX = (int) Math.floor(Math.min(Math.min(a.x, b.x), outline.minX));
        int maxX = (int) Math.floor(Math.max(Math.max(a.x, b.x), outline.maxX));
        int minY = Math.max(context.minY(), (int) Math.floor(Math.min(Math.min(a.y, b.y), outline.minY)));
        int maxY = Math.min(context.maxY() - 1, (int) Math.floor(Math.max(Math.max(a.y, b.y), outline.maxY)));
        int minZ = (int) Math.floor(Math.min(Math.min(a.z, b.z), outline.minZ));
        int maxZ = (int) Math.floor(Math.max(Math.max(a.z, b.z), outline.maxZ));



        boolean fallbackCandidate = data.face != Direction.UP && data.face != Direction.DOWN
                && downwardInterval(a, b, outline) != null;
        boolean repeatCandidate = data.triggerType == 2;
        if (repeatCandidate) {


            minX = Math.min(minX, (int)Math.floor(Math.min(a.x, b.x) - reach - EPSILON));
            maxX = Math.max(maxX, (int)Math.floor(Math.max(a.x, b.x) + reach + EPSILON));
            minY = Math.max(context.minY(),
                    Math.min(minY, (int)Math.floor(Math.min(a.y, b.y) - reach - EPSILON)));
            maxY = Math.min(context.maxY() - 1,
                    Math.max(maxY, (int)Math.floor(Math.max(a.y, b.y) + reach + EPSILON)));
            minZ = Math.min(minZ, (int)Math.floor(Math.min(a.z, b.z) - reach - EPSILON));
            maxZ = Math.max(maxZ, (int)Math.floor(Math.max(a.z, b.z) + reach + EPSILON));
        }
        if (fallbackCandidate) {
            double radius = reach * Math.sqrt(1 - (double) 0.7F * 0.7F) + EPSILON;
            minX = Math.min(minX, (int) Math.floor(Math.min(a.x, b.x) - radius));
            maxX = Math.max(maxX, (int) Math.floor(Math.max(a.x, b.x) + radius));
            minZ = Math.min(minZ, (int) Math.floor(Math.min(a.z, b.z) - radius));
            maxZ = Math.max(maxZ, (int) Math.floor(Math.max(a.z, b.z) + radius));
            minY = Math.max(context.minY(),
                    Math.min(minY, (int) Math.floor(Math.min(a.y, b.y) - reach - EPSILON)));
        }
        int cellLimit = fallbackCandidate || repeatCandidate ? MAX_FALLBACK_CELLS : MAX_CELLS;
        long sizeX = (long) maxX - minX + 1, sizeY = (long) maxY - minY + 1, sizeZ = (long) maxZ - minZ + 1;
        if (minX == Integer.MIN_VALUE || maxX == Integer.MAX_VALUE
                || minZ == Integer.MIN_VALUE || maxZ == Integer.MAX_VALUE
                || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0
                || sizeX > cellLimit || sizeY > cellLimit || sizeZ > cellLimit
                || sizeX * sizeY * sizeZ > cellLimit) { debug("skip=query_limit"); return null; }
        List<Box> blockers = new ArrayList<>();
        List<String> blockerStates = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            if (!context.chunkLoaded(x, z)) {
                debug("skip=unloaded_path");
                return null;
            }
            for (int y = minY; y <= maxY; y++) {
                if (x == target.x() && y == target.y() && z == target.z()) continue;
                BlockView obstacle = context.block(new Cell(x,y,z));


                if (!obstacle.air() && obstacle.sturdy()) {
                    blockers.add(new Box(x, y, z, x + 1, y + 1, z + 1));
                    blockerStates.add(describeState(obstacle));
                }
            }
        }
        Point reported = data.playerPos == null ? null : new Point(data.playerPos.x, data.playerPos.y, data.playerPos.z);
        return new Placement(latest.tick, a, b, target, data.face, outline, List.copyOf(blockers), reach,
                source, describeState(state), List.copyOf(blockerStates), data.triggerType, click, reported,
                placed, data.hotbarSlot,
                inputMode, interactionModel, latest.camera,
                uiSelectionRange(selectionModel(inputMode, interactionModel), context.gameType()),
                interactionModel == InputInteractionModel.CLASSIC ? (double) 5.6F : Double.NaN,
                bridge, latest.motion, latest.sneaking, new PredictionEvidence(data), null,
                previous.cameraYOffset, latest.cameraYOffset);
    }


    private boolean evaluate(Placement placement, Point direction,RotationPlaceInterpolation.Rotation nextCamera) {
        if (touchSelection(placement.inputMode, placement.interactionModel)) {

            if (placement.prediction.failed) return false;
            var candidates = placement.cameraCandidates;
            boolean miss = candidates != null && "MISS".equals(candidates.conditionalVerdict());
            debug("result=" + (miss ? "MISS" : "SKIP") + " tick=" + placement.tick
                    + " selection=camera_candidates target=" + placement.target + " cameraCandidates=" + candidates);
            if (miss) {
                fail("camera candidates miss under projection policy.");
                return true;
            }

            if (placement.prediction.placed && (placement.triggerType == 1 || placement.triggerType == 2))
                placement.bridge.success(new RotationPlaceBridge.Cell(placement.placed));
            return false;
        }
        boolean detailed = context.debugEnabled();
        Trace trace = detailed ? new Trace() : null;
        boolean hit = canHit(placement.from, placement.to, direction, placement.outline,
                placement.face, placement.blockers, trace);
        boolean ordinaryHit = hit;
        String normalReason = trace == null ? null : trace.reason;
        if (!hit) hit = canHitDownwardSelections(placement.from, placement.to, direction, placement.outline,
                placement.face, placement.uiSelectionRange, placement.alternateUiRange, placement.blockers, trace);
        boolean uncertain = false;
        boolean bridgeCandidate = canBridge(placement, direction, trace);
        boolean eligibleCandidate = bridgeCandidate || !placement.bridge.active && !RotationPlaceBridge.moving(placement.motion);
        if (placement.prediction.failed) {
            if (ordinaryHit || bridgeCandidate)
                placement.bridge.repeat(new RotationPlaceBridge.Cell(placement.placed), false,
                        eligibleCandidate, placement.sneaking);
            return false;
        }
        if (!hit && bridgeCandidate) {



            uncertain = true;
            if (trace != null) trace.reason = "bridge_policy_unknown";
        }
        if (!hit && !uncertain && placement.camera != null && placement.camera.finite()
                && nextCamera != null && nextCamera.finite()) {
            var result=RotationPlaceInterpolation.solve(placement.from,placement.to,placement.camera,nextCamera,
                    placement.outline,placement.face,placement.blockers);
            uncertain=result.verdict()==RotationPlaceInterpolation.Verdict.UNKNOWN;
            hit=result.verdict()==RotationPlaceInterpolation.Verdict.HIT;
            if(trace!=null) {
                trace.rotationResult=result.verdict().name();trace.rotationNodes=result.nodes();
                if(hit) {
                    trace.reason="interpolated_rotation_hit";
                    trace.allowed=new Interval(result.alpha(),result.alpha());
                    trace.candidateOrigin=result.origin();trace.candidateHit=result.hit();
                    Point ray=result.hit().minus(result.origin());
                    trace.candidateDistance=Math.sqrt(ray.x*ray.x+ray.y*ray.y+ray.z*ray.z);
                    trace.obstructionCount=0;trace.obstructions.clear();
                } else if(uncertain) trace.reason="rotation_interval_unknown";
            }
        }
        if (detailed) {
            Box cell = new Box(placement.target.x(), placement.target.y(), placement.target.z(),
                    placement.target.x() + 1.0D, placement.target.y() + 1.0D, placement.target.z() + 1.0D);
            boolean unitFaceHit = canHit(placement.from, placement.to, direction, cell, placement.face, List.of());
            debug(String.format(Locale.ROOT, "result=%s tick=%d target=%d,%d,%d face=%s originA=%s originB=%s direction=%s",
                    hit ? "HIT" : uncertain ? "SKIP" : "MISS", placement.tick, placement.target.x(), placement.target.y(),
                    placement.target.z(), placement.face, placement.from, placement.to, direction)
                    + " reason=" + trace.reason + " normalReason=" + normalReason + " fallbackReason=" + trace.fallbackReason
                    + " source=" + placement.source + " snapshotPhase=before_prediction"
                    + " inputMode=" + placement.inputMode + " interactionModel=" + placement.interactionModel
                    + " selectionModel=" + selectionModel(placement.inputMode, placement.interactionModel)
                    + " selection=" + "interaction_rotation"
                    + " cameraYOffsetA=" + placement.cameraYOffsetA + " cameraYOffsetB=" + placement.cameraYOffsetB
                    + " cameraStart=" + placement.camera + " cameraEnd=" + nextCamera
                    + " rotationResult=" + trace.rotationResult + " rotationNodes=" + trace.rotationNodes
                    + " originTicks=" + (placement.tick - 1) + "," + placement.tick
                    + " interactionTick=" + (placement.tick + 1) + " selectionRange=" + placement.selectionRange
                    + " uiSelectionRange=" + placement.uiSelectionRange + " alternateUiRange=" + placement.alternateUiRange
                    + " pickPath=" + trace.pickPath + " uiFallbackReason=" + trace.uiFallbackReason
                    + " screenFallbackReason=" + trace.screenFallbackReason
                    + " bridgeReason=" + trace.bridgeReason + " bridgeMotion=" + placement.motion
                    + " bridgeLast=" + placement.bridge.lastPlaced + " bridgeStep=" + placement.bridge.step
                    + " bridgeNext=" + placement.bridge.next + " placementPredicted=" + placement.prediction.placed
                    + " outline=" + placement.outline + " targetState=" + placement.targetState
                    + " unitFaceHit=" + unitFaceHit + " triggerType=" + placement.triggerType
                    + " clickPos=" + placement.clickPos + " reportedPosition=" + placement.reportedPosition
                    + " allowed=" + trace.allowed + " candidateOrigin=" + trace.candidateOrigin
                    + " candidateHit=" + trace.candidateHit + " candidateDistance=" + trace.candidateDistance
                    + " planeDistanceRange=" + trace.distanceAtStart + "," + trace.distanceAtEnd
                    + " blockerCount=" + placement.blockers.size()
                    + describeObstructions(trace, placement.blockers, placement.blockerStates)
                    + describeOrdinaryCuts(trace, placement));
        }
        if (!hit && !uncertain) {
            fail(String.format(Locale.ROOT, "ray_miss face=%s pos=%d,%d,%d",
                    placement.face.name().toLowerCase(Locale.ROOT), placement.target.x(), placement.target.y(), placement.target.z()));
            return true;
        }
        if (placement.prediction.placed && (placement.triggerType == 1 || placement.triggerType == 2)) {
            RotationPlaceBridge.Cell destination = new RotationPlaceBridge.Cell(placement.placed);
            if (placement.triggerType == 2) placement.bridge.repeat(destination, true, eligibleCandidate, placement.sneaking);
            else placement.bridge.success(destination);
        }
        return false;
    }

    private static String describeState(BlockView state) {
        return state.state().identifier() + "/" + state.id() + ":" + state.damage();
    }

    private static String describeObstructions(Trace trace, List<Box> boxes, List<String> states) {
        StringBuilder text = new StringBuilder(" obstructionCount=").append(trace.obstructionCount).append(" obstructions=[");
        for (int i = 0; i < trace.obstructions.size(); i++) {
            if (i > 0) text.append(';');
            Obstruction cut = trace.obstructions.get(i);
            text.append("{state=").append(states.get(cut.index)).append(",bounds=")
                    .append(boxes.get(cut.index)).append(",alpha=").append(cut.covered)
                    .append(",remainingIntervals=").append(cut.remainingIntervals).append('}');
        }
        return text.append(']').toString();
    }

    private void debug(String message) {
        if (context.debugEnabled())
            context.debug("[rotation-place] player=" + player.getName() + " " + message);
    }

    private static String describeOrdinaryCuts(Trace trace, Placement placement) {
        StringBuilder text = new StringBuilder(" ordinaryCutCount=").append(trace.ordinaryCutCount)
                .append(" ordinaryCuts=[");
        for (int i = 0; i < trace.ordinaryCuts.size(); i++) {
            if (i > 0) text.append(';');
            Obstruction cut = trace.ordinaryCuts.get(i);
            text.append("{state=").append(cut.index < 0 ? placement.targetState : placement.blockerStates.get(cut.index))
                    .append(",bounds=").append(cut.index < 0 ? placement.outline : placement.blockers.get(cut.index))
                    .append(",alpha=").append(cut.covered)
                    .append(",remainingIntervals=").append(cut.remainingIntervals).append('}');
        }
        return text.append(']').toString();
    }

    static Point direction(double pitch, double yaw) {
        if (!Double.isFinite(pitch) || !Double.isFinite(yaw)) return null;
        double p = Math.toRadians(Math.IEEEremainder(pitch, 360));
        double y = Math.toRadians(Math.IEEEremainder(yaw, 360));
        return new Point(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    static double uiSelectionRange(InputInteractionModel model, GameType gameType) {


        if (model == null) return Double.NaN;
        return model == InputInteractionModel.CROSSHAIR ? (double) 5.7F
                : gameType == GameType.CREATIVE ? 12.0F : (double) 6.7F;
    }

    static InputInteractionModel selectionModel(InputMode inputMode, InputInteractionModel reported) {


        return inputMode == InputMode.MOUSE && reported == InputInteractionModel.TOUCH
                ? InputInteractionModel.CROSSHAIR : reported;
    }

    private static boolean bridgeMiss(Trace trace, String reason) {
        if (trace != null) trace.bridgeReason = reason;
        return false;
    }

    private static boolean canBridge(Placement p, Point direction, Trace trace) {
        RotationPlaceBridge state = p.bridge;
        if (p.triggerType != 2) return bridgeMiss(trace, "not_repeat");
        if (state.lastPlaced == null) return bridgeMiss(trace, "no_predicted_success");
        if (state.active) {
            if (!state.support().equals(new RotationPlaceBridge.Cell(p.target)) || state.face != p.face)
                return bridgeMiss(trace, "different_expected_support_or_face");
            Box next = new Box(state.next.x(), state.next.y(), state.next.z(),
                    state.next.x() + 1D, state.next.y() + 1D, state.next.z() + 1D);
            List<Box> blockers = new ArrayList<>(p.blockers);
            blockers.add(p.outline);


            if (!canPickCell(p.from, p.to, direction, next, p.selectionRange, blockers))
                return bridgeMiss(trace, "next_cell_miss_or_occluded");
            if (trace != null) trace.bridgeReason = "active_next_cell";
            return true;
        }
        if (!RotationPlaceBridge.moving(p.motion)) return bridgeMiss(trace, "motion_below_threshold");
        if (RotationPlaceBridge.motionFace(p.motion) != p.face) return bridgeMiss(trace, "different_motion_face");


        if (!p.sneaking && !state.lastPlaced.equals(new RotationPlaceBridge.Cell(p.target)))
            return bridgeMiss(trace, "different_last_placed");
        boolean ordinary = false;
        double[] ranges = {p.uiSelectionRange, p.alternateUiRange, p.selectionRange};
        for (double range : ranges) {
            if (!Double.isFinite(range)) continue;
            if (canPickCell(p.from, p.to, direction, p.outline, range, p.blockers)) {
                ordinary = true;
                break;
            }
            if (!p.sneaking) {


                for (Box block : p.blockers) {
                    if (canPickCell(p.from, p.to, direction, block, range, List.of())) {
                        ordinary = true;
                        break;
                    }
                }
            }
            if (ordinary) break;
        }
        if (!ordinary) return bridgeMiss(trace, "no_ordinary_block_hit");
        if (trace != null) trace.bridgeReason = p.sneaking ? "sneaking_motion_face" : "motion_seed";
        return true;
    }

    static boolean canPickCell(Point from, Point to, Point direction, Box cell, double range, List<Box> blockers) {



        boolean unresolvedBoundary = false;
        for (Direction face : Direction.values()) {
            Trace witness = new Trace();
            if (!canHit(from, to, direction, cell, face, blockers, witness, range)) continue;
            double distance = range;
            for (Box blocker : blockers) distance = Math.min(distance, entryDistance(witness.candidateOrigin, direction, blocker));
            Point origin = witness.candidateOrigin;
            Point end = new Point(origin.x + direction.x * distance,
                    origin.y + direction.y * distance, origin.z + direction.z * distance);
            if (RotationPlaceBridge.intersects(cell, origin, end)) return true;



            unresolvedBoundary = true;
        }
        return unresolvedBoundary;
    }

    private static double entryDistance(Point origin, Point direction, Box box) {
        double near = 0, far = Double.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            double d = direction.at(axis), value = origin.at(axis);
            if (Math.abs(d) < 1.0E-10) {
                if (value < min(box, axis) || value > max(box, axis)) return Double.POSITIVE_INFINITY;
            } else {
                double a = (min(box, axis) - value) / d, b = (max(box, axis) - value) / d;
                near = Math.max(near, Math.min(a, b));
                far = Math.min(far, Math.max(a, b));
            }
        }
        return near <= far ? near : Double.POSITIVE_INFINITY;
    }

    static boolean canHitDownwardSelections(Point from, Point to, Point direction, Box target, Direction face,
                                            double uiRange, double alternateUiRange, List<Box> blockers, Trace trace) {



        if (Double.isFinite(uiRange)) {
            if (canHitDownward(from,to,direction,target,face,uiRange,blockers,trace)) {
                if (trace != null) { trace.reason="ui_downward_hit";trace.pickPath="ui"; }
                return true;
            }
            if (trace != null) trace.uiFallbackReason=trace.fallbackReason;
        }



        if (Double.isFinite(alternateUiRange) && alternateUiRange != uiRange
                && canHitDownward(from,to,direction,target,face,alternateUiRange,blockers,trace)) {
            if (trace != null) { trace.reason="ui_downward_hit";trace.pickPath="ui_classic_context"; }
            return true;
        }
        if (canHitDownward(from,to,direction,target,face,SCREEN_SELECTION_RANGE,blockers,trace)) {
            if (trace != null) trace.pickPath="screen";
            return true;
        }
        if (trace != null) trace.screenFallbackReason=trace.fallbackReason;
        return false;
    }


    static boolean canHit(Point from, Point to, Point direction, Box box, Direction face,
                          List<Box> blockers) {
        return canHit(from, to, direction, box, face, blockers, null);
    }

    static boolean canHit(Point from, Point to, Point direction, Box box, Direction face,
                          List<Box> blockers, Trace trace) {
        return canHit(from, to, direction, box, face, blockers, trace, Double.POSITIVE_INFINITY);
    }

    private static boolean canHit(Point from, Point to, Point direction, Box box, Direction face,
                                 List<Box> blockers, Trace trace, double range) {
        if (from == null || to == null || direction == null || box == null || face == null
                || !from.finite() || !to.finite() || !direction.finite()
                || !box.isValid()) return miss(trace, "invalid_input");
        int axis = switch (face) { case WEST, EAST -> 0; case DOWN, UP -> 1; default -> 2; };
        boolean positive = face == Direction.EAST || face == Direction.UP || face == Direction.SOUTH;
        double component = direction.at(axis);
        if (positive ? component >= -1.0E-10 : component <= 1.0E-10) return miss(trace, "wrong_face_direction");
        double plane = positive ? max(box, axis) : min(box, axis);
        Point delta = to.minus(from);
        double t0 = (plane - from.at(axis)) / component;
        double t1 = -delta.at(axis) / component;
        if (trace != null) { trace.distanceAtStart = t0; trace.distanceAtEnd = t0 + t1; }
        double rayLimit = Math.min(range, Math.max(0, Math.max(t0, t0 + t1)) + EPSILON);
        Interval allowed = constrain(new Interval(0, 1), t0, t1, -EPSILON, rayLimit);
        if (allowed == null) return miss(trace, "behind_target_face");
        for (int other = 0; other < 3 && allowed != null; other++) {
            if (other == axis) continue;
            allowed = constrain(allowed, from.at(other) + direction.at(other) * t0,
                    delta.at(other) + direction.at(other) * t1,
                    min(box, other) - EPSILON, max(box, other) + EPSILON);
        }
        if (allowed == null) return miss(trace, "outside_target_face");
        if (trace != null) {
            trace.allowed = allowed;
            double alpha = (allowed.min + allowed.max) * 0.5;
            double distance = t0 + t1 * alpha;
            trace.candidateOrigin = new Point(from.x + delta.x * alpha, from.y + delta.y * alpha, from.z + delta.z * alpha);
            trace.candidateHit = new Point(trace.candidateOrigin.x + direction.x * distance,
                    trace.candidateOrigin.y + direction.y * distance, trace.candidateOrigin.z + direction.z * distance);
            trace.candidateDistance = distance;
        }
        List<Interval> visible = new ArrayList<>();
        visible.add(allowed);
        for (int index = 0; index < blockers.size(); index++) {
            Box blocker = blockers.get(index);
            Interval covered = occlusion(from, delta, direction, blocker, allowed, t0, t1, rayLimit);
            if (covered == null) continue;
            List<Interval> remaining = new ArrayList<>();
            for (Interval segment : visible) {
                if (covered.max < segment.min || covered.min > segment.max) remaining.add(segment);
                else {
                    if (covered.min > segment.min) remaining.add(new Interval(segment.min, covered.min));
                    if (covered.max < segment.max) remaining.add(new Interval(covered.max, segment.max));
                }
            }
            if (trace != null && visible.stream().anyMatch(segment -> covered.max >= segment.min && covered.min <= segment.max))
                trace.cut(index, covered, remaining.size());
            visible = remaining;
            if (visible.isEmpty()) return miss(trace, "occluded");
        }
        if (trace != null) {
            trace.reason = "hit";
            trace.allowed = visible.getFirst();
            double alpha = (trace.allowed.min + trace.allowed.max) * .5;
            double distance = t0 + t1 * alpha;
            trace.candidateOrigin = new Point(from.x + delta.x * alpha, from.y + delta.y * alpha, from.z + delta.z * alpha);
            trace.candidateHit = new Point(trace.candidateOrigin.x + direction.x * distance,
                    trace.candidateOrigin.y + direction.y * distance, trace.candidateOrigin.z + direction.z * distance);
            trace.candidateDistance = distance;
        }
        return true;
    }

    private static boolean miss(Trace trace, String reason) {
        if (trace != null) trace.reason = reason;
        return false;
    }

    static boolean touchSelection(InputMode mode, InputInteractionModel model) {
        return mode == InputMode.TOUCH && model != InputInteractionModel.CROSSHAIR;
    }



    static boolean canHitTouch(Point from, Point to, Point point, Box target, Direction face,
                               List<Box> blockers, Trace trace) {
        if (from == null || to == null || point == null || target == null || face == null
                || !from.finite() || !to.finite() || !point.finite() || !target.isValid())
            return miss(trace, "invalid_touch_point");
        for (int axis = 0; axis < 3; axis++)
            if (point.at(axis) < min(target, axis) - EPSILON || point.at(axis) > max(target, axis) + EPSILON)
                return miss(trace, "touch_point_outside_target");
        int axis = switch (face) { case WEST, EAST -> 0; case DOWN, UP -> 1; default -> 2; };
        boolean positive = face == Direction.EAST || face == Direction.UP || face == Direction.SOUTH;
        double plane = positive ? max(target, axis) : min(target, axis);
        if (Math.abs(point.at(axis) - plane) > EPSILON) return miss(trace, "touch_point_off_face");
        Point delta = to.minus(from);
        Interval allowed = constrain(new Interval(0, 1), from.at(axis), delta.at(axis),
                positive ? plane - EPSILON : Double.NEGATIVE_INFINITY,
                positive ? Double.POSITIVE_INFINITY : plane + EPSILON);
        if (allowed == null) return miss(trace, "touch_face_behind");
        List<Interval> visible = new ArrayList<>(List.of(allowed));
        for (int i = 0; i < blockers.size(); i++) {
            Interval covered = pointOcclusion(from, delta, point, blockers.get(i), allowed);
            List<Interval> remaining = subtract(visible, covered);
            if (trace != null && covered != null && visible.stream().anyMatch(s -> covered.max >= s.min && covered.min <= s.max))
                trace.cut(i, covered, remaining.size());
            visible = remaining;
            if (visible.isEmpty()) return miss(trace, "touch_occluded");
        }
        if (trace != null) {
            trace.reason = "touch_point_hit";
            trace.allowed = visible.getFirst();
            double alpha = (trace.allowed.min + trace.allowed.max) * .5;
            trace.candidateOrigin = new Point(from.x + delta.x * alpha,
                    from.y + delta.y * alpha, from.z + delta.z * alpha);
            trace.candidateHit = point;
            Point ray = point.minus(trace.candidateOrigin);
            trace.candidateDistance = Math.sqrt(ray.x * ray.x + ray.y * ray.y + ray.z * ray.z);
        }
        return true;
    }





    private static Interval pointOcclusion(Point from, Point delta, Point point, Box box, Interval allowed) {
        double end = 1 - 1.0E-8;
        List<Vertex> polygon = new ArrayList<>(List.of(new Vertex(allowed.min, 0), new Vertex(allowed.max, 0),
                new Vertex(allowed.max * (1 - end), end), new Vertex(allowed.min * (1 - end), end)));
        Point ray = point.minus(from);
        for (int axis = 0; axis < 3 && !polygon.isEmpty(); axis++) {
            polygon = clip(polygon, delta.at(axis), ray.at(axis), max(box, axis) - EPSILON - from.at(axis));
            polygon = clip(polygon, -delta.at(axis), -ray.at(axis), from.at(axis) - min(box, axis) - EPSILON);
        }
        if (polygon.isEmpty()) return null;
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (Vertex vertex : polygon) {
            double alpha = vertex.alpha / (1 - vertex.distance);
            lo = Math.min(lo, alpha);hi = Math.max(hi, alpha);
        }
        return new Interval(Math.max(allowed.min, lo), Math.min(allowed.max, hi));
    }





    static boolean canHitDownward(Point from, Point to, Point direction, Box target, Direction face,
                                  double reach, List<Box> blockers, Trace trace) {
        if (from == null || to == null || direction == null || target == null || face == null
                || !from.finite() || !to.finite() || !direction.finite() || !target.isValid()
                || !Double.isFinite(reach) || reach <= 0)
            return fallbackMiss(trace, "invalid_input");
        if (!((float) direction.y < -0.7F)) return fallbackMiss(trace, "not_steep");
        float dx = (float) direction.x, dz = (float) direction.z;
        Direction selected = Math.abs(dx) > Math.abs(dz)
                ? (dx >= 0 ? Direction.EAST : Direction.WEST)
                : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        if (face != selected) return fallbackMiss(trace, "different_horizontal_face");
        Interval allowed = downwardInterval(from, to, target);
        if (allowed == null) return fallbackMiss(trace, "outside_downward_pick");
        Point delta = to.minus(from);
        List<Interval> visible = new ArrayList<>(List.of(allowed));
        double t0 = from.y - target.maxY, t1 = delta.y;
        for (Box blocker : blockers) {
            visible = subtract(visible, occlusion(from, delta, DOWN, blocker, allowed, t0, t1, 2));
            if (visible.isEmpty()) return fallbackMiss(trace, "downward_occluded");
        }



        visible = subtractOrdinary(visible, occlusion(from, delta, direction, target, allowed,
                reach + 2 * EPSILON, 0, reach), -1, trace);
        for (int i = 0; i < blockers.size(); i++) {
            if (visible.isEmpty()) break;
            visible = subtractOrdinary(visible, occlusion(from, delta, direction, blockers.get(i), allowed,
                    reach + 2 * EPSILON, 0, reach), i, trace);
        }
        if (visible.isEmpty()) return fallbackMiss(trace, "ordinary_hit");
        if (trace != null) {
            trace.reason = "downward_fallback_hit";
            trace.fallbackReason = "hit";
            trace.allowed = visible.getFirst();
            double alpha = (trace.allowed.min + trace.allowed.max) * .5;
            trace.candidateOrigin = new Point(from.x + delta.x * alpha,
                    from.y + delta.y * alpha, from.z + delta.z * alpha);
            trace.candidateHit = new Point(trace.candidateOrigin.x, target.maxY, trace.candidateOrigin.z);
            trace.candidateDistance = trace.candidateOrigin.y - target.maxY;
            trace.distanceAtStart = t0;
            trace.distanceAtEnd = t0 + t1;
            trace.obstructionCount = 0;
            trace.obstructions.clear();
        }
        return true;
    }

    private static List<Interval> subtractOrdinary(List<Interval> visible, Interval covered, int index, Trace trace) {
        List<Interval> remaining = subtract(visible, covered);
        if (trace != null && !remaining.equals(visible)) {
            trace.ordinaryCutCount++;
            Obstruction cut = new Obstruction(index, covered, remaining.size());
            if (trace.ordinaryCuts.size() < 16) trace.ordinaryCuts.add(cut);
            else trace.ordinaryCuts.set(15, cut);
        }
        return remaining;
    }

    private static boolean fallbackMiss(Trace trace, String reason) {
        if (trace != null) trace.fallbackReason = reason;
        return false;
    }

    private static Interval downwardInterval(Point from, Point to, Box target) {
        Point delta = to.minus(from);
        Interval allowed = constrain(new Interval(0, 1), from.y - target.maxY, delta.y, -EPSILON, 2 + EPSILON);
        allowed = constrain(allowed, from.x, delta.x, target.minX - EPSILON, target.maxX + EPSILON);
        return constrain(allowed, from.z, delta.z, target.minZ - EPSILON, target.maxZ + EPSILON);
    }

    private static List<Interval> subtract(List<Interval> visible, Interval covered) {
        if (covered == null) return visible;
        List<Interval> result = new ArrayList<>();
        for (Interval segment : visible) {
            if (covered.max < segment.min || covered.min > segment.max) result.add(segment);
            else {
                if (covered.min > segment.min) result.add(new Interval(segment.min, covered.min));
                if (covered.max < segment.max) result.add(new Interval(covered.max, segment.max));
            }
        }
        return result;
    }

    private static Interval constrain(Interval interval, double value, double slope, double min, double max) {
        if (interval == null) return null;
        if (Math.abs(slope) < 1.0E-12) return value >= min && value <= max ? interval : null;
        double a = (min - value) / slope, b = (max - value) / slope;
        double lo = Math.max(interval.min, Math.min(a, b)), hi = Math.min(interval.max, Math.max(a, b));
        return lo <= hi ? new Interval(lo, hi) : null;
    }



    private static Interval occlusion(Point from, Point delta, Point direction, Box box,
                                      Interval allowed, double t0, double t1, double reach) {
        List<Vertex> polygon = new ArrayList<>(List.of(new Vertex(allowed.min, 0), new Vertex(allowed.max, 0),
                new Vertex(allowed.max, reach), new Vertex(allowed.min, reach)));
        polygon = clip(polygon, -t1, 1, t0 - EPSILON);
        for (int axis = 0; axis < 3 && !polygon.isEmpty(); axis++) {
            polygon = clip(polygon, delta.at(axis), direction.at(axis), max(box, axis) - EPSILON - from.at(axis));
            polygon = clip(polygon, -delta.at(axis), -direction.at(axis), from.at(axis) - min(box, axis) - EPSILON);
        }
        if (polygon.isEmpty()) return null;
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (Vertex v : polygon) { lo = Math.min(lo, v.alpha); hi = Math.max(hi, v.alpha); }
        return new Interval(lo, hi);
    }

    private static List<Vertex> clip(List<Vertex> polygon, double a, double b, double c) {
        List<Vertex> result = new ArrayList<>();
        if (polygon.isEmpty()) return result;
        Vertex previous = polygon.getLast();
        double previousValue = a * previous.alpha + b * previous.distance - c;
        for (Vertex current : polygon) {
            double value = a * current.alpha + b * current.distance - c;
            if ((value <= 0) != (previousValue <= 0)) {
                double t = previousValue / (previousValue - value);
                result.add(new Vertex(previous.alpha + t * (current.alpha - previous.alpha),
                        previous.distance + t * (current.distance - previous.distance)));
            }
            if (value <= 0) result.add(current);
            previous = current;
            previousValue = value;
        }
        return result;
    }

    private static double min(Box box, int axis) { return axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ; }
    private static double max(Box box, int axis) { return axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ; }



    static final class RotationPlaceBridge {
        record Cell(int x, int y, int z) {
            Cell(PlacementContext.Cell p) { this(p.x(), p.y(), p.z()); }
            Cell add(Cell p) { return new Cell(x + p.x, y + p.y, z + p.z); }
            Cell subtract(Cell p) { return new Cell(x - p.x, y - p.y, z - p.z); }
            boolean unit() { return Math.abs((long)x) + Math.abs((long)y) + Math.abs((long)z) == 1; }
        }

        Cell lastPlaced, step, next;
        Direction face;
        boolean active;

        void firstUse() { stop(); }

        void stop() {
            active = false;
            lastPlaced = step = next = null;
            face = null;
        }




        void repeat(Cell destination, boolean success, boolean eligible, boolean sneaking) {
            if (eligible && !sneaking) {
                if (lastPlaced == null) {
                    active = false;
                } else if (!active) {
                    Cell difference = destination.subtract(lastPlaced);
                    if (difference.unit()) {
                        step = difference;
                        next = destination;
                        face = motionFace(new RotationPlace.Point(step.x, step.y, step.z));
                        active = true;
                    }
                }
                if (active && destination.equals(next) && success) next = destination.add(step);
            }
            if (success) lastPlaced = destination;
        }

        void success(Cell destination) { lastPlaced = destination; }

        Cell support() { return active ? next.subtract(step) : null; }


        static Direction motionFace(RotationPlace.Point motion) {
            float x = (float)motion.x(), y = (float)motion.y(), z = (float)motion.z();
            float ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
            if (ax * ax + ay * ay + az * az <= 0.01F) return Direction.UP;
            if (ay > ax && ay > az) return y > 0 ? Direction.UP : Direction.DOWN;
            if (ax > ay && ax > az) return x > 0 ? Direction.EAST : Direction.WEST;
            return z > 0 ? Direction.SOUTH : Direction.NORTH;
        }

        static boolean moving(RotationPlace.Point motion) {
            if (motion == null || !motion.finite()) return false;
            float x = (float)motion.x(), y = (float)motion.y(), z = (float)motion.z();
            return x * x + y * y + z * z > 0.01F;
        }



        static int delayMillis(float speed, boolean sneaking, boolean flag42,
                               boolean eligible, boolean active, boolean nativeFloorPolicy) {
            float delay = sneaking || flag42 || eligible && !active ? 300F : 200F;
            if (!sneaking && !flag42 && !(eligible && !active) && speed > 0)
                delay = Math.min(900F / speed, 180F);
            if (!nativeFloorPolicy) delay = Math.max(100F, delay);
            return (int)delay;
        }




        static boolean itemPolicy(long properties, boolean predicateD0, boolean predicateD8, boolean predicateE0) {
            return (properties & (0x40000L | 1 | 2 | 0x80)) != 0 || predicateD0 || predicateD8 || predicateE0;
        }


        static boolean intersects(Box box, RotationPlace.Point start, RotationPlace.Point end) {
            float sx=(float)start.x(), sy=(float)start.y(), sz=(float)start.z();
            float dx=((float)end.x()-sx)*.5F, dy=((float)end.y()-sy)*.5F, dz=((float)end.z()-sz)*.5F;
            float minX=(float)box.minX, minY=(float)box.minY, minZ=(float)box.minZ;
            float maxX=(float)box.maxX, maxY=(float)box.maxY, maxZ=(float)box.maxZ;
            float ex=(maxX-minX)*.5F, ey=(maxY-minY)*.5F, ez=(maxZ-minZ)*.5F;
            float mx=(sx+dx)-(minX+maxX)*.5F;
            float my=(sy+dy)-(minY+maxY)*.5F;
            float mz=(sz+dz)-(minZ+maxZ)*.5F;
            float ax=Math.abs(dx), ay=Math.abs(dy), az=Math.abs(dz);
            if (!(Math.abs(mx)<=ex+ax && Math.abs(my)<=ey+ay && Math.abs(mz)<=ez+az)) return false;
            float epsilon=0.0001F;
            return Math.abs(dy*mz-dz*my)<=ey*az+ez*ay+epsilon
                    && Math.abs(dz*mx-dx*mz)<=ez*ax+ex*az+epsilon
                    && Math.abs(dx*my-dy*mx)<=ex*ay+ey*ax+epsilon;
        }
    }


    static final class RotationPlaceInterpolation {
        private static final double EPS = 1.0E-5;



        private static final double DIRECTION_ERROR = 2 * (2 * Math.PI / 65536) + 4.0E-6;
        private static final int MAX_NODES = 4096, MAX_DEPTH = 28;
        record Rotation(double pitch, double yaw) {
            boolean finite() { return Double.isFinite(pitch) && Double.isFinite(yaw); }
        }
        enum Verdict { HIT, MISS, UNKNOWN }
        record Result(Verdict verdict, RotationPlace.Point origin, RotationPlace.Point hit, double alpha, int nodes) { }
        private record Work(double lo, double hi, int depth) { }
        private record Bounds(double lo, double hi) {
            Bounds plus(Bounds b) { return new Bounds(Math.nextDown(lo + b.lo), Math.nextUp(hi + b.hi)); }
            Bounds times(Bounds b) {
                double a = lo * b.lo, c = lo * b.hi, d = hi * b.lo, e = hi * b.hi;
                return new Bounds(Math.nextDown(Math.min(Math.min(a,c),Math.min(d,e))),
                        Math.nextUp(Math.max(Math.max(a,c),Math.max(d,e))));
            }
            Bounds scale(double n) { return times(new Bounds(n,n)); }
            Bounds directionEnvelope() { return new Bounds(lo-DIRECTION_ERROR,hi+DIRECTION_ERROR); }
            Bounds divide(Bounds b) {
                if (b.lo <= 0 && b.hi >= 0) return null;
                return times(new Bounds(Math.nextDown(1 / b.hi),Math.nextUp(1 / b.lo)));
            }
        }

        static Result solve(RotationPlace.Point from, RotationPlace.Point to, Rotation start, Rotation end,
                            Box target, Direction face, List<Box> blockers) {
            if (from == null || to == null || start == null || end == null || !from.finite() || !to.finite()
                    || !start.finite() || !end.finite() || target == null || !target.isValid() || face == null)
                return result(Verdict.UNKNOWN,0);
            start = new Rotation(wrap(start.pitch),wrap(start.yaw));
            double pitchDelta = wrap(wrap(end.pitch) - start.pitch), yawDelta = wrap(wrap(end.yaw) - start.yaw);
            RotationPlace.Point delta = to.minus(from);
            ArrayDeque<Work> pending = new ArrayDeque<>();pending.addLast(new Work(0,1,0));
            boolean unresolved = false;int nodes = 0;
            int axis = switch(face) { case EAST,WEST -> 0;case UP,DOWN -> 1;default -> 2; };
            boolean positive = face == Direction.EAST || face == Direction.UP || face == Direction.SOUTH;
            double plane = positive ? max(target,axis) : min(target,axis);
            while (!pending.isEmpty()) {
                if (++nodes > MAX_NODES) return result(Verdict.UNKNOWN,nodes);
                Work work = pending.removeFirst();double mid = (work.lo + work.hi) * .5;
                RotationPlace.Point origin = origin(from,delta,mid);
                RotationPlace.Point direction = RotationPlace.direction(start.pitch + pitchDelta * mid,start.yaw + yawDelta * mid);

                if (RotationPlace.canHit(origin,origin,direction,target,face,blockers)) {
                    double distance = (plane - origin.at(axis)) / direction.at(axis);
                    return new Result(Verdict.HIT,origin,new RotationPlace.Point(origin.x()+direction.x()*distance,
                            origin.y()+direction.y()*distance,origin.z()+direction.z()*distance),mid,nodes);
                }
                Bounds[] o = new Bounds[3];
                for (int i=0;i<3;i++) o[i]=linear(from.at(i),delta.at(i),work.lo,work.hi);
                Bounds pitch = linear(Math.toRadians(start.pitch),Math.toRadians(pitchDelta),work.lo,work.hi);
                Bounds yaw = linear(Math.toRadians(start.yaw),Math.toRadians(yawDelta),work.lo,work.hi);
                Bounds cp = cos(pitch);
                Bounds[] d = {sin(yaw).times(cp).scale(-1),sin(pitch).scale(-1),cos(yaw).times(cp)};
                for(int i=0;i<3;i++) d[i]=d[i].directionEnvelope();
                if (positive ? d[axis].lo >= -1.0E-10 : d[axis].hi <= 1.0E-10) continue;
                boolean excluded = false;
                Bounds distance = new Bounds(0,Double.POSITIVE_INFINITY);
                for(int i=0;i<3;i++) {
                    Bounds slab = new Bounds(Math.nextDown(min(target,i)-EPS-o[i].hi),
                            Math.nextUp(max(target,i)+EPS-o[i].lo)).divide(d[i]);
                    if(slab==null) continue;
                    distance=new Bounds(Math.max(distance.lo,slab.lo),Math.min(distance.hi,slab.hi));
                    if(distance.lo>distance.hi) {excluded=true;break;}
                }
                if(excluded) continue;
                Bounds t = new Bounds(Math.nextDown(plane-o[axis].hi),Math.nextUp(plane-o[axis].lo)).divide(d[axis]);
                if(t!=null) t=new Bounds(Math.max(t.lo,distance.lo),Math.min(t.hi,distance.hi));
                else if(Double.isFinite(distance.hi)) t=distance;
                if (t != null) {
                    if (t.hi < -EPS || t.lo>t.hi) continue;
                    t = new Bounds(Math.max(0,t.lo),Math.max(0,t.hi));
                    for (int i=0;i<3;i++) {
                        Bounds q=o[i].plus(d[i].times(t));
                        double low=i==axis?plane:min(target,i),high=i==axis?plane:max(target,i);
                        if (q.hi < low-EPS || q.lo > high+EPS) {excluded=true;break;}
                    }
                    if (!excluded) for (Box blocker : blockers) {


                        double sample = interiorDistance(origin,direction,blocker);
                        if (!Double.isFinite(sample) || sample >= t.lo-EPS) continue;
                        boolean inside=true;
                        for (int i=0;i<3;i++) {
                            Bounds q=o[i].plus(d[i].scale(sample));
                            if (q.lo < min(blocker,i)+EPS || q.hi > max(blocker,i)-EPS) {inside=false;break;}
                        }
                        if (inside) {excluded=true;break;}
                    }
                }
                if (excluded) continue;
                if (work.depth >= MAX_DEPTH || work.hi-work.lo < 1.0E-9) {unresolved=true;continue;}
                pending.addLast(new Work(work.lo,mid,work.depth+1));
                pending.addLast(new Work(mid,work.hi,work.depth+1));
            }
            return result(unresolved?Verdict.UNKNOWN:Verdict.MISS,nodes);
        }

        private static Result result(Verdict verdict,int nodes) { return new Result(verdict,null,null,Double.NaN,nodes); }
        private static RotationPlace.Point origin(RotationPlace.Point a,RotationPlace.Point delta,double alpha) {
            return new RotationPlace.Point(a.x()+delta.x()*alpha,a.y()+delta.y()*alpha,a.z()+delta.z()*alpha);
        }
        private static double interiorDistance(RotationPlace.Point origin,RotationPlace.Point direction,Box box) {
            double lo=0,hi=Double.POSITIVE_INFINITY;
            for(int i=0;i<3;i++) {
                double d=direction.at(i);
                if(Math.abs(d)<1.0E-12) {
                    if(origin.at(i)<min(box,i)+EPS || origin.at(i)>max(box,i)-EPS) return Double.NaN;
                } else {
                    double a=(min(box,i)+EPS-origin.at(i))/d,b=(max(box,i)-EPS-origin.at(i))/d;
                    lo=Math.max(lo,Math.min(a,b));hi=Math.min(hi,Math.max(a,b));
                }
            }
            return lo<hi && Double.isFinite(hi)?(lo+hi)*.5:Double.NaN;
        }
        private static Bounds linear(double start,double delta,double lo,double hi) {
            double a=start+delta*lo,b=start+delta*hi;
            return new Bounds(Math.nextDown(Math.min(a,b)),Math.nextUp(Math.max(a,b)));
        }
        private static Bounds sin(Bounds angle) {
            angle=new Bounds(angle.lo-1.0E-12,angle.hi+1.0E-12);
            if(angle.hi-angle.lo>=2*Math.PI) return new Bounds(-1,1);
            double lo=Math.min(Math.sin(angle.lo),Math.sin(angle.hi)),hi=Math.max(Math.sin(angle.lo),Math.sin(angle.hi));
            if(Math.ceil((angle.lo-Math.PI*.5)/(2*Math.PI))<=Math.floor((angle.hi-Math.PI*.5)/(2*Math.PI))) hi=1;
            if(Math.ceil((angle.lo+Math.PI*.5)/(2*Math.PI))<=Math.floor((angle.hi+Math.PI*.5)/(2*Math.PI))) lo=-1;
            return new Bounds(Math.nextDown(lo),Math.nextUp(hi));
        }
        private static Bounds cos(Bounds angle) { return sin(angle.plus(new Bounds(Math.PI*.5,Math.PI*.5))); }
        static double wrap(double angle) { return angle-360*Math.floor((angle+180)/360); }
        private static double min(Box b,int i) { return i==0?b.minX:i==1?b.minY:b.minZ; }
        private static double max(Box b,int i) { return i==0?b.maxX:i==1?b.maxY:b.maxZ; }
    }
}
