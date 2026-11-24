package ac.navro.proxy.data;

import java.util.Locale;
import java.util.Set;

/** Original request/metadata/prediction state machine over explicit native item identities. */
public class ItemUseTracker<I> {
    public static final int USING_ITEM = -1;
    public static final int ACTION_FLAG = 4;

    /** Native item/model boundaries. airItem has stable identity; cloneFlags returns a mutable copy. */
    public interface Context<I> {
        I airItem();
        String identifier(I item);
        boolean sameDefinition(I predicted, I claimed);
        boolean isNull(I item);
        int nativeItemId(I item);
        boolean canRelease(I item);
        int useDuration(I item);
        boolean hasUseDurationComponent(I item);
        Object foodByRelative(I item);
        I cloneItem(I item);
        long playerTick();
        boolean serverUsingItem();
        boolean hasFlag(int flag);
        void setFlag(int flag, boolean value);
        Set<Integer> cloneFlags();
        int sinceTridentUse();
        void sinceTridentUse(int value);
        void stage(String name, Object... details);
        void event(String name, Object... details);
        default boolean identifierEquals(I item, String expected) {
            final String identifier = identifier(item);
            return identifier != null && identifier.equalsIgnoreCase(expected);
        }
    }
    private static final Set<String> SPEARS = Set.of(
            "minecraft:wooden_spear", "minecraft:stone_spear", "minecraft:iron_spear",
            "minecraft:golden_spear", "minecraft:diamond_spear", "minecraft:netherite_spear"
    );

    private final Context<I> context;
    private final I airItem;

    private I usedItem;
    private int javaItemId = -1;
    private DirtyUsing dirtyUsing = DirtyUsing.NONE;
    private int useDuration;
    private boolean useButtonLatched;
    private boolean buttonReleasePending;

    // Server use state and client-visible slowdown state are separate domains.
    // Nukkit can queue ACTION=false metadata from an older release and deliver it
    // after a new use request has already started. Track that exact ordering in
    // Navro player/AuthInput ticks (not Nukkit server ticks), so prediction
    // follows the movement frame in which the Bedrock client observed it.
    private int pendingMetadataFalsePackets;
    private long lastMetadataTrueSentPlayerTick = Long.MIN_VALUE;
    private long lastMetadataFalseAckPlayerTick = Long.MIN_VALUE;
    // Ordered network confirmation is live state, independent of local prediction
    // flags and of the flags restored while replaying an older movement input.
    private Boolean lastAcknowledgedMetadataUsing;
    private boolean awaitingMetadataStartConfirmation;
    private boolean predictionUsingItem;
    private boolean predictionAction;
    private long predictionInputTick = Long.MIN_VALUE;
    // Belongs to the movement input, not the mutable item/request state. Both
    // committed movement and velocity trials must use this same frozen value.
    private float predictionItemSlowdownFactor = 1F;

    public ItemUseTracker(final Context<I> context) {
        this.context = context;
        this.airItem = context.airItem();
        this.usedItem = this.airItem;
    }

    public enum DirtyUsing {
        METADATA, INVENTORY_TRANSACTION, NONE
    }

    public boolean isUsingSpear() {
        final String identifier = context.identifier(this.usedItem);
        return identifier != null && SPEARS.contains(identifier.toLowerCase(Locale.ROOT));
    }

    public void preTick() {
        if (!context.hasFlag(USING_ITEM)) {
            return;
        }

        final Object food = context.foodByRelative(this.usedItem);
        if (food != null) {

        }

        if (this.javaItemId == 455) {
            context.sinceTridentUse(context.sinceTridentUse() + 1);
        }
    }

    public void postTick() {
        if (!context.hasFlag(USING_ITEM)) {
            // Geyser keeps a use request pending after sending Java's UseItem packet
            // and waits for either START_USING_ITEM or server metadata to confirm it.
            // Do not discard the request merely because confirmation did not arrive
            // in the same Bedrock auth-input tick.
            if (this.dirtyUsing == DirtyUsing.INVENTORY_TRANSACTION
                    || this.awaitingMetadataStartConfirmation) {
                return;
            }
            if (this.usedItem != this.airItem || this.javaItemId != -1) {

                this.releaseState(false);
            }
            return;
        }

        if (this.usedItem == this.airItem || this.javaItemId == -1) {

            this.releaseState(false);
            return;
        }

        // Match Geyser's BedrockMobEquipmentTranslator: changing the carried slot
        // does not locally clear USING_ITEM. The authoritative server metadata or
        // an explicit RELEASE transaction ends the state.
    }

