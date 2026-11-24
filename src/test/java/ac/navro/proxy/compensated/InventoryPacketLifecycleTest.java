package ac.navro.proxy.compensated;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.compensated.cache.container.ContainerCache;
import ac.navro.proxy.compensated.cache.container.impl.TradeContainerCache;
import ac.navro.proxy.data.inventory.ItemCache;
import ac.navro.proxy.inventory.InventoryType;
import ac.navro.proxy.packets.other.NetworkLatencyPackets;
import ac.navro.proxy.util.EntityBatchEncoder;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.MultiRecipeData;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchEncoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.NoopCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end inventory cache writes across the real packet/batch/compression ACK boundary. */
class InventoryPacketLifecycleTest {
    private static final ItemDefinition STONE = new SimpleItemDefinition("minecraft:stone", 1, false);
    private static final ItemDefinition BUNDLE = new SimpleItemDefinition("minecraft:bundle", 2, false);
    private static final ItemDefinition LEGGINGS = new SimpleItemDefinition("minecraft:diamond_leggings", 3, false);
    private static final ItemDefinition INFO_UPDATE = new SimpleItemDefinition("minecraft:info_update", 4, false);
    private static final FullContainerName INVENTORY = new FullContainerName(ContainerSlotType.INVENTORY, null);

    private static ItemData stone(int count, int netId) {
        return ItemData.builder().definition(STONE).count(count).netId(netId).usingNetId(true).build();
    }

    private static InventorySlotPacket slot(int window, int slot, ItemData item) {
        InventorySlotPacket packet = new InventorySlotPacket();
        packet.setContainerId(window);
        packet.setSlot(slot);
        packet.setItem(item);
        packet.setStorageItem(ItemData.AIR);
        packet.setContainerNameData(INVENTORY);
        return packet;
    }

    private static final class Fixture implements AutoCloseable {
        final SessionState state = new SessionState();
        final CompensatedInventory inventory = state.inventory();
        final EmbeddedChannel channel = new EmbeddedChannel();
        final EntityBatchEncoder encoder;

        Fixture() {
            state.player().setProtocolVersion(2193);
            StartGamePacket start = new StartGamePacket();
            start.setRuntimeEntityId(7);
            start.setPlayerPosition(Vector3f.from(0, 64, 0));
            state.observeBackend(start);
            channel.pipeline().addLast(CompressionCodec.NAME,
                    new CompressionCodec(new SimpleCompressionStrategy(new NoopCompression()), false));
            channel.pipeline().addLast(BedrockBatchEncoder.NAME, new BedrockBatchEncoder());
            BedrockPacketCodec codec = new BedrockPacketCodec_v3();
            codec.setCodec(Bedrock_v2193.CODEC);
            Map<Integer, ItemDefinition> definitions = Map.of(0, ItemDefinition.AIR, 1, STONE, 2, BUNDLE, 3, LEGGINGS, 4, INFO_UPDATE);
            codec.getHelper().setItemDefinitions(new DefinitionRegistry<ItemDefinition>() {
                @Override public ItemDefinition getDefinition(int id) { return definitions.get(id); }
                @Override public boolean isRegistered(ItemDefinition item) { return definitions.get(item.getRuntimeId()) == item; }
            });
            channel.pipeline().addLast(BedrockPacketCodec.NAME, codec);
            BedrockServerSession session = new BedrockServerSession(new BedrockPeer(channel, BedrockServerSession::new), 0);
            encoder = EntityBatchEncoder.install(session, state.player().getLatencyUtil());
            state.player().setEntityBatchEncoder(encoder);
        }

        void send(BedrockPacket packet) {
            state.observeBackend(packet);
            channel.write(BedrockPacketWrapper.create(0, 0, 0, packet, null));
        }

        long flush() {
            channel.flush();
            channel.checkException();
            BedrockBatchWrapper batch;
            while ((batch = channel.readOutbound()) != null) batch.release();
            return state.player().getLatencyUtil().sentQueue().getLast().id();
        }

        void ack(long id) {
            NetworkStackLatencyPacket packet = new NetworkStackLatencyPacket();
            packet.setTimestamp(id * 1_000_000L);
            assertTrue(NetworkLatencyPackets.onPacket(state.player(), packet));
        }

