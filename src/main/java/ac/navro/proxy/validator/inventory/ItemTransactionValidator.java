package ac.navro.proxy.validator.inventory;

import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.data.inventory.ItemCache;
import ac.navro.proxy.data.ItemUseTracker;
import ac.navro.proxy.data.inventory.NativeItemPlacementProperties;
import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.proxy.data.block.NativeBlockProperties;
import ac.navro.proxy.data.block.NativeInteractionProperties;
import ac.navro.proxy.data.block.NativeUseItemOnProperties;
import ac.navro.proxy.data.block.BlockStateEncoding;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.util.block.BlockPlacementPhysics;
import ac.navro.proxy.util.block.BlockUtil;
import org.cloudburstmc.math.vector.Vector3i;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.util.ItemUtil;
import ac.navro.proxy.util.NbtNumber;
import ac.navro.proxy.util.NbtString;
import ac.navro.anticheat.util.math.Vec3;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;


public class ItemTransactionValidator {
    private final WirePlayerState player;
    private final CompensatedInventory inventory;

    public ItemTransactionValidator(WirePlayerState player, CompensatedInventory inventory) {
        this.player = player;
        this.inventory = inventory;
    }






    public interface TransactionContext {
        Vec3 position();
        int nativeItemId(ItemData item);
        boolean handleClickBlock(InventoryTransactionPacket data);
        boolean handleClickAir(InventoryTransactionPacket data);
        boolean handleRelease(InventoryTransactionPacket packet);


        default InventoryTransactionPacket useItemData(InventoryTransactionPacket packet) { return packet; }
        default boolean validate(ItemData predicted, ItemData claimed) {
            return ItemTransactionValidator.validate(predicted, claimed);
        }

        default void validatorDecision(InventoryTransactionPacket data, String reason) { }
    }

    @FunctionalInterface
    public interface AirLiquidPlaceCheck {
        boolean reject(InventoryTransactionPacket data, ItemData held, NetworkBlockState clicked);
    }


    public interface PlacementChecks {
        AirLiquidPlaceCheck airLiquidPlace();
        PlacementObserver rotationPlace();
    }






    public interface ClickBlockContext extends UseItemOnContext, BucketContext {
        CompensatedWorld world();
        Integer resolvedFace(InventoryTransactionPacket data);
        boolean sneaking();
        PlacementChecks checks();
        ActivationObserver trapdoorSampler();
        boolean nativeCanBePlaced(ItemData item);
        NativeItemPlacementProperties.Properties placementProperties(ItemData item);
        void restoreCorrectBlock(Vector3i position);
        boolean validate(ItemData predicted, ItemData claimed);
        default int clientInteractPrediction(InventoryTransactionPacket data) {
            return data.getClientInteractPrediction() == null ? 0 : data.getClientInteractPrediction().ordinal();
        }
    }


    public interface ClickAirContext<I> {
        boolean validate(ItemData predicted, ItemData claimed);
        boolean nativeFirework(ItemData held);
        boolean serverGliding();
        boolean serverChestIsElytra();
        boolean compensatedGliding();
        boolean nativeIsNull(ItemData item);
        int nativeItemId(ItemData item);
        int nativeFireworkFlight(ItemData item);
        boolean usingItemFlag();
        ItemUseTracker<I> tracker();
        I nativeItem(ItemData item);
    }


    public <I> boolean handleClickAir(InventoryTransactionPacket data, ClickAirContext<I> context) {
        final ItemData held = inventory.inventoryContainer.getHeldItemData();
        if (data.getItemInHand() == null || !context.validate(held, data.getItemInHand())) return true;
        if (context.nativeFirework(held)) {
            final boolean serverGliding = context.serverGliding();
            final boolean serverElytra = context.serverChestIsElytra();
            final boolean compensatedGliding = context.compensatedGliding();
            final ItemData chest = inventory.armorContainer.get(1).getData();
            final boolean compensatedElytra = chest != null && !context.nativeIsNull(chest)
                    && context.nativeItemId(chest) == 444;
            final boolean beginBoost = compensatedGliding && compensatedElytra;
            final boolean expectsNativeRocket = serverGliding && serverElytra;
            if (beginBoost) player.beginNukkitGlideBoost(context.nativeFireworkFlight(held), expectsNativeRocket);
            return true;
        }
        if (context.usingItemFlag()) return true;
        final ItemUseTracker<I> resumeTracker = context.tracker();
        final I nativeHeld = context.nativeItem(held);
        if (resumeTracker.resumeLatchedUse(nativeHeld)) return true;
        context.tracker().use(nativeHeld, context.nativeItemId(held), false);
        return true;
    }