    public void onMetadataUseStateChanged(final boolean oldUsing, final boolean newUsing) {
        context.stage("USE_METADATA_STATE_BEGIN", "oldUsing", oldUsing, "newUsing", newUsing);
        if (newUsing) {
            this.awaitingMetadataStartConfirmation = false;
        } else if (context.serverUsingItem()
                && this.usedItem != this.airItem && this.javaItemId != -1) {
            // The client has observed an ACTION=false packet from an older use
            // generation while Nukkit is already using the replacement item.
            // Keep the request pending until the later ACTION=true packet arrives.
            this.awaitingMetadataStartConfirmation = true;
            this.dirtyUsing = DirtyUsing.INVENTORY_TRANSACTION;
        }

        context.stage("USE_METADATA_STATE_END", "oldUsing", oldUsing, "newUsing", newUsing);
        if (oldUsing == newUsing) {
            return;
        }

    }

    public void onMovementMetadataSent(final boolean using) {
        context.stage("USE_METADATA_SENT_BEGIN", "using", using,
                "lastMetadataTrueSentPlayerTick", this.lastMetadataTrueSentPlayerTick);
        if (using) {
            this.lastMetadataTrueSentPlayerTick = context.playerTick();
            context.stage("USE_METADATA_SENT_END", "using", using,
                    "lastMetadataTrueSentPlayerTick", this.lastMetadataTrueSentPlayerTick);
            return;
        }

        this.pendingMetadataFalsePackets++;

        // Sending a stop does not say which movement input observed it, even
        // when a start was sent in the same server-observed player tick. Only
        // the ordered acknowledgment may apply that metadata transition.
        context.stage("USE_METADATA_SENT_END", "using", using,
                "lastMetadataTrueSentPlayerTick", this.lastMetadataTrueSentPlayerTick);
    }

    public void onMovementMetadataAcknowledged(final boolean using) {
        context.stage("USE_METADATA_ACK_BEGIN", "using", using);
        this.lastAcknowledgedMetadataUsing = using;
        if (using) {
            this.awaitingMetadataStartConfirmation = false;
            context.stage("USE_METADATA_ACK_END", "using", using);
            return;
        }

        if (this.pendingMetadataFalsePackets > 0) {
            this.pendingMetadataFalsePackets--;
        }
        this.lastMetadataFalseAckPlayerTick = context.playerTick();

        if (context.serverUsingItem()
                && this.usedItem != this.airItem && this.javaItemId != -1) {
            this.awaitingMetadataStartConfirmation = true;
            this.dirtyUsing = DirtyUsing.INVENTORY_TRANSACTION;
        } else {
            this.awaitingMetadataStartConfirmation = false;
        }
        context.stage("USE_METADATA_ACK_END", "using", using);
    }

    public void snapshotPredictionUseState() {
        context.stage("USE_PREDICTION_SNAPSHOT_BEGIN");
        this.predictionAction = context.hasFlag(ACTION_FLAG);
        this.predictionInputTick = context.playerTick();
        this.predictionUsingItem = context.hasFlag(USING_ITEM)
                && !this.awaitingMetadataStartConfirmation;
        this.predictionItemSlowdownFactor = this.predictionUsingItem && !this.isUsingSpear()
                ? 0.122499995F : 1F;
        context.stage("USE_PREDICTION_SNAPSHOT_END");
    }

    /** Native flags history belongs to the movement phase, before a later local release. */
    public Set<Integer> snapshotPredictionFlags(final long inputTick) {
        final Set<Integer> flags = context.cloneFlags();
        if (this.predictionInputTick == inputTick) {
            if (this.predictionAction) flags.add(ACTION_FLAG);
            else flags.remove(ACTION_FLAG);
        }
        return flags;
    }

    /** Update the model's local-start flags; this does not acknowledge a server packet. */
    public void applyPredictedUseStart() {
        final boolean using = !this.awaitingMetadataStartConfirmation;
        context.setFlag(USING_ITEM, using);
        if (using) {
            context.setFlag(ACTION_FLAG, true);
        }
    }

    public void release() {
        context.event("USE_RELEASE_BEGIN");
        // Bedrock can emit RELEASE_ITEM as part of a carried-slot transition
        // while the physical use button is still held. Wait for the following
        // auth-input frame before deciding whether this was a real button-up.
        this.buttonReleasePending = this.useButtonLatched;
        this.releaseState(false);
        context.event("USE_RELEASE_END");
    }

    public void finishAuthInput(final boolean startUsingItemPresent) {
        context.stage("USE_FINISH_AUTH_INPUT", "startUsingItemPresent", startUsingItemPresent);
        if (!this.buttonReleasePending) {
            return;
        }

        this.buttonReleasePending = false;
        if (startUsingItemPresent) {

            return;
        }

        this.useButtonLatched = false;

    }