        @Override public void close() { encoder.close(); channel.finishAndReleaseAll(); }
    }

    @Test void fullContentAndSlotUpdatesWaitForTheirOwnAckAndFreezeAllItemFields() {
        try (Fixture f = new Fixture()) {
            InventoryContentPacket content = new InventoryContentPacket();
            content.setContainerId(0);
            content.setContainerNameData(INVENTORY);
            content.setStorageItem(ItemData.AIR);
            content.setContents(new ArrayList<>(List.of(stone(12, 11), stone(30, 12))));
            f.send(content);
            assertTrue(f.inventory.inventoryContainer.get(0).isEmpty());
            long first = f.flush();
            assertTrue(f.inventory.inventoryContainer.get(0).isEmpty());

            ItemData later = stone(4, 99).toBuilder().tag(NbtMap.builder().putInt("custom", 6).build())
                    .canPlace("minecraft:grass").build();
            f.send(slot(0, 0, later));
            later.setNetId(777);
            later.getCanPlace()[0] = "minecraft:air";
            long second = f.flush();
            f.ack(first);
            assertEquals(12, f.inventory.inventoryContainer.get(0).count());
            f.ack(second);
            ItemData cached = f.inventory.inventoryContainer.get(0).getData();
            assertEquals(4, cached.getCount());
            assertEquals(99, cached.getNetId());
            assertEquals(6, cached.getTag().getInt("custom"));
            assertArrayEquals(new String[]{"minecraft:grass"}, cached.getCanPlace());
            assertEquals(30, f.inventory.inventoryContainer.get(1).count());
        }
    }

    @Test void offsetContainersAndCloseRequestsFollowTheNavroWindowLifecycle() {
        try (Fixture f = new Fixture()) {
            ContainerOpenPacket open = new ContainerOpenPacket();
            open.setId((byte) 4);
            open.setType(ContainerType.ENCHANTMENT);
            open.setBlockPosition(Vector3i.from(1, 64, 1));
            f.send(open);
            f.send(slot(4, 14, stone(1, 8)));
            assertNull(f.inventory.openContainer);
            f.ack(f.flush());
            ContainerCache container = f.inventory.openContainer;
            assertEquals(14, container.getOffset());
            assertEquals(16, container.getContainerSize());
            assertEquals(2, container.getContents().length);
            assertEquals(1, container.get(14).count());
            assertTrue(container.get(0).isEmpty());
            ContainerClosePacket close = new ContainerClosePacket();
            close.setId((byte) 9);
            f.state.observeClient(close);
            assertSame(container, f.inventory.openContainer);
            close.setId((byte) -1);
            f.state.observeClient(close);
            assertNull(f.inventory.openContainer);
        }
    }

    @Test void nativeHotbarCorrectionWaitsForAckWhileClientEquipAppliesItsOwnSlotRules() {
        try (Fixture f = new Fixture()) {
            PlayerHotbarPacket hotbar = new PlayerHotbarPacket();
            hotbar.setContainerId(0);
            hotbar.setSelectHotbarSlot(true);
            hotbar.setSelectedHotbarSlot(5);
            f.send(hotbar);
            assertEquals(0, f.inventory.heldItemSlot);
            f.ack(f.flush());
            assertEquals(5, f.inventory.heldItemSlot);
            MobEquipmentPacket equip = new MobEquipmentPacket();
            equip.setRuntimeEntityId(8);
            equip.setContainerId(0);
            equip.setHotbarSlot(3);
            f.state.observeClient(equip);
            assertEquals(5, f.inventory.heldItemSlot);
            equip.setRuntimeEntityId(7);
            f.state.observeClient(equip);
            assertEquals(3, f.inventory.heldItemSlot);
            equip.setHotbarSlot(9);
            f.state.observeClient(equip);
            assertEquals(3, f.inventory.heldItemSlot);
        }
    }