    public interface ReleaseContext<I> {

        InventoryTransactionPacket releaseItemData(InventoryTransactionPacket packet);
        ItemUseTracker<I> tracker();
        I nativeItem(ItemData item);
        I serverHeldItem();
        int riptideLevel(I item);
        void setDirtyRiptide(int sinceTridentUse, I item);
    }


    public <I> boolean handleRelease(InventoryTransactionPacket packet, ReleaseContext<I> context) {
        final InventoryTransactionPacket data = context.releaseItemData(packet);
        if (data == null) return true;
        if (data.getActionType() == 0) {
            final ItemUseTracker<I> tracker = context.tracker();
            final I held = context.nativeItem(inventory.inventoryContainer.getHeldItemData());
            final I serverHeld = context.serverHeldItem();
            for (final I candidate : java.util.Arrays.asList(serverHeld, tracker.getUsedItem(), held)) {
                if (context.riptideLevel(candidate) > 0) {
                    context.setDirtyRiptide(tracker.getPlayer().sinceTridentUse(), candidate);
                    break;
                }
            }
            tracker.release();
            tracker.setDirtyUsing(ItemUseTracker.DirtyUsing.NONE);
        }
        return true;
    }


    public boolean handleClickBlock(InventoryTransactionPacket data, ClickBlockContext context) {
        if (data.getBlockPosition() == null) return false;
        final ItemData held = inventory.inventoryContainer.getHeldItemData();
        if (data.getItemInHand() == null || !context.validate(held, data.getItemInHand())) return true;
        final Vector3i position = data.getBlockPosition();
        if (context.clientInteractPrediction(data) == 0) return true;
        final Integer face = context.resolvedFace(data);
        if (face == null) return false;
        if (face < 0 || face > 5) return false;

        final CompensatedWorld world = context.world();
        final NetworkBlockState clickedState = stateAt(world, position, 0);
        if (NativeBlockProperties.forState(clickedState).itemFrame()) return true;
        final Vector3i newBlockPos = BlockUtil.getBlockPosition(position, face);
        final boolean heldItemExists = held != null && !context.nativeIsNull(held);
        final boolean doingSecondaryAction = heldItemExists && context.sneaking();
        if (!doingSecondaryAction) {
            if (handleUseItemOn(world, position, clickedState, held, face, context)) return true;
            if (NativeUseItemOnProperties.forState(clickedState).bell() && data.getClickPosition() != null
                    && isProperHit(clickedState, face, data.getClickPosition().getY())) return true;
            if (canUseWithoutItem(clickedState)) {

                predictActivatedBlock(world, position, clickedState,
                        NativeInteractionProperties.forState(clickedState).trapdoor() ? context.trapdoorSampler() : null);
                return true;
            }
        }
        final PlacementChecks checks = context.checks();
        final AirLiquidPlaceCheck airLiquidPlace = checks == null ? null : checks.airLiquidPlace();
        if (airLiquidPlace != null && airLiquidPlace.reject(data, held, clickedState)) return false;
        if (isScaffoldingPlacement(world, held == null ? null : context.nativeBlockId(held), clickedState, newBlockPos)) return true;
        if (clickedState.is("minecraft:air") || clickedState.is("minecraft:cave_air")
                || clickedState.is("minecraft:void_air") || NativeBlockProperties.forState(clickedState).blockId() == 0) {
            context.restoreCorrectBlock(newBlockPos);
            context.restoreCorrectBlock(position);
            player.tickSinceBlockResync = 5;
            return false;
        }
        if (handleBucketUse(world, held, position, newBlockPos, clickedState, context)) return true;
        if (context.nativeCanBePlaced(held) && context.nativeBlockId(held) != 0) {
            handleBlockPlacementWithLookup(world, context.placementProperties(held), position, face, clickedState, data, () -> {
                final PlacementChecks current = context.checks();
                return current == null ? null : current.rotationPlace();
            });
        }
        return true;
    }