    private void releaseState(final boolean clearButtonLatch) {
        context.event("USE_CLEAR_STATE_BEGIN", "clearButtonLatch", clearButtonLatch);

        this.usedItem = this.airItem;
        this.javaItemId = -1;
        this.dirtyUsing = DirtyUsing.NONE;
        this.awaitingMetadataStartConfirmation = false;
        // A release may run after this movement's input has already been
        // scaled. It affects the next snapshot, never the current input.
        if (clearButtonLatch) {
            this.useButtonLatched = false;
            this.buttonReleasePending = false;
        }
        context.sinceTridentUse(0);
        context.setFlag(USING_ITEM, false);
        context.setFlag(ACTION_FLAG, false);
        context.event("USE_CLEAR_STATE_END", "clearButtonLatch", clearButtonLatch);

    }

    public boolean resumeLatchedUse(final I heldItem) {
        context.event("USE_RESUME_ATTEMPT");
        if (!this.useButtonLatched || heldItem == null || context.isNull(heldItem)
                || !canBeUse(heldItem, context.nativeItemId(heldItem))) {
            return false;
        }

        this.usedItem = heldItem;
        this.javaItemId = context.nativeItemId(heldItem);
        this.dirtyUsing = DirtyUsing.NONE;
        this.buttonReleasePending = false;
        context.sinceTridentUse(0);
        // A held-button restart can follow an acknowledged completion in this
        // same input tick. Honor the stop until the replacement start is
        // confirmed, as use(skip=false) does. Repeated START edges must retain
        // that wait; an already acknowledged start must remain usable.
        if (!context.hasFlag(USING_ITEM)
                && (this.hasUnreplacedStopInCurrentInput()
                || Boolean.FALSE.equals(this.lastAcknowledgedMetadataUsing))) {
            this.awaitingMetadataStartConfirmation = true;
        }
        if (!this.awaitingMetadataStartConfirmation) {
            this.applyPredictedUseStart();
        }

        context.event("USE_RESUMED");
        return true;
    }

    public Snapshot<I> snapshot() {
        return new Snapshot<>(copy(this.usedItem), this.javaItemId, this.dirtyUsing,
                this.useDuration, this.useButtonLatched, this.buttonReleasePending,
                this.awaitingMetadataStartConfirmation, this.predictionUsingItem,
                this.predictionAction, this.predictionInputTick,
                this.predictionItemSlowdownFactor,
                context.sinceTridentUse());
    }

    public void restore(final Snapshot<I> snapshot) {
        if (snapshot == null) {
            return;
        }
        context.stage("USE_RESTORE_BEGIN", "restoredPredictionUsingItem", snapshot.predictionUsingItem(),
                "restoredJavaItemId", snapshot.javaItemId(), "restoredAwaitingMetadata", snapshot.awaitingMetadataStartConfirmation());
        this.usedItem = copy(snapshot.usedItem());
        this.javaItemId = snapshot.javaItemId();
        this.dirtyUsing = snapshot.dirtyUsing();
        this.useDuration = snapshot.useDuration();
        this.useButtonLatched = snapshot.useButtonLatched();
        this.buttonReleasePending = snapshot.buttonReleasePending();
        this.awaitingMetadataStartConfirmation = snapshot.awaitingMetadataStartConfirmation();
        this.predictionUsingItem = snapshot.predictionUsingItem();
        this.predictionAction = snapshot.predictionAction();
        this.predictionInputTick = snapshot.predictionInputTick();
        this.predictionItemSlowdownFactor = snapshot.predictionItemSlowdownFactor();
        context.sinceTridentUse(snapshot.sinceTridentUse());
        context.stage("USE_RESTORE_END");
    }

    private I copy(final I item) {
        return item == null || context.isNull(item) ? this.airItem : context.cloneItem(item);
    }

    public record Snapshot<I>(I usedItem, int javaItemId, DirtyUsing dirtyUsing,
                           int useDuration, boolean useButtonLatched,
                           boolean buttonReleasePending,
                           boolean awaitingMetadataStartConfirmation,
                           boolean predictionUsingItem,
                           boolean predictionAction,
                           long predictionInputTick,
                           float predictionItemSlowdownFactor,
                           int sinceTridentUse) {
    }

    public void onHeldItemChanged(final I newHeldItem) {
        context.stage("USE_HELD_ITEM_CHANGED");
        if (!context.hasFlag(USING_ITEM)
                || this.usedItem == this.airItem
                || context.sameDefinition(newHeldItem, this.usedItem)) {
            return;
        }

        // Changing the carried item immediately stops the client's slowdown model.
        // Preserve only the physical button edge so a later CLICK_AIR can resume
        // without requiring Bedrock to emit another START_USING_ITEM.
        this.releaseState(false);
    }