    @Test void reopeningTheSameWindowIdStartsAFreshCompensatedCacheAtAck() {
        try (Fixture fixture = new Fixture()) {
            var open = new ContainerOpenPacket();
            open.setId((byte) 5);
            open.setType(ContainerType.CONTAINER);
            open.setBlockPosition(Vector3i.ZERO);
            fixture.send(open);
            fixture.send(slot(5, 0, stone(3, 20)));
            fixture.ack(fixture.flush());
            var previous = fixture.inventory.openContainer;
            assertEquals(3, previous.get(0).count());

            fixture.send(open);
            assertSame(previous, fixture.inventory.openContainer);
            fixture.ack(fixture.flush());
            assertNotSame(previous, fixture.inventory.openContainer);
            assertTrue(fixture.inventory.openContainer.get(0).isEmpty());

            var close = new ContainerClosePacket();
            close.setId((byte) 5);
            close.setType(ContainerType.CONTAINER);
            close.setServerInitiated(true);
            fixture.send(close);
            assertNotNull(fixture.inventory.openContainer);
            fixture.ack(fixture.flush());
            assertNull(fixture.inventory.openContainer);
        }
    }

    @Test void armorSwiftSneakAndBundleStorageUpdateOnlyAfterAcknowledgment() {
        try (Fixture f = new Fixture()) {
            NbtMap ench = NbtMap.builder().putShort("id", (short) 37).putShort("lvl", (short) 3).build();
            ItemData leggings = ItemData.builder().definition(LEGGINGS).count(1)
                    .tag(NbtMap.builder().putList("ench", NbtType.COMPOUND, List.of(ench)).build()).build();
            f.send(slot(120, 2, leggings));
            assertEquals(0, f.state.player().sneakingAttributeModifier);
            f.ack(f.flush());
            assertEquals(0.15F + 0.15F * 2, f.state.player().sneakingAttributeModifier);

            ItemData bundle = ItemData.builder().definition(BUNDLE).count(1)
                    .tag(NbtMap.builder().putInt("bundle_id", 18).build()).build();
            f.send(slot(0, 0, bundle));
            InventorySlotPacket internal = slot(125, 2, stone(6, 43));
            internal.setStorageItem(bundle);
            internal.setContainerNameData(new FullContainerName(ContainerSlotType.DYNAMIC_CONTAINER, 18));
            f.send(internal);
            assertFalse(f.inventory.getBundleCache().containsKey(18));
            f.ack(f.flush());
            ItemCache cache = f.inventory.getBundleCache().get(18);
            assertNotNull(cache);
            assertSame(cache, f.inventory.inventoryContainer.get(0));
            assertEquals(6, cache.getBundle().getContents()[2].count());
            f.send(slot(120, 2, ItemData.AIR));
            f.ack(f.flush());
            assertEquals(0, f.state.player().sneakingAttributeModifier);
        }
    }

    @Test void creativeAndRecipeRegistriesUsePacketNetworkIdsAndAckOrder() {
        try (Fixture f = new Fixture()) {
            CreativeContentPacket creative = new CreativeContentPacket();
            creative.getContents().add(new CreativeItemData(stone(1, 0), 77, 0));
            f.send(creative);
            CraftingDataPacket crafting = new CraftingDataPacket();
            crafting.getCraftingData().add(MultiRecipeData.of(UUID.randomUUID(), 53));
            f.send(crafting);
            assertTrue(f.inventory.getCreativeData().isEmpty());
            assertTrue(f.inventory.getCraftingData().isEmpty());
            f.ack(f.flush());
            assertEquals(1, f.inventory.getCreativeData().get(77).getCount());
            assertTrue(f.inventory.getCraftingData().containsKey(53));
        }
    }

    @Test void tradeOffersAreAckBoundAndFixedContainerBoundsMatchNavro() {
        try (Fixture f = new Fixture()) {
            UpdateTradePacket trade = new UpdateTradePacket();
            trade.setContainerId(9);
            trade.setContainerType(ContainerType.TRADE);
            trade.setPlayerUniqueEntityId(7);
            trade.setTraderUniqueEntityId(99);
            trade.setDisplayName("Trader");
            trade.setOffers(NbtMap.builder().putInt("test", 1).build());
            f.send(trade);
            assertNull(f.inventory.openContainer);
            f.ack(f.flush());
            assertInstanceOf(TradeContainerCache.class, f.inventory.openContainer);
            assertEquals(4, f.inventory.openContainer.getOffset());
            assertEquals(6, f.inventory.openContainer.getContainerSize());
            assertEquals(1, ((TradeContainerCache) f.inventory.openContainer).getOffers().getInt("test"));
            assertEquals(36, f.inventory.offhandContainer.getContainerSize());
            assertEquals(36, f.inventory.armorContainer.getContainerSize());
            assertEquals(56, new ContainerCache(f.inventory, (byte) 3, InventoryType.CHEST, null, -1).getContainerSize());
        }
    }