    public boolean handle(InventoryTransactionPacket packet, TransactionContext context) {

        if (packet.getTransactionType() == null) return handleNormal(packet, context);
        return switch (packet.getTransactionType()) {
            case NORMAL -> handleNormal(packet, context);
            case ITEM_RELEASE -> context.handleRelease(packet);
            case ITEM_USE -> handleUse(packet, context);
            default -> true;
        };
    }

    public boolean handleUse(InventoryTransactionPacket packet, TransactionContext context) {
        final InventoryTransactionPacket data = context.useItemData(packet);
        if (data == null) return true;
        final int slot = data.getHotbarSlot();
        if (slot < 0 || slot > 8) return false;

        final ItemData held = inventory.inventoryContainer.getHeldItemData();
        final var actions = packet.getActions();
        final boolean noActions = actions == null || actions.isEmpty();
        if (!noActions) {
            for (final InventoryActionData action : actions) {
                if (action == null || action.getSlot() < 0 || action.getSlot() > 8) return false;
                final ItemData predicted = inventory.inventoryContainer.getItemFromSlot(action.getSlot()).getData();
                if (!context.validate(predicted, action.getFromItem())) return false;
            }
        }

        final boolean emptyHandInteraction = isEmpty(held, context) && isEmpty(data.getItemInHand(), context);
        if (noActions && !emptyHandInteraction && !context.validate(held, data.getItemInHand())) {
            context.validatorDecision(data, "REJECT_HELD_ITEM_MISMATCH");
            return false;
        }

        final Vector3i position = data.getBlockPosition();
        if (position == null || !Float.isFinite(position.getX()) || !Float.isFinite(position.getY())
                || !Float.isFinite(position.getZ()) || tooFar(position, context)) return false;
        return switch (data.getActionType()) {
            case 0 -> context.handleClickBlock(data);
            case 1 -> {
                final boolean accepted = context.handleClickAir(data);
                if (accepted) handleLegacyArmorEquip(packet, data);
                yield accepted;
            }
            default -> true;
        };
    }

    private static boolean isEmpty(ItemData item, TransactionContext context) {

        return item == null || item.getCount() <= 0 || context.nativeItemId(item) == 0;
    }

    private static boolean tooFar(Vector3i position, TransactionContext context) {
        final Vec3 blockPos = new Vec3(position.getX(), position.getY(), position.getZ());
        return context.position().squaredDistanceTo(blockPos) > 12F * 12F
                && position.getX() + position.getY() + position.getZ() != 0;
    }

    public void handleLegacyArmorEquip(InventoryTransactionPacket packet, InventoryTransactionPacket data) {
        final var actions = packet.getActions();
        if (actions == null || actions.size() != 1) return;
        final var legacySlots = packet.getLegacySlots();
        if (legacySlots == null || legacySlots.isEmpty()) return;
        if (data.getHotbarSlot() != inventory.heldItemSlot) return;
        final var slotData = legacySlots.get(0);
        if (slotData == null || slotData.getSlots() == null || slotData.getSlots().length == 0) return;
        final int actualSlot = Byte.toUnsignedInt(slotData.getSlots()[0]);
        if (actualSlot < 0 || actualSlot >= inventory.armorContainer.getContainerSize()) return;
        if (slotData.getContainerId() == 6) {
            final ItemCache oldHotbar = inventory.inventoryContainer.getHeldItemCache();
            inventory.inventoryContainer.set(data.getHotbarSlot(), inventory.armorContainer.get(actualSlot));
            inventory.armorContainer.set(actualSlot, oldHotbar);
        }
    }

    public boolean handleNormal(InventoryTransactionPacket packet) {
        return handleNormal(packet, null);
    }

    private boolean handleNormal(InventoryTransactionPacket packet, TransactionContext context) {
        var actions = packet.getActions();
        if (actions == null || actions.size() != 2) return false;
        InventoryActionData world = null;
        InventoryActionData container = null;
        for (InventoryActionData action : actions) {
            if (action == null) return false;
            if (action.getSource().getType() == InventorySource.Type.WORLD_INTERACTION) {
                if (world != null) return false;
                world = action;
            } else if (action.getSource().getType() == InventorySource.Type.CONTAINER) {
                if (container != null) return false;
                container = action;
            } else return false;
        }
        if (world == null || container == null) return false;
        if (world.getSlot() != 0) return false;
        if (container.getSource().getContainerId() != inventory.inventoryContainer.getId()) return false;
        return handleHotbarDrop(world, container, context);
    }

