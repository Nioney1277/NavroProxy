package ac.navro.proxy.packets.player;

import static ac.navro.proxy.util.block.PlacementContext.*;

import ac.navro.anticheat.util.math.*;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.check.impl.place.AirLiquidPlace;
import ac.navro.proxy.check.impl.place.FarPlace;
import ac.navro.proxy.check.impl.place.RotationPlace;
import ac.navro.proxy.util.block.PlacementContext;
import ac.navro.proxy.data.block.*;
import ac.navro.proxy.data.inventory.*;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.packets.input.PacketEvent;
import ac.navro.proxy.util.ItemUtil;
import ac.navro.proxy.validator.inventory.ItemTransactionValidator;

import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.*;
import org.cloudburstmc.protocol.bedrock.packet.*;

import java.util.*;
import java.util.function.*;






public final class PlacementPipeline
        implements PlacementContext,
                ItemTransactionValidator.ClickBlockContext,
                ItemTransactionValidator.TransactionContext {
    public static final class MissingFact extends RuntimeException {
        public MissingFact(String message) {
            super(message);
        }
    }

    private final SessionState state;
    private final RotationPlace rotation;
    private final AirLiquidPlace air;
    private final FarPlace far;
    private final IdentityHashMap<InventoryTransactionPacket, UseItemData> views =
            new IdentityHashMap<>();
    private final IdentityHashMap<PlayerAuthInputPacket, InventoryTransactionPacket> embedded =
            new IdentityHashMap<>();
    private final ArrayList<Expiry> expiries = new ArrayList<>();
    private final Set<InventoryTransactionPacket> skippedPredictions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Integer> pendingSlotCorrections = new LinkedHashSet<>();
    private final Map<Cell, Cell> pendingBlockCorrections = new LinkedHashMap<>();
    private final Object[] level = {new Object()};
    private record Expiry(int due, Runnable task) {}

    private Function<InventoryTransactionPacket, Boolean> forward = packet -> false;
    private Consumer<BedrockPacket> sendClient = packet -> {};
    private PacketEvent current;

    public PlacementPipeline(SessionState state) {
        this.state = state;
        rotation = new RotationPlace(state.player(), this);
        far = new FarPlace(state.player(), this);
        air =
                new AirLiquidPlace(
                        state.player(),
                        new AirLiquidPlace.Context() {
                            public boolean placeable(ItemData item) {
                                if (item == null || nativeIsNull(decodedClaim(item))) return false;
                                var p =
                                        NativeItemDecoder.placement(
                                                state.player().protocolVersion(),
                                                decodedClaim(item));
                                return p.canBePlaced() && p.blockId() != 0;
                            }

                            public boolean validate(ItemData held, ItemData claimed) {
                                return PlacementPipeline.this.validate(held, claimed);
                            }

                            public ItemData held() {
                                return state.inventory().inventoryContainer.getHeldItemData();
                            }

                            public GameType serverGameType() {
                                return gameType();
                            }

                            public boolean hadRecentPlacementSupport(Vector3i p) {
                                return world().hadRecentPlacementSupport(p);
                            }

                            public int clickedBlockId(Vector3i p) {
                                var block =
                                        world().networkBlockState(
                                                        world().getRawBlockAt(
                                                                        p.getX(), p.getY(),
                                                                        p.getZ(), 0));
                                return block == null
                                        ? 0
                                        : NativeBlockProperties.forState(block).blockId();
                            }

                            public void restore(Vector3i p) {
                                restoreCorrectBlock(p);
                            }

                            public void resendInventory() {
                                resyncSlot(state.inventory().heldItemSlot);
                            }

                            public InventoryTransactionPacket embedded(
                                    PlayerAuthInputPacket input) {
                                return embeddedTransaction(input);
                            }
                        });
    }

    public RotationPlace rotation() {
        return rotation;
    }

    public void setTransports(
            Function<InventoryTransactionPacket, Boolean> forward,
            Consumer<BedrockPacket> sendClient) {
        this.forward = Objects.requireNonNull(forward);
        this.sendClient = Objects.requireNonNull(sendClient);
    }


    private boolean airLiquidCheckAvailable() {
        return state.player().checkEnabled(AirLiquidPlace.class);
    }

    private boolean rotationCheckAvailable() {
        if (state.player().checkEnabled(RotationPlace.class)) return true;
        rotation.disable();
        expiries.clear();
        return false;
    }

    public PacketEvent begin(BedrockPacket packet) {
        current = new PacketEvent(packet);
        try {
            if (packet instanceof PlayerAuthInputPacket input) {
                if(state.player().checkEnabled(FarPlace.class))far.onInput(input, false);
                if(airLiquidCheckAvailable())air.onInput(input, false);
            }
            if(rotationCheckAvailable())rotation.onPacketReceive(current);
        } catch (MissingFact missing) {
            skipIncompletePrediction(packet, missing);
        }
        return current;
    }

    public boolean handleTransaction(InventoryTransactionPacket packet) {
        if (skippedPredictions.contains(packet)) return true;
        if (current != null && current.getPacket() == packet && current.isCancelled()) return false;
        try {
            boolean accepted = state.transactionValidator().handle(packet, this);
            if (!accepted && packet.getTransactionType() == InventoryTransactionType.ITEM_USE
                    && packet.getActionType() == 0) {
                restoreTransaction(packet);
            }
            return accepted;
        } catch (MissingFact missing) {
            skipIncompletePrediction(packet, missing);
            return true;
        }
    }

    public boolean finish(PacketEvent event, boolean accepted) {
        if (!accepted) event.setCancelled(true);
        if (event.getPacket() instanceof InventoryTransactionPacket transaction
                && !state.player().isExempted() && state.player().checkEnabled(FarPlace.class))
            event.setCancelled(far.onTransaction(transaction, event.isCancelled()));
        if(rotationCheckAvailable())rotation.finishPacket(event);
        views.clear();
        embedded.clear();
        skippedPredictions.clear();
        current = null;
        return !event.isCancelled();
    }

    private void skipIncompletePrediction(BedrockPacket packet, MissingFact missing) {
        if (current != null) rotation.skipPacket(current);
        if (packet instanceof InventoryTransactionPacket transaction) skippedPredictions.add(transaction);
        else if (packet instanceof PlayerAuthInputPacket input) {
            var transaction = embeddedTransaction(input);
            if (transaction != null) skippedPredictions.add(transaction);
        }
        var trace = state.player().worldDiagnostics();
        if (trace != null)
            trace.event(
                    "placement_prediction_skipped",
                    ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                            "reason", missing.getMessage()));
    }

    private void restoreTransaction(InventoryTransactionPacket packet) {
        var pos = packet.getBlockPosition();
        if (pos != null) {
            Cell target = cell(pos);
            int face = packet.getBlockFace();
            if (face >= 0 && face <= 5) {
                var known = state.backendBlocksAt(pos);
                var placed = known == null
                        ? adjacent(target, face)
                        : cell(ac.navro.proxy.util.block.BlockPlacementPhysics.target(
                                pos, face, world().networkBlockState(known.layerZero())));
                resyncPlacement(target, placed);
            } else {
                resyncPlacement(target, target);
            }
        }
        resyncSlot(packet.getHotbarSlot());
    }

    public void tick() {
        if (rotationCheckAvailable()) {
            int now = world().nativeServerTick();
            var due = expiries.stream().filter(e -> now - e.due >= 0).toList();
            expiries.removeAll(due);
            due.forEach(e -> e.task.run());
        }
        flushCorrections();
    }

    public void close() {
        expiries.clear();
        skippedPredictions.clear();
        views.clear();
        embedded.clear();
        pendingSlotCorrections.clear();
        pendingBlockCorrections.clear();
    }

    public void observeBackend(BedrockPacket packet) {
        if (packet instanceof StartGamePacket || packet instanceof ChangeDimensionPacket)
            level[0] = new Object();
        rotation.onPacketSend(new PacketEvent(packet));
        flushCorrections();
    }

    public int pendingSlotCorrectionCount() {
        return pendingSlotCorrections.size();
    }

    public long epoch() {
        return state.world().getBlockBreakEpoch();
    }

    public Object levelIdentity() {
        return level[0];
    }

    public boolean available() {
        return state.spawned()
                && !state.player().predictionFlag(EntityFlag.SLEEPING)
                && !state.player().inLoadingScreen
                && state.player().vehicleData == null
                && state.player().gameType() != GameType.SPECTATOR
                && !state.teleports().isTeleporting();
    }

    public long runtimeId() {
        return state.player().runtimeId();
    }

    public Vec3 acceptedMotion() {
        return state.player().velocity();
    }

    public GameType gameType() {
        return state.player().gameType();
    }

    public int minY() {
        return state.world().getMinY();
    }

    public int maxY() {
        return state.world().getHeightY();
    }

    public boolean chunkLoaded(int x, int z) {
        return state.world().isChunkLoaded(x, z);
    }

    public BlockView block(Cell position) {
        if (isInWorld(position.y())
                && !world().isSectionKnown(position.x(), position.y(), position.z())) {
            throw new MissingFact("unknown_selection_section");
        }
        var network =
                world().networkBlockState(
                                world().getRawBlockAt(position.x(), position.y(), position.z(), 0));
        if (network == null) throw new MissingFact("unknown_block_definition");
        if (network.legacyFullId() < 0) throw new MissingFact("unknown_native_block_behavior");

        var facts = NativeSelectionProperties.get(network.blockId(), network.blockData());
        Box outline;
        boolean sturdy;
        if (facts.known()) {
            outline = facts.outline(position.x(), position.y(), position.z());
            sturdy = facts.sturdy();
        } else if (NativeWallVineSelection.supported(network.blockId())) {
            NativeWallVineSelection.Selection selection;
            try {
                selection =
                        NativeWallVineSelection.selection(
                                network.blockId(),
                                network.blockData(),
                                position.x(),
                                position.y(),
                                position.z(),
                                side -> wallVineNeighbor(position, side));
            } catch (NativeConnectedSelection.UnknownProperty missing) {
                throw new MissingFact(missing.getMessage());
            }
            outline = selection.outline();
            sturdy = selection.sturdy();
        } else if (NativeConnectedSelection.supported(network.blockId())) {
            var selection = connectedSelection(network, position);
            outline = selection.outline();
            sturdy = selection.sturdy();
        } else if (NativeDoorSelection.supported(network.blockId())) {
            outline =
                    NativeDoorSelection.outline(
                            position.x(),
                            position.y(),
                            position.z(),
                            facts.damage(),
                            offset -> doorNeighbor(position, offset));
            sturdy = NativeDoorSelection.sturdy(network.blockId(), network.blockData());
        } else {
            throw new MissingFact("native_selection_requires_world_state");
        }

        boolean air =
                facts.known()
                        && (network.is("minecraft:air")
                                || network.is("minecraft:cave_air")
                                || network.is("minecraft:void_air")
                                || facts.id() == 0);
        return new BlockView(
                network,
                facts.id(),
                facts.damage(),
                air,
                NativePlacementProperties.canBeReplaced(network),
                facts.slab(),
                sturdy,
                outline);
    }

    private boolean isInWorld(int y) {
        return y >= minY() && y < maxY();
    }

    private NativeConnectedSelection.Selection connectedSelection(
            NetworkBlockState block, Cell position) {
        try {
            return NativeConnectedSelection.selection(
                    block.blockId(),
                    block.blockData(),
                    position.x(),
                    position.y(),
                    position.z(),
                    side -> connectionNeighbor(position, side));
        } catch (NativeConnectedSelection.UnknownProperty missing) {
            throw new MissingFact(missing.getMessage());
        }
    }

    private NativeConnectedSelection.Neighbor connectionNeighbor(Cell position, int side) {
        int x = position.x() + (side == 2 ? -1 : side == 3 ? 1 : 0);
        int z = position.z() + (side == 0 ? -1 : side == 1 ? 1 : 0);
        if (!world().isSectionKnown(x, position.y(), z)) {
            throw new MissingFact("unknown_connection_neighbor_section");
        }
        var neighbor = world().networkBlockState(world().getRawBlockAt(x, position.y(), z, 0));
        if (neighbor == null) throw new MissingFact("unknown_connection_neighbor_definition");
        return NativeConnectedSelection.neighbor(neighbor.blockId(), neighbor.blockData());
    }

    private NativeWallVineSelection.State wallVineNeighbor(Cell position, int side) {
        int x = position.x() + (side == 2 ? -1 : side == 3 ? 1 : 0);
        int y = position.y() + (side == 4 ? 1 : 0);
        int z = position.z() + (side == 0 ? -1 : side == 1 ? 1 : 0);
        if (isInWorld(y) && !world().isSectionKnown(x, y, z)) {
            throw new MissingFact(
                    side == 4 ? "unknown_vine_upper_section" : "unknown_wall_neighbor_section");
        }
        var neighbor = world().networkBlockState(world().getRawBlockAt(x, y, z, 0));
        if (neighbor == null) {
            throw new MissingFact(
                    side == 4
                            ? "unknown_vine_upper_definition"
                            : "unknown_wall_neighbor_definition");
        }
        return new NativeWallVineSelection.State(neighbor.blockId(), neighbor.blockData());
    }

    private NativeSelectionProperties.Facts doorNeighbor(Cell position, int offset) {
        int y = position.y() + offset;
        if (isInWorld(y) && !world().isSectionKnown(position.x(), y, position.z())) {
            throw new MissingFact("unknown_door_neighbor_section");
        }
        var neighbor =
                world().networkBlockState(world().getRawBlockAt(position.x(), y, position.z(), 0));
        if (neighbor == null || neighbor.blockId() < 0 || neighbor.blockId() >= 2048) {
            throw new MissingFact("unknown_door_neighbor_definition");
        }
        return NativeSelectionProperties.get(neighbor.blockId(), neighbor.blockData());
    }

    public UseItemData placementData(InventoryTransactionPacket packet) {
        if (views.containsKey(packet)) return views.get(packet);
        var item = packet.getItemInHand();
        ItemView itemView = null;
        if (item != null) {
            var decoded =
                    NativeItemDecoder.decode(
                            state.player().protocolVersion(),
                            item,
                            NativeItemDecoder.Format.TRANSACTION);
            var traits = NativePredictionItems.resolve(state.player().protocolVersion(), decoded);
            var props = NativeItemDecoder.placement(state.player().protocolVersion(), decoded);
            boolean placeable =
                    decoded.getCount() > 0 && !traits.positiveNull() && props.canBePlaced();
            boolean slab = NativeSelectionProperties.get(props.blockId(), 0).slab();
            itemView =
                    new ItemView(
                            traits.nativeId(),
                            decoded.getDamage(),
                            placeable,
                            props.hasMappedBlock(),
                            props.blockId(),
                            slab);
        }
        var view =
                new UseItemData(
                        packet.getBlockPosition() == null ? null : cell(packet.getBlockPosition()),
                        face(packet.getBlockFace()),
                        itemView,
                        packet.getActionType(),
                        packet.getClientInteractPrediction() == null
                                ? 0
                                : packet.getClientInteractPrediction().ordinal(),
                        packet.getTriggerType() == null ? 0 : packet.getTriggerType().ordinal(),
                        packet.getHotbarSlot(),
                        packet.getClickPosition() == null
                                ? null
                                : new Vec3(packet.getClickPosition()),
                        packet.getPlayerPosition() == null
                                ? null
                                : new Vec3(packet.getPlayerPosition()));
        views.put(packet, view);
        return view;
    }

    public InventoryTransactionPacket embeddedTransaction(PlayerAuthInputPacket input) {
        var data = input.getItemUseTransaction();
        if (data == null) return null;
        return embedded.computeIfAbsent(
                input,
                ignored -> {
                    var p = new InventoryTransactionPacket();
                    p.setTransactionType(InventoryTransactionType.ITEM_USE);
                    p.setActionType(data.getActionType());
                    p.setBlockPosition(data.getBlockPosition());
                    p.setBlockFace(data.getBlockFace());
                    p.setHotbarSlot(data.getHotbarSlot());
                    p.setItemInHand(data.getItemInHand());
                    p.setPlayerPosition(data.getPlayerPosition());
                    p.setClickPosition(data.getClickPosition());
                    p.setBlockDefinition(data.getBlockDefinition());
                    p.setClientInteractPrediction(data.getClientInteractPrediction());
                    p.setTriggerType(data.getTriggerType());
                    p.getActions().addAll(data.getActions());
                    return p;
                });
    }

    public void scheduleExpiry(Runnable task, int delay) {
        if (state.player().checkEnabled(RotationPlace.class))
            expiries.add(new Expiry(world().nativeServerTick() + delay, task));
    }

    public void applyPlacement(InventoryTransactionPacket packet) {
        if (!forward.apply(packet))
            throw new IllegalStateException("Deferred placement was not sent");
        state.onClientPacketForwarded(packet);
    }

    public void resyncPlacement(Cell target, Cell placed) {
        pendingBlockCorrections.put(target, placed);
        flushCorrections();
    }

    public void resyncSlot(int slot) {
        if (slot >= 0 && slot <= 8) {
            pendingSlotCorrections.add(slot);
            flushCorrections();
        }
    }

    public void resyncFarPlacement(InventoryTransactionPacket packet) {
        var pos = packet.getBlockPosition();
        restoreCorrectBlock(pos);
        if (packet.getBlockFace() >= 0 && packet.getBlockFace() <= 5) {
            var clicked =
                    world().networkBlockState(
                                    world().getRawBlockAt(pos.getX(), pos.getY(), pos.getZ(), 0));
            var placed =
                    ac.navro.proxy.util.block.BlockPlacementPhysics.target(
                            pos, packet.getBlockFace(), clicked);
            if (!placed.equals(pos)) restoreCorrectBlock(placed);
        }
        resyncSlot(state.inventory().heldItemSlot);
    }

    private void flushCorrections() {
        for (var entry : List.copyOf(pendingBlockCorrections.entrySet())) {
            boolean ready =
                    state.backendBlocksAt(vector(entry.getKey())) != null
                            && state.backendBlocksAt(vector(entry.getValue())) != null;
            if (!ready) continue;
            for (Cell p :
                    entry.getKey().equals(entry.getValue())
                            ? List.of(entry.getKey())
                            : List.of(entry.getKey(), entry.getValue())) {
                var blocks = state.backendBlocksAt(vector(p));
                state.world().predictBucketNetworkBlock(p.x(), p.y(), p.z(), 0, blocks.layerZero());
                state.world().predictBucketNetworkBlock(p.x(), p.y(), p.z(), 1, blocks.layerOne());
                state.restoreCorrectBlock(vector(p));
            }
            state.player().tickSinceBlockResync = 5;
            pendingBlockCorrections.remove(entry.getKey());
        }
        for (int slot : List.copyOf(pendingSlotCorrections)) {
            var authoritative = state.backendInventories().slot(0, slot);
            var packet = state.backendInventories().fullPacket(0);
            if (authoritative == null || packet == null) continue;
            state.inventory().inventoryContainer.set(slot, authoritative);
            state.onProxyInventoryCorrection(packet);
            sendClient.accept(packet);
            pendingSlotCorrections.remove(slot);
        }
    }

    public boolean debugEnabled() {
        return state.player().worldDiagnostics() != null;
    }

    public void debug(String message) {
        var trace = state.player().worldDiagnostics();
        if (trace != null) trace.event("rotation_place_debug", Map.of("message", message));
    }

    public ac.navro.proxy.compensated.world.base.CompensatedWorld world() {
        return state.world();
    }

    public Integer resolvedFace(InventoryTransactionPacket packet) {
        return packet.getBlockFace() >= 0 && packet.getBlockFace() <= 5
                ? packet.getBlockFace()
                : null;
    }

    public boolean sneaking() {
        return state.player()
                .inputData()
                .contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.SNEAKING);
    }

    public ItemTransactionValidator.PlacementChecks checks() {
        return checks;
    }

    private final ItemTransactionValidator.PlacementChecks checks =
            new ItemTransactionValidator.PlacementChecks() {
                public ItemTransactionValidator.AirLiquidPlaceCheck airLiquidPlace() {
                    if(!airLiquidCheckAvailable())return null;
                    return (packet, held, clicked) ->
                            air.reject(
                                    packet,
                                    held,
                                    NativeBlockProperties.forState(clicked).blockId());
                }

                public ItemTransactionValidator.PlacementObserver rotationPlace() {
                    if(!rotationCheckAvailable())return null;
                    return (data, target, id) ->
                            rotation.predictedPlacement(placementData(data), cell(target), id);
                }
            };

    public ItemTransactionValidator.ActivationObserver trapdoorSampler() {
        return (position, before, after, stage, trace) -> {
            var log = state.player().worldDiagnostics();
            return log == null
                    ? 0
                    : log.event(
                            "localToggle",
                            ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                                    "position",
                                    position.toString(),
                                    "before",
                                    before,
                                    "after",
                                    after,
                                    "phase",
                                    stage,
                                    "trace",
                                    trace));
        };
    }

    public boolean nativeCanBePlaced(ItemData item) {
        return placementProperties(item).canBePlaced();
    }

    public NativeItemPlacementProperties.Properties placementProperties(ItemData item) {
        return NativeItemDecoder.placement(state.player().protocolVersion(), item);
    }

    public void restoreCorrectBlock(Vector3i pos) {
        state.restoreCorrectBlock(pos);
    }

    public boolean validate(ItemData predicted, ItemData claim) {
        return state.authInputPackets().itemUse().validate(predicted, claim);
    }

    public int nativeItemId(ItemData item) {
        return state.authInputPackets().itemUse().nativeItemId(item);
    }

    public int nativeItemDamage(ItemData item) {
        return state.authInputPackets().itemUse().nativeItem(item).getDamage();
    }

    public int nativeBlockId(ItemData item) {
        return placementProperties(item).blockId();
    }

    public boolean nativeIsNull(ItemData item) {
        return state.authInputPackets().itemUse().isNull(item);
    }

    public ItemData createNativeBucket(int damage) {
        return NativeItemDecoder.constructNumeric(state.player().protocolVersion(), 325, damage, 1);
    }

    public boolean hasTag(ItemData item, String tag) {
        return ItemUtil.hasTag(state.authInputPackets().itemUse().nativeItem(item), tag);
    }

    public Vec3 position() {
        return state.player().position();
    }

    public boolean handleClickBlock(InventoryTransactionPacket data) {
        return state.transactionValidator().handleClickBlock(data, this);
    }

    public boolean handleClickAir(InventoryTransactionPacket data) {
        return state.authInputPackets().itemUse().handleClickAir(data);
    }

    public boolean handleRelease(InventoryTransactionPacket packet) {
        return state.authInputPackets().itemUse().handleRelease(packet);
    }

    public ItemData decodedClaim(ItemData item) {
        return NativeItemDecoder.decode(
                state.player().protocolVersion(), item, NativeItemDecoder.Format.TRANSACTION);
    }

    private static Cell cell(Vector3i p) {
        return new Cell(p.getX(), p.getY(), p.getZ());
    }

    private static Vector3i vector(Cell p) {
        return Vector3i.from(p.x(), p.y(), p.z());
    }

    private static Direction face(int f) {
        return f >= 0 && f < 6 ? Direction.values()[f] : null;
    }

    private static Cell adjacent(Cell p, int f) {
        var face = face(f);
        return face == null
                ? p
                : new Cell(
                        p.x() + face.getXOffset(),
                        p.y() + face.getYOffset(),
                        p.z() + face.getZOffset());
    }
}