    @Test void partialInventoryContentKeepsTrailingSlotsAndContainerBoundsIgnoreInvalidUpdates() {
        try (Fixture f = new Fixture()) {
            f.inventory.inventoryContainer.set(8, stone(11, 8));
            InventoryContentPacket content = new InventoryContentPacket();
            content.setContainerId(0);
            content.setContainerNameData(INVENTORY);
            content.setStorageItem(ItemData.AIR);
            content.setContents(List.of(stone(3, 3)));
            f.send(content);
            f.send(slot(0, 36, stone(55, 4)));
            f.send(slot(13, 0, stone(55, 4)));
            f.ack(f.flush());
            assertEquals(3, f.inventory.inventoryContainer.get(0).count());
            assertEquals(11, f.inventory.inventoryContainer.get(8).count());
            assertTrue(f.inventory.inventoryContainer.get(36).isEmpty());
            assertNull(f.inventory.getContainer((byte) 13));
        }
    }

    @Test void legacyDropIdentifiesWorldAndContainerActionsInEitherOrderAndNeverUsesClaimedReplacement() {
        try (Fixture f = new Fixture()) {
            f.inventory.inventoryContainer.set(0, stone(64, 10));
            var world = org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource
                    .fromWorldInteraction(org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource.Flag.NONE);
            var container = org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource.fromContainerWindowId(0);
            InventoryTransactionPacket drop = new InventoryTransactionPacket();
            drop.setTransactionType(org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType.NORMAL);
            drop.getActions().add(new org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData(
                    container, 0, stone(64, 10), stone(999, 55)));
            drop.getActions().add(new org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData(
                    world, 0, ItemData.AIR, stone(7, 20)));
            f.state.observeClient(drop);
            assertEquals(57, f.inventory.inventoryContainer.get(0).count());
            assertEquals(10, f.inventory.inventoryContainer.get(0).getData().getNetId());
            java.util.Collections.reverse(drop.getActions());
            f.state.observeClient(drop);
            assertEquals(50, f.inventory.inventoryContainer.get(0).count());
            drop.getActions().set(0, new org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData(
                    world, 0, ItemData.AIR, stone(51, 20)));
            f.state.observeClient(drop);
            assertEquals(50, f.inventory.inventoryContainer.get(0).count());
            assertEquals("inventoryPredictionRejected", f.state.anomalies().getLast().kind());
        }
    }

    @Test void modernRequestLocksOneInputAndTheHistoricalSnapshotOwnsItsMovementGate() {
        try (Fixture f = new Fixture()) {
            f.inventory.openContainer = new ContainerCache(f.inventory, (byte) 2, InventoryType.CHEST, null, -1);
            f.state.observeClient(new ItemStackRequestPacket());
            assertTrue(f.state.player().doingInventoryAction);
            PlayerAuthInputPacket input = new PlayerAuthInputPacket();
            input.setTick(0);
            input.setPosition(Vector3f.from(0, 65.62001F, 0));
            input.setRotation(Vector3f.ZERO);
            input.setMotion(org.cloudburstmc.math.vector.Vector2f.from(0, 1));
            input.setRawMoveVector(org.cloudburstmc.math.vector.Vector2f.from(0, 1));
            f.state.observeClient(input);
            assertTrue(f.state.snapshotAt(0).doingInventoryAction());
            assertEquals(0, f.state.snapshotAt(0).input().z);
            assertFalse(f.state.player().doingInventoryAction);
            input.setTick(1);
            f.state.observeClient(input);
            assertEquals(1, f.state.snapshotAt(1).input().z);
            assertFalse(f.state.snapshotAt(1).doingInventoryAction());
            assertTrue(f.state.restoreTick(0));
            assertTrue(f.state.player().doingInventoryAction);
        }
    }
    @Test void nativeNumericBundleIdsAndEnchantmentsSurviveTheirPacketAckBoundary() {
        try (Fixture f = new Fixture()) {
            var bundle = ItemData.builder().definition(BUNDLE).count(1)
                    .tag(NbtMap.builder().putShort("bundle_id", (short) 18).build()).build();
            f.send(slot(0, 0, bundle)); long marker = f.flush();
            assertFalse(f.inventory.getBundleCache().containsKey(18)); f.ack(marker);
            assertTrue(f.inventory.getBundleCache().containsKey(18));
            assertFalse(f.inventory.getBundleCache().containsKey(0));
            var internal = slot(125, 3, stone(6, 43));
            internal.setStorageItem(bundle.toBuilder().tag(NbtMap.builder().putLong("bundle_id", 18L).build()).build());
            f.send(internal); marker = f.flush();
            assertTrue(f.inventory.getBundleCache().get(18).getBundle().getContents()[3].isEmpty()); f.ack(marker);
            assertEquals(6, f.inventory.getBundleCache().get(18).getBundle().getContents()[3].count());
            var leggings = ItemData.builder().definition(LEGGINGS).count(1).tag(NbtMap.builder()
                    .putList("ench", NbtType.COMPOUND, List.of(NbtMap.builder().putInt("id", 37).putLong("lvl", 3L).build())).build()).build();
            f.send(slot(120, 2, leggings)); marker = f.flush();
            assertEquals(0, f.state.player().sneakingAttributeModifier); f.ack(marker);
            assertEquals(0.15F + 0.15F * 2, f.state.player().sneakingAttributeModifier);
            assertEquals(3, CompensatedInventory.getEnchantments(f.inventory.armorContainer.get(2).getData()).get(37));
        }
    }