    private boolean handleHotbarDrop(InventoryActionData world, InventoryActionData containerAction, TransactionContext context) {
        int slot = containerAction.getSlot();
        if (slot < 0 || slot > 8) return false;
        ItemData slotData = inventory.inventoryContainer.getItemFromSlot(slot).getData();
        ItemData claimedDrop = world.getToItem();
        final boolean emptyDrop = context == null
                ? inventory.nativeIsNull(claimedDrop, ac.navro.proxy.data.inventory.NativeItemDecoder.Format.TRANSACTION) : isEmpty(claimedDrop, context);
        if (emptyDrop) return false;
        int dropCount = claimedDrop.getCount();
        if (dropCount < 1 || dropCount > slotData.getCount()
                || !(context == null ? inventory.sameDefinition(slotData, claimedDrop,
                    ac.navro.proxy.data.inventory.NativeItemDecoder.Format.TRANSACTION) : context.validate(slotData, claimedDrop))) return false;
        if (dropCount == slotData.getCount()) {
            inventory.inventoryContainer.set(slot, ItemCache.AIR);
            return true;
        }
        ItemData remaining = ItemUtil.copy(slotData).toBuilder()
                .count(Math.max(0, slotData.getCount() - dropCount)).build();
        inventory.inventoryContainer.set(slot, remaining);
        return true;
    }

    @FunctionalInterface
    public interface PlacementObserver {
        void predictedPlacement(InventoryTransactionPacket data, Vector3i target, int nativeBlockId);
    }


    public interface BucketContext {
        int nativeItemId(ItemData item);
        int nativeItemDamage(ItemData item);
        ItemData createNativeBucket(int damage);
    }


    public interface UseItemOnContext {
        int nativeItemId(ItemData item);
        int nativeItemDamage(ItemData item);
        int nativeBlockId(ItemData item);
        boolean nativeIsNull(ItemData item);
        default boolean hasTag(ItemData item, String tag) { return ItemUtil.hasTag(item, tag); }
        default boolean isNoteblockTopInstrument(ItemData item) {
            return hasTag(item, "minecraft:noteblock_top_instruments");
        }
    }


    public boolean handleUseItemOn(CompensatedWorld world, Vector3i position, NetworkBlockState block,
                                   ItemData held, Integer face, UseItemOnContext context) {
        if (block == null || held == null) return false;
        final int heldId = context.nativeItemId(held);
        final int blockId = NativeBlockProperties.forState(block).blockId();
        final var properties = NativeUseItemOnProperties.forState(block);
        if (properties.cauldron() && (isBucket(held, context, 8) || isBucket(held, context, 10)
                || isBucket(held, context, 11))) return true;
        if (blockId == 92 && (context.hasTag(held, "minecraft:candles") || properties.undamagedCake())) return true;
        if (properties.candle() && context.nativeIsNull(held) && properties.litCandle()) return true;
        if (properties.lectern() && context.nativeIsNull(held) && !lecternHasBook(world, position)) return true;
        if (properties.noteblock() && face != null && face == 1 && context.isNoteblockTopInstrument(held)) return true;
        if (blockId == 86 || blockId == 73) return true;
        if (properties.respawnAnchor() && context.nativeBlockId(held) == 89 && properties.anchorChargeBelowFour()) return true;
        if (properties.sign()) return true;
        if (blockId == 462 && properties.berryDamageNotThree() && context.nativeItemId(held) == 351
                && context.nativeItemDamage(held) == 15) return true;
        if (properties.tnt() && (heldId == 259 || heldId == 259 || heldId == 385)) return true;
        return blockId == 569 && (context.nativeIsNull(held) || !isVaultActive(world, position, block));
    }

    private static boolean isBucket(ItemData item, UseItemOnContext context, int damage) {
        return context.nativeItemId(item) == 325 && context.nativeItemDamage(item) == damage;
    }

    public boolean lecternHasBook(CompensatedWorld world, Vector3i position) {
        final var tag = world.getBlockEntityTag(position.getX(), position.getY(), position.getZ());
        return tag != null && (NbtNumber.getByte(tag, "hasBook") != 0
                || tag.get("book") instanceof org.cloudburstmc.nbt.NbtMap);
    }

