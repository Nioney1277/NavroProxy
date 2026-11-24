package ac.navro.proxy.check.impl.sprint;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.check.api.impl.OffsetHandlerCheck;
import ac.navro.proxy.check.impl.prediction.Prediction;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import ac.navro.proxy.prediction.live.NativeIds;
import ac.navro.proxy.prediction.live.PredictionContext;
import ac.navro.proxy.teleport.data.TeleportData;
import org.cloudburstmc.protocol.bedrock.data.Ability;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SprintChecksTest {
    @Test void hungerUsesTheAcknowledgedValueAndFrozenSprintState() {
        var fixture = Fixture.create();
        fixture.sprint(true);
        fixture.complete(SprintA.class);
        for (float hunger : new float[]{7F, Float.NaN, Float.POSITIVE_INFINITY}) {
            fixture.hunger(hunger);
            fixture.complete(SprintA.class);
        }
        assertTrue(fixture.alerts().isEmpty());

        fixture.hunger(6F);
        fixture.session().player().setServerSprinting(false);
        fixture.complete(SprintA.class);
        fixture.hunger(0F);
        fixture.complete(SprintA.class);
        assertEquals(List.of("A", "A"), fixture.sprintAlerts());
        assertEquals(2, fixture.rewinds().size());

        fixture.sprint(false);
        fixture.session().player().setServerSprinting(true);
        fixture.complete(SprintA.class);
        assertEquals(2, fixture.rewinds().size());
    }

    @Test void sneakChecksOnLandWithoutASprintStartAndRewindsOnTheSixthFlag() {
        var fixture = Fixture.create();
        fixture.sprint(true);
        fixture.session().player().setPredictionFlag(EntityFlag.SNEAKING, true);
        for (int attempt = 0; attempt < 5; attempt++) fixture.complete(SprintB.class);
        assertTrue(fixture.rewinds().isEmpty());
        fixture.complete(SprintB.class);
        assertEquals(List.of("B", "B", "B", "B", "B", "B"), fixture.sprintAlerts());
        assertEquals(1, fixture.rewinds().size());
    }

    @Test void crawlUsesTheActiveInputPoseAndTheOriginalMultiplierBoundary() {
        var fixture = Fixture.create();
        var prediction = fixture.prediction();
        var pose = fixture.session().player().fluidPrediction();
        fixture.sprint(true);
        fixture.session().player().setPredictionFlag(EntityFlag.CRAWLING, true);
        pose.inputActive = true;
        fixture.complete(SprintB.class);
        assertTrue(fixture.alerts().isEmpty());

        pose.wasCrawling = true;
        prediction.sneakingAttributeModifier = 0.5F;
        prediction.ticksSinceCanSlowdown = 2;
        fixture.complete(SprintB.class);
        prediction.ticksSinceCanSlowdown = 3;
        fixture.complete(SprintB.class);
        assertEquals(List.of("B"), fixture.sprintAlerts());

        pose.inputActive = false;
        pose.wasCrawling = false;
        prediction.sneakingAttributeModifier = 0.49F;
        fixture.complete(SprintB.class);
        assertEquals(List.of("B", "B"), fixture.sprintAlerts());

        fixture.session().player().setPredictionFlag(EntityFlag.CRAWLING, false);
        prediction.inputData = Set.of(PlayerAuthInputData.STOP_SNEAKING);
        fixture.complete(SprintB.class);
        prediction.inputData = Set.of();
        fixture.complete(SprintB.class);
        assertEquals(List.of("B", "B", "B"), fixture.sprintAlerts());
    }

    @Test void itemSlowdownFlagsTheFirstSampleInOrOutOfWaterAndCleanSamplesDecay() {
        var fixture = Fixture.create();
        fixture.sprint(true);
        fixture.complete(SprintC.class);
        assertTrue(fixture.alerts().isEmpty());
        fixture.prediction().usingItem.snapshot(0.2F);
        fixture.complete(SprintC.class);
        fixture.prediction().fluidPrediction.wasInWater = true;
        fixture.prediction().fluidPrediction.waterTravel = true;
        for (int attempt = 0; attempt < 4; attempt++) fixture.complete(SprintC.class);
        assertEquals(List.of("C", "C", "C", "C", "C"), fixture.sprintAlerts());

        fixture.sprint(false);
        for (int attempt = 0; attempt < 100; attempt++) fixture.complete(SprintC.class);
        fixture.sprint(true);
        fixture.complete(SprintC.class);
        assertEquals(6, fixture.sprintAlerts().size());
        assertTrue(fixture.rewinds().isEmpty());
    }

    @Test void hungerAndBlindnessReachTheChecksOnlyAfterTheirClosingAck() {
        var fixture = Fixture.create();
        var session = fixture.session();
        var player = session.player();
        fixture.sprint(true);
        fixture.hunger(20F);
        player.getLatencyUtil().queue(77, true);
        session.observeBackend(hungerUpdate(6F));
        session.observeBackend(blindness(MobEffectPacket.Event.ADD));

        fixture.complete(SprintA.class);
        fixture.complete(SprintD.class);
        assertTrue(fixture.alerts().isEmpty());
        assertFalse(player.getLatencyUtil().onResponse(76));
        assertTrue(player.getLatencyUtil().onResponse(77));
        fixture.complete(SprintA.class);
        fixture.complete(SprintD.class);
        assertEquals(List.of("A", "D"), fixture.sprintAlerts());

        player.getLatencyUtil().queue(78, true);
        session.observeBackend(blindness(MobEffectPacket.Event.REMOVE));
        fixture.complete(SprintD.class);
        assertTrue(player.getLatencyUtil().onResponse(78));
        fixture.complete(SprintD.class);
        assertEquals(List.of("A", "D", "D"), fixture.sprintAlerts());
    }

    @Test void completedInputPreservesEligibilityAndOrderWhenAHandlerStartsCorrection() {
        var fixture = Fixture.create();
        var session = fixture.session();
        var player = session.player();
        fixture.sprint(true);
        player.setPredictionFlag(EntityFlag.SNEAKING, true);
        player.addEffect(NativeIds.Effect.BLINDNESS, 0, 100);
        for (int attempt = 0; attempt < 5; attempt++) {
            fixture.complete(SprintB.class);
            fixture.complete(SprintD.class);
        }
        fixture.alerts().clear();
        fixture.hunger(6F);
        var dispatched = new ArrayList<Float>();
        player.getCheckHolder().put(Prediction.class, new OffsetHandlerCheck(player, fixture.prediction()) {
            @Override public void onPredictionComplete(float offset) {
                if (isPredictionChecked()) dispatched.add(offset);
            }
        });
        player.setPredictionRewind(tick -> {
            fixture.rewinds().add(tick);
            if (fixture.rewinds().size() == 1) {
                session.teleports().queue(new TeleportData(session.teleports().getLastKnowValid()), ignored -> { });
            }
        });

        session.observeClient(AuthInputPacketsTest.input(10, 8, 64, 8, new Vec3(0, -0.0784F, 0)));

        assertEquals(List.of("A", "B", "D"), fixture.sprintAlerts());
        assertEquals(List.of(10L, 10L, 10L), fixture.rewinds());
        assertEquals(List.of(session.authInputPackets().lastResult().checkedOffset()), dispatched);
        assertTrue(session.teleports().isTeleporting());
        session.observeClient(AuthInputPacketsTest.input(11, 8, 64, 8, Vec3.ZERO));
        assertEquals(List.of("A", "B", "D"), fixture.sprintAlerts());
        assertEquals(3, fixture.rewinds().size());
    }

    @Test void disabledChecksAndMovementExemptionsDoNotDispatchSprintViolations() {
        var fixture = Fixture.create();
        var session = fixture.session();
        var player = session.player();
        fixture.hunger(6F);
        fixture.sprint(true);
        fixture.handler(SprintA.class).onPredictionComplete(0, false);
        player.acknowledgeAbilities(Set.of(Ability.MAY_FLY));
        session.observeClient(AuthInputPacketsTest.input(10, 8, 64, 8, Vec3.ZERO));
        player.acknowledgeAbilities(Set.of());
        player.inLoadingScreen = true;
        session.observeClient(AuthInputPacketsTest.input(11, 8, 64, 8, Vec3.ZERO));
        player.inLoadingScreen = false;
        player.setDisabledChecks(Set.of("Sprint-A", "Prediction"));
        session.observeClient(AuthInputPacketsTest.input(12, 8, 64, 8, Vec3.ZERO));
        assertTrue(fixture.sprintAlerts().isEmpty());
        assertTrue(fixture.rewinds().isEmpty());
        player.setDisabledChecks(Set.of("Prediction"));
        session.observeClient(AuthInputPacketsTest.input(13, 8, 64, 8, Vec3.ZERO));
        assertEquals(List.of("A"), fixture.sprintAlerts());
    }

    private static UpdateAttributesPacket hungerUpdate(float value) {
        var packet = new UpdateAttributesPacket();
        packet.setRuntimeEntityId(7);
        packet.getAttributes().add(new AttributeData("minecraft:player.hunger", 0, 20, value, 20));
        return packet;
    }

    private static MobEffectPacket blindness(MobEffectPacket.Event event) {
        var packet = new MobEffectPacket();
        packet.setRuntimeEntityId(7);
        packet.setEffectId(NativeIds.Effect.BLINDNESS);
        packet.setEvent(event);
        packet.setDuration(100);
        return packet;
    }

    private record Fixture(SessionState session, PredictionContext prediction, List<String> alerts, List<Long> rewinds) {
        static Fixture create() {
            var session = AuthInputPacketsTest.session();
            var alerts = new ArrayList<String>();
            var rewinds = new ArrayList<Long>();
            var prediction = session.authInputPackets().context();
            session.player().setAlertSink(alerts::add);
            session.player().setPredictionRewind(rewinds::add);
            return new Fixture(session, prediction, alerts, rewinds);
        }

        void sprint(boolean sprinting) {
            session.player().setServerSprinting(sprinting);
            session.player().snapshotPredictionMovementState(sprinting, false);
        }

        void hunger(float value) { session.player().apply(hungerUpdate(value)); }

        OffsetHandlerCheck handler(Class<? extends OffsetHandlerCheck> type) {
            return type.cast(session.player().getCheckHolder().get(type));
        }

        void complete(Class<? extends OffsetHandlerCheck> type) { handler(type).onPredictionComplete(0, true); }

        List<String> sprintAlerts() {
            return alerts.stream().filter(alert -> alert.contains("Sprint ("))
                    .map(alert -> alert.substring(alert.indexOf("Sprint (") + 8, alert.indexOf("Sprint (") + 9)).toList();
        }
    }
}
