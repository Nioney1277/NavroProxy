package ac.navro.proxy.prediction.live;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.data.ItemUseTracker;
import ac.navro.proxy.data.inventory.NativePredictionItems;
import ac.navro.proxy.data.inventory.NativeItemDecoder;
import ac.navro.proxy.player.data.tracker.FlagTracker;
import ac.navro.proxy.util.ItemUtil;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.*;
import java.util.*;

import static ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields;


public final class PredictionItemUse implements ItemUseTracker.Context<ItemData>,FlagTracker.Context<ItemData>,
        ac.navro.proxy.validator.inventory.ItemTransactionValidator.TransactionContext,
        ac.navro.proxy.validator.inventory.ItemTransactionValidator.ClickAirContext<ItemData>,
        ac.navro.proxy.validator.inventory.ItemTransactionValidator.ReleaseContext<ItemData> {
    private final SessionState session;
    private final ItemUseTracker<ItemData> tracker=new ItemUseTracker<>(this);
    private final FlagTracker<ItemData> flags;
    private boolean serverUsing;
    private int sinceTridentUse;
    public PredictionItemUse(SessionState session){this.session=session;this.flags=session.player().trackedFlags();}
    public ItemUseTracker<ItemData> getItemUseTracker(){return tracker;}
    public void onHeldItemChanged(ItemData item){



        if (usingItemFlag()) {
            traceUse("item_use_held_change",fields("decision","await_use_stop"));
            return;
        }

        var pending=tracker.getUsedItem();
        if (pending!=airItem()&&!sameDefinition(nativeItem(item),pending)) {
            tracker.release();
            traceUse("item_use_held_change",fields("decision","cancel_pending_request"));
        }
    }
    public ItemData airItem(){return ItemData.AIR;}
    private NativePredictionItems.Traits traits(ItemData item){return NativePredictionItems.resolve(session.player().protocolVersion(),item);}
    public String identifier(ItemData item){return ItemUtil.identifier(nativeItem(item));}
    public boolean sameDefinition(ItemData a,ItemData b){return ItemUtil.sameDefinition(nativeItem(a),nativeItem(b));}
    public boolean isNull(ItemData item){return item==null||item.getCount()<=0||traits(item).positiveNull();}
    public int nativeItemId(ItemData item){return traits(item).nativeId();}
    public boolean canRelease(ItemData item){return traits(item).canRelease();}
    public int useDuration(ItemData item){var t=traits(item);rethrow(t.durationError());return t.duration();}
    public Object foodByRelative(ItemData item){if(item==null)return null;var t=traits(item);rethrow(t.foodError());return t.food()?Boolean.TRUE:null;}
    private static void rethrow(String error){if(error.isEmpty())return;int split=error.indexOf('\n');String type=error.substring(0,split),message=error.substring(split+1);switch(type){case "java.lang.IllegalArgumentException"->throw new IllegalArgumentException(message);case "java.lang.NullPointerException"->throw new NullPointerException(message);default->throw new IllegalStateException(error);}}
    public ItemData cloneItem(ItemData item){return ItemUtil.copy(item);}
    public long playerTick(){return session.player().tick();}
    public boolean serverUsingItem(){return serverUsing;}
    public boolean hasFlag(int flag){return flags.has(flag);}
    public void setFlag(int flag,boolean value){flags.set(flag,value);}
    public Set<Integer> cloneFlags(){return flags.cloneFlags();}
    public int sinceTridentUse(){return sinceTridentUse;}
    public void sinceTridentUse(int value){sinceTridentUse=value;}
    public void stage(String name,Object...details){}
    public void event(String name,Object...details){}
    public boolean hasUseDurationComponent(ItemData item){
        if(item==null||item.getDefinition()==null)return false;
        NbtMap root=item.getDefinition().getComponentData();if(root==null)return false;
        Object data=root.get("components");if(!(data instanceof NbtMap components))return false;
        if(components.containsKey("minecraft:use_duration"))return true;
        Object properties=components.get("item_properties");return properties instanceof NbtMap map&&map.containsKey("use_duration");
    }
    private final Map<Integer,ItemData> serverInventory=new HashMap<>();
    private final Map<Integer,ItemData> serverArmor=new HashMap<>();
    private boolean serverGliding;
    public void observeInventory(BedrockPacket packet){
        if(packet instanceof InventoryContentPacket content){
            Map<Integer,ItemData> slots=content.getContainerId()==0?serverInventory:content.getContainerId()==120?serverArmor:null;
            if(slots!=null){slots.clear();for(int i=0;i<content.getContents().size();i++)slots.put(i,ItemUtil.copy(content.getContents().get(i)));}
        }else if(packet instanceof InventorySlotPacket slot){
            Map<Integer,ItemData> slots=slot.getContainerId()==0?serverInventory:slot.getContainerId()==120?serverArmor:null;
            if(slots!=null)slots.put(slot.getSlot(),ItemUtil.copy(slot.getItem()));
        }
        if(packet instanceof SetEntityDataPacket metadata && metadata.getRuntimeEntityId()==session.player().runtimeId()
                && metadata.getMetadata().isFlagPresent(EntityFlag.GLIDING))serverGliding=metadata.getMetadata().getFlag(EntityFlag.GLIDING);
    }
    public boolean nativeFirework(ItemData held){return nativeItemId(held)==401;}
    public boolean serverGliding(){return serverGliding;}
    public boolean serverChestIsElytra(){ItemData chest=serverArmor.get(1);return chest!=null && nativeItemId(chest)==444;}
    public boolean compensatedGliding(){return session.player().predictionFlag(EntityFlag.GLIDING);}
    public boolean nativeIsNull(ItemData item){return isNull(item);}
    public int nativeFireworkFlight(ItemData item){
        item=nativeItem(item);
        Object fireworks=item.getTag()==null?null:item.getTag().get("Fireworks");
        return fireworks instanceof NbtMap tag?ac.navro.proxy.util.NbtNumber.getByte(tag,"Flight"):0;
    }
    public boolean usingItemFlag(){return flags.has(-1);}
    public ItemUseTracker<ItemData> tracker(){return tracker;}
    public ItemData nativeItem(ItemData item){return NativeItemDecoder.decode(session.player().protocolVersion(),item);}
    public ItemData serverHeldItem(){return nativeItem(serverInventory.getOrDefault(session.inventory().heldItemSlot,ItemData.AIR));}
    public InventoryTransactionPacket releaseItemData(InventoryTransactionPacket packet){return packet;}
    public int riptideLevel(ItemData item){return item!=null && !isNull(item) && nativeItemId(item)==455
            ?ac.navro.proxy.compensated.CompensatedInventory.getEnchantmentLevel(nativeItem(item),NativeIds.Enchantment.ID_TRIDENT_RIPTIDE):0;}
    public void setDirtyRiptide(int since,ItemData item){
        if(since<10 || riptideLevel(item)<=0)return;
        var p=session.authInputPackets().context();p.riptideItem=ItemUtil.copy(nativeItem(item));p.dirtyRiptide=true;
    }
    public ac.navro.anticheat.util.math.Vec3 position(){return session.player().position();}
    public boolean validate(ItemData predicted,ItemData claimed){return ItemUtil.sameDefinition(nativeItem(predicted),NativeItemDecoder.decode(session.player().protocolVersion(),claimed,NativeItemDecoder.Format.TRANSACTION));}
    @Override
    public void validatorDecision(InventoryTransactionPacket packet, String reason) {
        traceTransaction(packet, "validator", reason);
    }

    public void traceTransaction(InventoryTransactionPacket packet, String source, String result) {
        if (session.player().worldDiagnostics() == null) return;
        var type = packet.getTransactionType();
        if (type != InventoryTransactionType.ITEM_USE && type != InventoryTransactionType.ITEM_RELEASE) return;
        traceUse("item_use_transaction", fields(
                "source", source, "result", result, "type", type.name(), "action", packet.getActionType(),
                "claimedSlot", packet.getHotbarSlot(), "claimedItem", describeItem(packet.getItemInHand()),
                "actionCount", packet.getActions().size()));
    }

    private void traceUse(String event, Map<String, Object> details) {
        var trace = session.player().worldDiagnostics();
        if (trace == null) return;
        try {
            int slot = session.inventory().heldItemSlot;
            var pending = session.inventoryResponseSync().pending().stream()
                    .filter(entry -> entry.slot() == slot)
                    .filter(entry -> switch (entry.type()) {
                        case HOTBAR, HOTBAR_AND_INVENTORY, INVENTORY -> true;
                        default -> false;
                    })
                    .map(entry -> fields("requestId", entry.requestId(), "count", entry.count(), "stackId", entry.stackId()))
                    .toList();
            details.put("heldSlot", slot);
            details.put("cachedItem", describeItem(session.inventory().inventoryContainer.getHeldItemData()));
            details.put("backendItem", describeItem(session.backendInventories().slot(0, slot)));
            details.put("pendingIdentity", pending);
            details.put("usedItem", describeItem(tracker.getUsedItem()));
            details.put("dirtyUsing", tracker.getDirtyUsing().name());
            details.put("usingItem", usingItemFlag());
            details.put("serverUsingItem", serverUsing);
            details.put("awaitingMetadata", tracker.isAwaitingMetadataStartConfirmation());
            details.put("slowdown", tracker.getPredictionItemSlowdownFactor());
            trace.event(event, details);
        } catch (RuntimeException ignored) {

        }
    }

    private static Map<String, Object> describeItem(ItemData item) {
        if (item == null) return null;
        var definition = item.getDefinition();
        return fields("identifier", definition == null ? null : definition.getIdentifier(),
                "runtimeId", definition == null ? null : definition.getRuntimeId(),
                "count", item.getCount(), "damage", item.getDamage());
    }

    public boolean handleClickBlock(InventoryTransactionPacket data){throw new IllegalStateException("Block transactions use their own complete dispatcher");}
    public boolean handleClickAir(InventoryTransactionPacket data){return session.transactionValidator().handleClickAir(data,this);}
    public boolean handleRelease(InventoryTransactionPacket packet){return session.transactionValidator().handleRelease(packet,this);}
    public void observeMetadata(SetEntityDataPacket packet){
        if(session.player().vehicleData!=null)return;
        if(packet.getRuntimeEntityId()!=session.player().runtimeId() || !ac.navro.proxy.util.EntityMetadataUtil.hasBaseFlags(packet.getMetadata()))return;
        boolean using=packet.getMetadata().getFlag(EntityFlag.USING_ITEM);serverUsing=using;
        tracker.onMovementMetadataSent(using);
        session.player().queueAcknowledgment(packet,()->{
            tracker.onMovementMetadataAcknowledged(using);


        });
    }
    public void beginInput(PlayerAuthInputPacket packet){
        if(!flags.has(-1))sinceTridentUse=0;
        boolean start=packet.getInputData().contains(PlayerAuthInputData.START_USING_ITEM);
        var before = tracker.getDirtyUsing();
        float previousSlowdown = tracker.getPredictionItemSlowdownFactor();
        String decision = "no_start_edge";
        var iterator=packet.getInputData().iterator();
        while(iterator.hasNext()){
            var action=iterator.next();if(action!=PlayerAuthInputData.START_USING_ITEM)continue;
            ItemData item=nativeItem(session.inventory().inventoryContainer.getHeldItemData());
            if(tracker.getDirtyUsing()==ItemUseTracker.DirtyUsing.NONE){
                if(tracker.resumeLatchedUse(item)){
                    decision = "resumed";
                    break;
                }
                decision = "no_pending_use";
                iterator.remove();continue;
            }
            if(isNull(item)){
                decision = "cached_item_empty";
                iterator.remove();continue;
            }
            tracker.use(item,nativeItemId(item),true);
            boolean await=tracker.isAwaitingMetadataStartConfirmation();tracker.applyPredictedUseStart();
            if(!await)tracker.setDirtyUsing(ItemUseTracker.DirtyUsing.NONE);
            decision = await ? "waiting_for_metadata" : "started";
        }
        tracker.finishAuthInput(start);
        var dirty=tracker.getDirtyUsing();
        if(dirty!=ItemUseTracker.DirtyUsing.NONE && dirty==ItemUseTracker.DirtyUsing.METADATA && !flags.has(-1)){
            flags.set(-1,false);tracker.setDirtyUsing(ItemUseTracker.DirtyUsing.NONE);
        }
        tracker.snapshotPredictionUseState();
        if (session.player().worldDiagnostics() != null
                && (start || previousSlowdown != tracker.getPredictionItemSlowdownFactor())) {
            traceUse("item_use_input", fields("inputTick", packet.getTick(), "decision", decision,
                    "startReceived", start, "startKept", packet.getInputData().contains(PlayerAuthInputData.START_USING_ITEM),
                    "dirtyBefore", before.name(), "previousSlowdown", previousSlowdown,
                    "motion", String.valueOf(packet.getMotion()), "rawMoveVector", String.valueOf(packet.getRawMoveVector())));
        }
        tracker.preTick();
    }
    public void finishInput(){tracker.postTick();}
}