    public boolean isVaultActive(CompensatedWorld world, Vector3i position, NetworkBlockState block) {
        final int nativeProbe = block == null ? 0 : NativeUseItemOnProperties.forState(block).vaultState();
        if (nativeProbe != 0) return nativeProbe == 1;
        final var tag = world.getBlockEntityTag(position.getX(), position.getY(), position.getZ());
        if (tag != null) {
            final String state = NbtString.getString(tag, "vault_state");
            if (!state.isEmpty()) return "active".equalsIgnoreCase(state);
            final String fallback = NbtString.getString(tag, "state");
            if (!fallback.isEmpty()) return "active".equalsIgnoreCase(fallback);
        }
        return false;
    }


    public static boolean isProperHit(NetworkBlockState bell, Integer face, float clickY) {
        final int axis = switch (face) {
            case 0, 1 -> 1;
            case 2, 3 -> 2;
            case 4, 5 -> 0;
            default -> throw new IllegalArgumentException("Expected resolved native BlockFace index");
        };
        if (axis == 1 || clickY > 0.8124F) return false;
        final var properties = NativeUseItemOnProperties.forState(bell);
        if (!properties.bell()) throw new IllegalArgumentException("Expected native BlockBell");
        return switch (properties.attachment()) {
            case 0 -> properties.bellAxis() == axis;
            case 2, 3 -> properties.bellAxis() != axis;
            case 1 -> true;
            default -> false;
        };
    }


    public boolean handleBucketUse(CompensatedWorld world, ItemData held, Vector3i clicked, Vector3i target,
                                   NetworkBlockState clickedBlock, BucketContext context) {
        if (context.nativeItemId(held) != 325) return false;
        final int bucketDamage = context.nativeItemDamage(held);
        if (bucketDamage == 8) {
            world.predictBucketBlock(target, 0, BlockStateEncoding.fullId(8, 0));
            setHeldBucket(context, 0);
            return true;
        } else if (bucketDamage == 10) {
            world.predictBucketBlock(target, 0, BlockStateEncoding.fullId(10, 0));
            setHeldBucket(context, 0);
            return true;
        } else if (bucketDamage == 11) {
            world.predictBucketBlock(target, 0, BlockStateEncoding.fullId(561, 0));
            setHeldBucket(context, 0);
            return true;
        } else if (bucketDamage != 0) return false;

        int layer = 0;
        int resultBucket = -1;
        if (NativeInteractionProperties.forState(clickedBlock).water()) {
            resultBucket = 8;
        } else if (NativeInteractionProperties.forState(stateAt(world, clicked, 1)).water()) {
            layer = 1;
            resultBucket = 8;
        } else if (NativeInteractionProperties.forState(clickedBlock).lava()) {
            resultBucket = 10;
        } else if (NativeBlockProperties.forState(clickedBlock).blockId() == 561) {
            resultBucket = 11;
        }
        if (resultBucket == -1) return true;
        world.predictBucketBlock(clicked, layer, 0);
        setHeldBucket(context, resultBucket);
        return true;
    }

    private void setHeldBucket(BucketContext context, int damage) {
        if (player.gameType() == GameType.CREATIVE) return;
        final int slot = inventory.heldItemSlot;
        inventory.inventoryContainer.set(slot, context.createNativeBucket(damage));
    }


    public boolean isScaffoldingPlacement(CompensatedWorld world, Integer heldBlockId,
                                          NetworkBlockState clickedBlock, Vector3i target) {
        if (heldBlockId == null || heldBlockId != 420) return false;
        if (clickedBlock != null && NativeBlockProperties.forState(clickedBlock).blockId() == 420) return true;
        if (target == null) return false;
        return NativeBlockProperties.forState(stateAt(world, target, 0)).blockId() == 420;
    }

    @FunctionalInterface
    public interface ActivationObserver {
        long localToggle(Vector3i position, int beforeFullId, int afterFullId, String phase, long updateId);
    }


    public static boolean canUseWithoutItem(NetworkBlockState block) {
        return block != null && NativeInteractionProperties.forState(block).canUseWithoutItem();
    }