    static ItemData restored(int id,int count,org.cloudburstmc.nbt.NbtMap inner,String namespace) {
        var tag=org.cloudburstmc.nbt.NbtMap.builder().putInt("mv_origin_id",id).putInt("mv_origin_meta",0).putCompound("mv_origin_nbt",inner).putInt("bundle_id",88);
        if(namespace!=null)tag.putString("mv_origin_namespace",namespace);
        return ItemData.builder().definition(INFO_UPDATE).count(count).tag(tag.build()).build();
    }
    @Test void restoredBundleStorageAndContentsWaitForTheirOwnRealClosingAck() {
        try(Fixture f=new Fixture()) {
            f.state.setDetectionEnabled(true);
            var owner=restored(255,1,org.cloudburstmc.nbt.NbtMap.builder().putInt("bundle_id",7).build(),"minecraft:bundle");
            f.send(slot(0,0,owner));long first=f.flush();assertFalse(f.inventory.getBundleCache().containsKey(7));
            var child=restored(1,5,org.cloudburstmc.nbt.NbtMap.builder().putByteArray("bytes",new byte[]{1,2}).build(),null);
            var content=new InventoryContentPacket();content.setContainerId(125);content.setContainerNameData(new FullContainerName(ContainerSlotType.DYNAMIC_CONTAINER,7));content.setStorageItem(owner);content.setContents(new ArrayList<>(List.of(child,stone(2,11))));
            f.send(content);long second=f.flush();child.getTag().getCompound("mv_origin_nbt").getByteArray("bytes")[0]=99;content.getContents().clear();
            f.ack(first);var cache=f.inventory.getBundleCache().get(7);assertNotNull(cache);assertFalse(f.inventory.getBundleCache().containsKey(88));assertTrue(cache.getBundle().getContents()[0].isEmpty());
            f.ack(second);var stored=cache.getBundle().getContents()[0];assertEquals(5,stored.count());assertEquals("minecraft:info_update",stored.getData().getDefinition().getIdentifier());
            assertEquals("minecraft:stone",stored.getNativeData().getDefinition().getIdentifier());assertArrayEquals(new byte[]{1,2},stored.getNativeData().getTag().getByteArray("bytes"));assertEquals(2,cache.getBundle().getContents()[1].count());
            var change=slot(125,1,restored(3,4,org.cloudburstmc.nbt.NbtMap.EMPTY,null));change.setStorageItem(owner);f.send(change);long third=f.flush();assertEquals(2,cache.getBundle().getContents()[1].count());
            f.ack(third);assertEquals(4,cache.getBundle().getContents()[1].count());assertEquals("minecraft:dirt",cache.getBundle().getContents()[1].getNativeData().getDefinition().getIdentifier());
        }
    }
}
