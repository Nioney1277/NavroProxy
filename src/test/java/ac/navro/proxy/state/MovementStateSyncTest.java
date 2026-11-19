package ac.navro.proxy.state;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.SupportedCodecs;
import ac.navro.proxy.data.ItemUseTracker;
import ac.navro.proxy.util.EntityMetadataUtil;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.Ability;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.entity.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MovementStateSyncTest {
    static SessionState session() {
        var s=new SessionState();s.setProtocolVersion(2193);s.player().setProtocolVersion(2193);s.setDetectionEnabled(true);s.authInputPackets();
        s.player().setResolvedPosition(new Vec3(0,64,0),Box.EMPTY,false);return s;
    }
    static SetEntityDataPacket metadata(SessionState s,Set<Integer> bits,boolean base,boolean extended) {
        var p=new SetEntityDataPacket();p.setRuntimeEntityId(s.player().runtimeId());
        EntityMetadataUtil.writeFlags(p.getMetadata(),bits);
        p.getMetadata().getFlags().keySet().removeIf(f->{int id=EntityMetadataUtil.nativeFlagId(f);return id<64?!base:!extended;});return p;
    }
    static void acknowledge(SessionState s,SetEntityDataPacket p) {
        s.player().getLatencyUtil().queue(1000+s.player().latestPoseMetadataRevision(),true);s.observeBackend(p);
        assertTrue(s.player().getLatencyUtil().onOrderedResponse());
    }
    static PlayerAuthInputPacket input(long tick,float forward,PlayerAuthInputData...actions) {
        var p=new PlayerAuthInputPacket();p.setTick(tick);p.setPosition(Vector3f.from(0,64,0));p.setRotation(Vector3f.ZERO);p.setDelta(Vector3f.ZERO);
        p.setMotion(Vector2f.from(0,1));p.setRawMoveVector(Vector2f.from(0,forward));p.getInputData().addAll(List.of(actions));return p;
    }
    static void apply(SessionState s,PlayerAuthInputPacket p) {s.player().apply(p,null,null);}
    static ItemData elytra(int count) {return ItemData.builder().definition(new SimpleItemDefinition("minecraft:elytra",444,false)).count(count).build();}

    @Test void sprintUsesAcceptedEdgesAndPersistsWithoutTheContinuousBit() {
        var s=session();var w=s.player();apply(s,input(1,1,PlayerAuthInputData.START_SPRINTING));
        assertTrue(w.clientSprinting());assertTrue(w.serverSprinting());assertTrue(w.predictionSprinting());assertEquals(.13F,w.attributes().get("minecraft:movement").getValue());
        apply(s,input(2,1));assertTrue(w.clientSprinting());assertTrue(w.predictionFlag(EntityFlag.SPRINTING));
        apply(s,input(3,1,PlayerAuthInputData.STOP_SPRINTING,PlayerAuthInputData.SPRINTING));
        assertFalse(w.clientSprinting());assertFalse(w.serverSprinting());assertFalse(w.predictionSprinting());assertEquals(.1F,w.attributes().get("minecraft:movement").getValue());
        apply(s,input(4,1,PlayerAuthInputData.SPRINTING));assertFalse(w.clientSprinting());assertFalse(w.serverSprinting());
    }
    @Test void backwardSprintIsFilteredAndCannotLeaveTheClientFieldTrue() {
        var s=session();var p=input(1,-1,PlayerAuthInputData.START_SPRINTING,PlayerAuthInputData.SPRINTING);apply(s,p);
        assertFalse(p.getInputData().contains(PlayerAuthInputData.START_SPRINTING));assertFalse(s.player().clientSprinting());assertFalse(s.player().predictionFlag(EntityFlag.SPRINTING));
    }
    @Test void metadataWaitsForItsMarkerAndPreservesTheCurrentPredictionDomains() {
        var s=session();var w=s.player();apply(s,input(1,1,PlayerAuthInputData.START_SPRINTING,PlayerAuthInputData.START_SWIMMING));
        var p=metadata(s,Set.of(1),true,false);s.player().getLatencyUtil().queue(10,true);s.observeBackend(p);
        assertTrue(w.serverSprinting());assertTrue(w.clientSwimming());assertFalse(w.sneaking());
        assertFalse(w.getLatencyUtil().onResponse(999));assertTrue(w.serverSprinting());
        assertTrue(w.getLatencyUtil().onResponse(10));assertFalse(w.serverSprinting());assertFalse(w.clientSwimming());assertTrue(w.sneaking());
        assertTrue(w.clientSprinting());assertTrue(w.predictionSprinting());assertTrue(w.predictionSwimming());
        assertEquals(.13F,w.attributes().get("minecraft:movement").getValue());
        apply(s,input(2,1));assertFalse(w.predictionSprinting());assertFalse(w.predictionSwimming());
    }
    @Test void extendedOnlyAckRetainsBaseFlagsAndBaseOnlyAckRetainsCrawling() {
        var s=session();var w=s.player();acknowledge(s,metadata(s,Set.of(1,3,57),true,false));
        acknowledge(s,metadata(s,Set.of(114),false,true));
        assertTrue(w.sneaking());assertTrue(w.serverSprinting());assertTrue(w.clientSwimming());assertTrue(w.predictionFlag(EntityFlag.SWIMMING));assertTrue(w.clientCrawling());
        acknowledge(s,metadata(s,Set.of(),true,false));assertFalse(w.sneaking());assertFalse(w.clientSwimming());assertFalse(w.serverSprinting());assertTrue(w.clientCrawling());
        assertTrue(w.predictionFlag(EntityFlag.CRAWLING));assertTrue(w.predictionFlag(EntityFlag.CAN_CLIMB));
    }
    @Test void queuedPacketFlagsAreDetachedFromLaterPacketMutationsAndInputActions() {
        var s=session();var w=s.player();var p=metadata(s,Set.of(1,3,57),true,false);
        w.getLatencyUtil().queue(10,true);s.observeBackend(p);p.getMetadata().setFlag(EntityFlag.SNEAKING,false);p.getMetadata().setFlag(EntityFlag.SWIMMING,false);
        apply(s,input(1,1,PlayerAuthInputData.STOP_SNEAKING,PlayerAuthInputData.STOP_SWIMMING));w.getLatencyUtil().onResponse(10);
        assertTrue(w.sneaking());assertTrue(w.clientSwimming());assertTrue(w.serverSprinting());assertFalse(w.predictionSwimming());
    }
    @Test void eachQueuedSnapshotOwnsItsStateAndCurrentInputRemainsFrozen() {
        var s=session();var w=s.player();var first=metadata(s,Set.of(3,57),true,false);var second=metadata(s,Set.of(),true,false);
        w.getLatencyUtil().queue(10,true);s.observeBackend(first);w.getLatencyUtil().queue(11,true);s.observeBackend(second);
        w.getLatencyUtil().onResponse(10);assertTrue(w.serverSprinting());assertTrue(w.clientSwimming());apply(s,input(1,1));
        w.getLatencyUtil().onResponse(11);assertFalse(w.serverSprinting());assertFalse(w.clientSwimming());assertTrue(w.predictionSprinting());assertTrue(w.predictionSwimming());
        assertFalse(w.poseMetadataAwaitingAcknowledgment());assertEquals(2,w.acknowledgedPoseMetadataRevision());
    }
    @Test void swimAndCrawlActionsUpdateTheSameFlagsReadByPredictionAndPose() {
        var s=session();var w=s.player();apply(s,input(1,1,PlayerAuthInputData.START_SWIMMING,PlayerAuthInputData.START_CRAWLING));
        assertTrue(w.clientSwimming());assertTrue(w.predictionSwimming());assertTrue(w.predictionFlag(EntityFlag.SWIMMING));assertTrue(w.predictionFlag(EntityFlag.CRAWLING));
        assertTrue(s.authInputPackets().itemUse().hasFlag(57));assertTrue(s.authInputPackets().itemUse().hasFlag(114));
        apply(s,input(2,1,PlayerAuthInputData.STOP_SWIMMING,PlayerAuthInputData.STOP_CRAWLING));
        assertFalse(w.clientSwimming());assertFalse(w.predictionSwimming());assertFalse(w.predictionFlag(EntityFlag.SWIMMING));assertFalse(w.predictionFlag(EntityFlag.CRAWLING));
    }
    @Test void predictionSwimmingSetterCannotRewriteTheClientVisibleDomain() {
        var s=session();var w=s.player();w.setClientSwimming(true);w.setPredictionSwimming(false);
        assertTrue(w.clientSwimming());assertTrue(w.predictionFlag(EntityFlag.SWIMMING));assertFalse(w.predictionSwimming());
    }
    @Test void rejectedDrySwimmingRequestClearsBothTheClientFieldAndPredictionFlag() {
        var s=session();var w=s.player();w.apply(input(1,1,PlayerAuthInputData.START_SWIMMING),s.world(),s.fluidState());
        assertFalse(w.clientSwimming());assertFalse(w.predictionSwimming());assertFalse(w.predictionFlag(EntityFlag.SWIMMING));
    }
    @Test void glidingUsesCompensatedEquipmentAtBothActionAndMetadataApplicationTime() {
        var s=session();var w=s.player();var p=input(1,1,PlayerAuthInputData.START_GLIDING);apply(s,p);
        assertFalse(w.gliding());assertFalse(p.getInputData().contains(PlayerAuthInputData.START_GLIDING));
        s.inventory().armorContainer.set(1,elytra(1));apply(s,input(2,1,PlayerAuthInputData.START_GLIDING));assertTrue(w.gliding());
        s.inventory().armorContainer.set(1,elytra(0));acknowledge(s,metadata(s,Set.of(32),true,false));assertFalse(w.gliding());
        s.inventory().armorContainer.set(1,elytra(1));var meta=metadata(s,Set.of(32),true,false);w.getLatencyUtil().queue(10,true);s.observeBackend(meta);
        s.inventory().armorContainer.set(1,elytra(0));w.getLatencyUtil().onResponse(10);assertFalse(w.gliding());
    }
    @Test void unrelatedMetadataRunsTheOriginalItemHandshakeOnceWithoutClearingMovementFlags() {
        var s=session();var use=s.authInputPackets().itemUse();var w=s.player();
        acknowledge(s,metadata(s,Set.of(1,4,57),true,false));
        assertEquals(ItemUseTracker.DirtyUsing.METADATA,use.tracker().getDirtyUsing());assertFalse(use.usingItemFlag());assertTrue(w.hasFlag(4));
        acknowledge(s,metadata(s,Set.of(114),false,true));assertTrue(w.sneaking());assertTrue(w.clientSwimming());assertTrue(w.clientCrawling());
        assertFalse(use.usingItemFlag());assertEquals(ItemUseTracker.DirtyUsing.NONE,use.tracker().getDirtyUsing());
    }
    @Test void snapshotRestoreKeepsFrozenFlagsPoseAndDetachedPendingPackets() {
        var s=session();var w=s.player();apply(s,input(1,1,PlayerAuthInputData.START_SWIMMING));apply(s,input(2,1,PlayerAuthInputData.START_CRAWLING));
        var p=metadata(s,Set.of(1),true,false);w.apply(p);var saved=w.snapshot(null);
        assertTrue(saved.inputWasSwimming());assertThrows(UnsupportedOperationException.class,()->saved.trackedFlags().clear());
        p.getMetadata().setFlag(EntityFlag.SNEAKING,false);var pending=saved.pendingPoseMetadata().get(0);
        ((EnumMap<?,?>)pending.values().get(EntityDataTypes.FLAGS)).clear();pending.toEntityDataMap().getFlags().clear();
        w.setClientSwimming(false);w.setPredictionSwimming(false);w.setPredictionFlag(EntityFlag.CRAWLING,false);w.restore(saved,null);
        assertTrue(w.clientSwimming());assertTrue(w.predictionSwimming());assertTrue(w.clientCrawling());assertTrue(w.fluidPrediction().wasSwimming);
        w.acknowledgePoseMetadata(saved.poseMetadataRevision());assertTrue(w.sneaking());assertFalse(w.clientSwimming());assertTrue(w.predictionSwimming());
        w.restore(saved,null);w.acknowledgePoseMetadata(saved.poseMetadataRevision());assertTrue(w.sneaking());
    }
    @Test void flightActionsUseAcknowledgedAbilities() {
        var s=session();apply(s,input(1,1,PlayerAuthInputData.START_FLYING));assertFalse(s.player().flying());
        s.player().acknowledgeAbilities(Set.of(Ability.MAY_FLY));apply(s,input(2,1,PlayerAuthInputData.START_FLYING));assertTrue(s.player().flying());
        apply(s,input(3,1,PlayerAuthInputData.STOP_FLYING));assertFalse(s.player().flying());assertTrue(s.player().wasFlying());
    }
    @Test void diagnosticsShowQueuedAndAppliedStatesWithTheirOwnRevisionAndAck() {
        var s=session();var rows=new ArrayList<Map<String,Object>>();s.player().setWorldDiagnostics(new ac.navro.proxy.diagnostics.WorldCollisionDiagnostics(s.player(),s.world(),"state-test",rows::add));
        acknowledge(s,metadata(s,Set.of(1,3,57),true,false));
        assertEquals(List.of("movement_metadata_queued","movement_metadata_applied"),rows.stream().map(r->r.get("event")).toList());
        assertEquals(1L,rows.get(0).get("metadataRevision"));assertNull(rows.get(0).get("ackId"));assertEquals(1000L,rows.get(1).get("ackId"));
        assertFalse((boolean)((Map<?,?>)rows.get(0).get("movementState")).get("clientSwimming"));assertTrue((boolean)((Map<?,?>)rows.get(1).get("movementState")).get("clientSwimming"));
    }
    @Test void realCodecRoundTripsRetainFlagWordPresenceForEverySupportedProtocol() {
        for(int version:new int[]{729,776,786,800,819,860,898,924,944,975,1001,2168,2169,2192,2193,2225}) {
            for(boolean base:new boolean[]{true,false}) {
                var source=new EntityDataMap();



                for(EntityFlag flag:base?List.of(EntityFlag.SNEAKING,EntityFlag.SPRINTING,EntityFlag.GLIDING,EntityFlag.SWIMMING):List.of(EntityFlag.DESCEND_THROUGH_BLOCK,EntityFlag.CRAWLING))source.setFlag(flag,true);
                var codec=SupportedCodecs.forProtocol(version);var buffer=Unpooled.buffer();var decoded=new EntityDataMap();
                try {codec.createHelper().writeEntityData(buffer,source);codec.createHelper().readEntityData(buffer,decoded);}
                finally {buffer.release();}
                assertEquals(base,EntityMetadataUtil.hasBaseFlags(decoded),"protocol "+version);assertEquals(!base,EntityMetadataUtil.hasExtendedFlags(decoded),"protocol "+version);
                assertEquals(base?Set.of(1,3,32,57):Set.of(71,114),EntityMetadataUtil.copyFlags(decoded));
            }
        }
    }
}