    public void predictActivatedBlock(CompensatedWorld world, Vector3i position, NetworkBlockState block,
                                      ActivationObserver trapdoorSampler) {
        if (position == null || block == null) return;
        final var properties = NativeInteractionProperties.forState(block);
        final int nativeId = NativeBlockProperties.forState(block).blockId();

        if (properties.trapdoor()) {
            final int before = BlockStateEncoding.fullId(nativeId, block.blockData());
            final int after = BlockStateEncoding.fullId(nativeId, block.blockData() ^ 8);
            final long trace = trapdoorSampler.localToggle(position, before, after, "beforePrediction", 0);
            world.updateLegacyBlock(position, 0, BlockStateEncoding.fullId(nativeId, block.blockData() ^ 8));
            trapdoorSampler.localToggle(position, before, after, "afterPrediction", trace);
            return;
        }
        if (properties.door()) {
            predictDoorToggle(world, position, block);
            return;
        }
        if (properties.fenceGate()) {
            world.updateLegacyBlock(position, 0, BlockStateEncoding.fullId(nativeId, block.blockData() ^ 4));
        }
    }

    private void predictDoorToggle(CompensatedWorld world, Vector3i position, NetworkBlockState door) {
        final boolean top = (door.blockData() & 8) != 0;
        final Vector3i lowerPos = top ? Vector3i.from(position.getX(), position.getY() - 1, position.getZ()) : position;
        final NetworkBlockState lower = stateAt(world, lowerPos, 0);
        final var lowerProperties = NativeInteractionProperties.forState(lower);
        final int lowerId = NativeBlockProperties.forState(lower).blockId();
        if (!lowerProperties.door() || lowerId != NativeBlockProperties.forState(door).blockId()) return;
        world.updateLegacyBlock(lowerPos, 0, BlockStateEncoding.fullId(lowerId, lower.blockData() ^ 4));
    }

    private static NetworkBlockState stateAt(CompensatedWorld world, Vector3i position, int layer) {
        return java.util.Objects.requireNonNull(world.networkBlockState(world.getRawBlockAt(
                position.getX(), position.getY(), position.getZ(), layer)), "Block palette is not initialized");
    }


    public void handleBlockPlacement(CompensatedWorld world, ItemData held,
                                     Vector3i clicked, int face, NetworkBlockState clickedBlock,
                                     InventoryTransactionPacket data, PlacementObserver rotationPlace) {
        handleBlockPlacement(world, ac.navro.proxy.data.inventory.NativeItemDecoder.placement(player.protocolVersion(), held),
                clicked, face, clickedBlock, data, rotationPlace);
    }

    public void handleBlockPlacement(CompensatedWorld world, NativeItemPlacementProperties.Properties held,
                                     Vector3i clicked, int face, NetworkBlockState clickedBlock,
                                     InventoryTransactionPacket data, PlacementObserver rotationPlace) {
        handleBlockPlacementWithLookup(world, held, clicked, face, clickedBlock, data, () -> rotationPlace);
    }

    private void handleBlockPlacementWithLookup(CompensatedWorld world, NativeItemPlacementProperties.Properties held,
                                      Vector3i clicked, int face, NetworkBlockState clickedBlock,
                                      InventoryTransactionPacket data, java.util.function.Supplier<PlacementObserver> lookup) {
        if (held.hasMappedBlock() && held.blockId() != 0) {
            BlockPlacementPhysics.predict(world, clicked, face, clickedBlock, held.defaultLegacyFullId());
            final PlacementObserver rotationPlace = lookup.get();
            if (rotationPlace != null)
                rotationPlace.predictedPlacement(data, BlockPlacementPhysics.target(clicked, face, clickedBlock),
                        held.defaultBlockId());
        }

        if (this.player.gameType() != GameType.CREATIVE) {
            final ItemCache heldCache = inventory.inventoryContainer.getHeldItemCache();
            heldCache.count(heldCache.count() - 1);
            if (heldCache.count() <= 0) {
                inventory.inventoryContainer.set(inventory.heldItemSlot, ItemCache.AIR);
            }
        }
    }

    public void handle(ItemStackRequestPacket packet) {
        if (inventory.openContainer != null) player.doingInventoryAction = true;
    }

    public static boolean validate(ItemData predicted, ItemData claimed) {
        return ItemUtil.sameDefinition(predicted, claimed);
    }
}