    public void use(final I usedItem, int itemId, boolean skip) {
        context.event("USE_REQUEST", "itemId", itemId, "validatedStartEdge", skip);
        if (!canBeUse(usedItem, itemId) && !skip) {
            context.event("USE_REJECTED", "itemId", itemId);
            return;
        }

        this.usedItem = usedItem;
        this.javaItemId = itemId;
        this.dirtyUsing = DirtyUsing.INVENTORY_TRANSACTION;
        if (!skip) {
            // An ACTION=false packet that is merely in flight does not prevent the
            // Bedrock client from applying its new local START_USING_ITEM slowdown.
            // Wait only after that false packet has actually been acknowledged in
            // the same player/AuthInput tick as this replacement use request.
            this.awaitingMetadataStartConfirmation =
                    this.hasUnreplacedStopInCurrentInput();
        }
        if (skip) {
            // skip=true is a validated START_USING_ITEM event. It is not a
            // persistent physical-button snapshot; the latch is a legacy policy.
            this.useButtonLatched = true;
            this.buttonReleasePending = false;
            }
        context.sinceTridentUse(0);
        context.event("USE_REQUEST_APPLIED", "itemId", itemId, "validatedStartEdge", skip);

    }

    public Context<I> getPlayer() {
        return this.context;
    }

    public I getUsedItem() {
        return this.usedItem;
    }

    public void setUsedItem(final I usedItem) {
        this.usedItem = usedItem;
        context.stage("USE_SETTER", "setter", "setUsedItem");
    }

    public int getJavaItemId() {
        return this.javaItemId;
    }

    public void setJavaItemId(final int javaItemId) {
        this.javaItemId = javaItemId;
        context.stage("USE_SETTER", "setter", "setJavaItemId");
    }

    public DirtyUsing getDirtyUsing() {
        return this.dirtyUsing;
    }

    public void setDirtyUsing(final DirtyUsing dirtyUsing) {
        this.dirtyUsing = dirtyUsing;
        context.stage("USE_SETTER", "setter", "setDirtyUsing");
    }

    public int getUseDuration() {
        return this.useDuration;
    }

    public void setUseDuration(final int useDuration) {
        this.useDuration = useDuration;
        context.stage("USE_SETTER", "setter", "setUseDuration");
    }

    public boolean isUseButtonLatched() {
        return this.useButtonLatched;
    }

    public void setUseButtonLatched(final boolean useButtonLatched) {
        this.useButtonLatched = useButtonLatched;
        context.stage("USE_SETTER", "setter", "setUseButtonLatched");
    }

    public boolean isButtonReleasePending() {
        return this.buttonReleasePending;
    }

    public void setButtonReleasePending(final boolean buttonReleasePending) {
        this.buttonReleasePending = buttonReleasePending;
    }

    public boolean isAwaitingMetadataStartConfirmation() {
        return this.awaitingMetadataStartConfirmation;
    }

    public boolean isPredictionUsingItem() {
        return this.predictionUsingItem;
    }

    public boolean isPredictionAction() {
        return this.predictionAction;
    }

    public long getPredictionInputTick() {
        return this.predictionInputTick;
    }

    public float getPredictionItemSlowdownFactor() {
        return this.predictionItemSlowdownFactor;
    }

    public boolean hasPredictionItemSlowdown() {
        return this.predictionItemSlowdownFactor != 1F;
    }

    public int getPendingMetadataFalsePackets() {
        return this.pendingMetadataFalsePackets;
    }

    public long getLastMetadataFalseAckPlayerTick() {
        return this.lastMetadataFalseAckPlayerTick;
    }

    public Boolean getLastAcknowledgedMetadataUsing() {
        return this.lastAcknowledgedMetadataUsing;
    }

    private boolean hasUnreplacedStopInCurrentInput() {
        // ACTION also changes on local start/release and can be restored from
        // movement history. Neither it nor USING_ITEM identifies the last ACK.
        return this.lastMetadataFalseAckPlayerTick == context.playerTick()
                && Boolean.FALSE.equals(this.lastAcknowledgedMetadataUsing);
    }

    private boolean canBeUse(final I usedItem, int itemId) {
        if (context.hasUseDurationComponent(usedItem)) {
            return true;
        }

        // Nukkit-MOT does not expose Bedrock component NBT for every vanilla item.
        // Its native use-duration/release flags are the equivalent source for those
        // definitions (food, shields and other hold-to-use items).
        if (context.canRelease(usedItem) || context.useDuration(usedItem) > 0) {
            return true;
        }

        return itemId == 261 || itemId == 471
                || itemId == 455 || itemId == 381
                || itemId == 772 || itemId == 373
                || context.identifierEquals(usedItem, "minecraft:ominous_bottle");
    }
}
