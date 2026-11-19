package ac.navro.proxy.prediction.fluid;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.data.FluidState;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;






class SwimmingPosePipelineTest {
    private static int block(String name, int data) {
        return NetworkBlockStateRegistry.forProtocol(2193, false).states().values().stream()
                .filter(b -> b.is(name) && b.blockData() == data).findFirst().orElseThrow().networkId();
    }

    private static SessionState water(float feetY, int top) {
        var s = AuthInputPacketsTest.session();
        s.player().rebasePosition(new Vec3(8, feetY, 8), false);
        s.player().setVelocity(Vec3.ZERO);
        int water = block("minecraft:water", 0);
        for (int x = 6; x <= 10; x++) for (int y = 64; y <= top; y++) for (int z = 6; z <= 10; z++)
            s.world().updateBlock(x, y, z, 0, water);
        return s;
    }

    private static void input(SessionState s, long tick, float feetY, PlayerAuthInputData... actions) {
        var p = AuthInputPacketsTest.input(tick, 8, feetY, 8, Vec3.ZERO);
        for (var a : actions) p.getInputData().add(a);
        s.observeClient(p);
    }

    @Test void loadingInputsDoNotSenseLiquidsOrAdvanceTheSwimmingBlendBeforePrediction() {
        var s = water(64, 66);
        s.player().inLoadingScreen = true;
        s.player().sinceLoadingScreen = 0;
        input(s, 10, 64, PlayerAuthInputData.START_SWIMMING);
        for (int tick = 11; tick <= 14; tick++) input(s, tick, 64);
        assertFalse(s.fluidState().wasInWater(), "Navro prepares liquid sensing inside actual prediction");
        assertEquals(0, s.fluidState().swimAmount());
        assertTrue(s.player().clientSwimming(), "Input action ownership still precedes fluid resolution");

        s.player().inLoadingScreen = false;
        s.player().sinceLoadingScreen = 20;
        input(s, 15, 64);
        assertTrue(s.fluidState().wasInWater());
        assertEquals(.1F, s.fluidState().swimAmount(), "Only the first actual prediction advances the blend");
    }

    @Test void pendingTeleportInputsCannotAdvanceFluidHistoryAndConfirmationResetsItsMotion() {
        var s = water(64, 66);
        input(s, 10, 64, PlayerAuthInputData.START_SWIMMING);
        input(s, 11, 64);
        assertEquals(.1F, s.fluidState().swimAmount());
        s.player().getLatencyUtil().queue(100, true);
        var move = new MovePlayerPacket();
        move.setRuntimeEntityId(7);
        move.setMode(MovePlayerPacket.Mode.TELEPORT);
        move.setPosition(Vector3f.from(8, 65.62001F, 8));
        move.setRotation(Vector3f.ZERO);
        s.observeBackend(move);
        for (int tick = 12; tick <= 14; tick++) input(s, tick, 64);
        assertTrue(s.teleports().isTeleporting());
        assertEquals(.1F, s.fluidState().swimAmount());
        assertTrue(s.player().getLatencyUtil().onResponse(100));
        input(s, 15, 64, PlayerAuthInputData.HANDLE_TELEPORT);
        assertFalse(s.teleports().isTeleporting());
        assertEquals(0, s.fluidState().swimAmount());
        input(s, 16, 64);
        assertEquals(.1F, s.fluidState().swimAmount());
    }

    @Test void acknowledgedSwimmingHeightAndStopActionKeepTheInputStartLiquidBox() {
        var s = water(64.68F, 64);
        var metadata = new SetEntityDataPacket();
        metadata.setRuntimeEntityId(7);
        metadata.getMetadata().setFlag(EntityFlag.SWIMMING, true);
        metadata.getMetadata().put(EntityDataTypes.HEIGHT, .6F);
        s.player().getLatencyUtil().queue(200, true);
        s.observeBackend(metadata);
        assertEquals(1.8F, s.player().dimensions().height());
        assertFalse(s.player().clientSwimming());
        assertTrue(s.player().getLatencyUtil().onResponse(200));
        assertEquals(.6F, s.player().dimensions().height());
        assertEquals(.42F, s.player().dimensions().eyeHeight());
        assertTrue(s.player().clientSwimming());

        input(s, 10, 64.68F, PlayerAuthInputData.STOP_SWIMMING);
        assertFalse(s.player().clientSwimming());
        assertFalse(s.player().predictionSwimming());
        assertEquals(1.8F, s.player().dimensions().height());
        var frame = s.fluidState().snapshot();
        assertEquals(.6F, frame.inputDimensions().height());
        assertTrue(frame.inputHorizontalPose());
        assertEquals(64.68F, frame.inputBox().minY);
        assertTrue(frame.liquids().water().touching(), "STOP cannot replace input-start sensing with the expanded standing box");
        assertTrue(frame.waterTravel());
    }

    @Test void waterAndLavaSelectionKeepsHeadWaterSeparateFromTheTravelLiquid() {
        var s = water(64.37F, 66);
        int lava = block("minecraft:lava", 0);
        for (int x = 6; x <= 10; x++) for (int z = 6; z <= 10; z++)
            s.world().updateBlock(x, 64, z, 0, lava);
        input(s, 10, 64.37F);
        assertTrue(s.fluidState().liquids().water().touching());
        assertTrue(s.fluidState().liquids().lava().touching());
        assertEquals(FluidState.Type.LAVA, s.fluidState().liquids().selectedType());
        assertFalse(s.fluidState().wasInWater());
        assertFalse(s.fluidState().waterTravel());
        assertTrue(s.fluidState().headInWater(), "Source-water physical surface is the full cell, not its rendered 8/9 height");
    }

    @Test void chargedRiptideReleaseRecomputesTheLiveHorizontalPoseWithoutAnotherSwimBlendStep() {
        for (boolean alreadySwimming : new boolean[]{false, true}) {
            var s = water(64, 66);
            if (alreadySwimming) input(s, 9, 64, PlayerAuthInputData.START_SWIMMING);
            var trident = ItemData.builder().definition(new ItemDefinition() {
                public String getIdentifier() { return "minecraft:trident"; }
                public int getRuntimeId() { return 123; }
                public boolean isComponentBased() { return false; }
            }).count(1).tag(NbtMap.builder().putList("ench", NbtType.COMPOUND,
                    List.of(NbtMap.builder().putShort("id", (short)30).putShort("lvl", (short)3).build())).build()).build();
            s.inventory().inventoryContainer.set(0, trident);
            s.inventory().heldItemSlot = 0;


            s.authInputPackets().itemUse().sinceTridentUse(12);
            var release = new InventoryTransactionPacket();
            release.setTransactionType(InventoryTransactionType.ITEM_RELEASE);
            release.setActionType(0);
            s.observeClient(release);
            assertTrue(s.authInputPackets().context().dirtyRiptide);
            input(s, 10, 64);
            assertTrue(s.player().hasFlag(56));
            assertTrue(s.player().horizontalPoseApplied());
            assertEquals(.42F, s.player().dimensions().eyeHeight());
            assertEquals(1.8F, s.player().dimensions().height(), "Native live players retain physical height until its authoritative update");
            assertEquals(alreadySwimming ? .1F : 0, s.fluidState().swimAmount(),
                    "Spin resampling must not advance swimming/crawling blend a second time");
            assertEquals(FluidState.Type.WATER, s.fluidState().liquids().selectedType());
            assertEquals(s.fluidState().liquids().selectedType(), s.authInputPackets().context().fluidPrediction.liquids.selectedType());
        }
    }
}
