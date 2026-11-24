package ac.navro.proxy.packets.input;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.data.input.TickData;
import ac.navro.proxy.prediction.RiptidePhysics;
import ac.navro.proxy.teleport.data.CorrectionResetData;
import ac.navro.proxy.teleport.data.RewindHistory;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PostAuthInputPacketsTest {
    private static SessionState session() {
        var s=AuthInputPacketsTest.session();s.observeClient(input(10,8,64));return s;
    }
    private static PlayerAuthInputPacket input(long tick,float x,float y) {
        return AuthInputPacketsTest.input(tick,x,y,8,new Vec3(0,-.0784F,0));
    }
    private static boolean postInput(SessionState s,PlayerAuthInputPacket packet,boolean cancelled) {
        s.player().advanceInputClock(packet);
        return PostAuthInputPackets.onPacket(s,packet,cancelled);
    }
    static class FrozenTimer extends ac.navro.proxy.check.impl.timer.Timer {
        FrozenTimer(ac.navro.proxy.state.WirePlayerState player) { super(player,()->1_000_000_000L,ignored->{}); }
    }
    @Test void historyPrecedesRewriteAndResetAndDoesNotAliasTheLivePacket() {
        var s=session();var p=s.authInputPackets().context();p.thisTickRiptideLevel=7;
        p.thisTickSpinAttack=p.thisTickOnGroundSpinAttack=p.hasDepthStrider=true;
        p.riptideTickStartState=RiptidePhysics.snapshot(p);s.player().doingInventoryAction=true;s.player().tickSinceBlockResync=5;
        var packet=input(11,80,300);packet.getInputData().add(PlayerAuthInputData.HORIZONTAL_COLLISION);
        assertTrue(postInput(s,packet,false));var saved=s.teleports().getAuthInputHistory().get(11L);
        assertEquals(80,saved.packet().getPosition().getX());assertEquals(8,packet.getPosition().getX());
        assertTrue(saved.state().doingInventoryAction());assertFalse(s.player().doingInventoryAction);
        assertNotNull(saved.riptideStartState());assertNull(p.riptideTickStartState);assertEquals(0,p.thisTickRiptideLevel);
        assertFalse(p.thisTickSpinAttack);assertFalse(p.thisTickOnGroundSpinAttack);assertFalse(p.hasDepthStrider);
        assertEquals(4,s.player().tickSinceBlockResync);assertTrue(saved.packet().getInputData().contains(PlayerAuthInputData.HORIZONTAL_COLLISION));
        assertFalse(packet.getInputData().contains(PlayerAuthInputData.HORIZONTAL_COLLISION));
        packet.getInputData().clear();packet.setPosition(Vector3f.ZERO);packet.setTick(99);
        assertEquals(11,saved.packet().getTick());assertEquals(80,saved.packet().getPosition().getX());
        assertTrue(saved.packet().getInputData().contains(PlayerAuthInputData.HORIZONTAL_COLLISION));
    }
    @Test void resyncExpiresAfterFiveCompletedInputsAndDetectionResumesOnTheSixth() {
        var s=session();var alerts=new ArrayList<String>();s.player().setAlertSink(alerts::add);s.player().tickSinceBlockResync=5;
        for(int tick=11;tick<=15;tick++){
            s.observeClient(input(tick,80,64));assertEquals(15-tick,s.player().tickSinceBlockResync);assertTrue(alerts.isEmpty(),alerts.toString());
        }
        s.observeClient(input(16,80,64));assertFalse(alerts.isEmpty());assertEquals(0,s.player().tickSinceBlockResync);
    }
    @Test void hardTeleportBlocksForwardingUntilItsActualConfirmationAndPausesResync() {
        var s=session();var moves=AuthInputPacketsTest.transport(s);s.player().tickSinceBlockResync=5;s.rewindPrediction(10);
        var wrong=input(11,80,64);assertFalse(s.receiveClient(wrong,8500));assertEquals(5,s.player().tickSinceBlockResync);
        assertEquals(8,wrong.getPosition().getX());assertTrue(s.teleports().isHardTeleporting());
        assertTrue(s.player().getLatencyUtil().onResponse(1000));
        var confirmation=input(12,8,64);confirmation.setPosition(moves.get(0).getPosition());confirmation.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);
        assertTrue(s.receiveClient(confirmation,8500));assertFalse(s.teleports().isHardTeleporting());assertEquals(4,s.player().tickSinceBlockResync);
    }
    @Test void correctionStartedByThisPredictionCannotLeakItsOffendingMovementToBackend() {
        var s=session();AuthInputPacketsTest.transport(s);var spoof=input(11,80,64);
        assertFalse(s.receiveClient(spoof,8500));assertEquals(8,spoof.getPosition().getX());assertTrue(s.teleports().isHardTeleporting());
        assertEquals(80,s.teleports().getAuthInputHistory().get(11L).packet().getPosition().getX());
    }
    @Test void alreadyRejectedInputStillRunsTheOriginalPostListenerWithoutApplyingRawMovement() {
        var s=session();s.player().tickSinceBlockResync=2;s.player().doingInventoryAction=true;
        s.player().sinceLoadingScreen=200;
        var timer=new FrozenTimer(s.player());
        for(int i=0;i<3;i++)assertFalse(timer.isInvalid());
        s.player().getCheckHolder().put(ac.navro.proxy.check.impl.timer.Timer.class,timer);
        var duplicate=input(10,80,300);assertFalse(s.receiveClient(duplicate,8500));
        assertEquals("Timer",s.anomalies().getLast().kind());
        assertEquals(11,s.player().tick());assertEquals(8,s.player().position().x);assertEquals(1,s.player().tickSinceBlockResync);
        assertFalse(s.player().doingInventoryAction);assertEquals(8,s.teleports().getAuthInputHistory().get(10L).packet().getPosition().getX());
        assertFalse(s.teleports().getAuthInputHistory().containsKey(11L));
        assertEquals(8,duplicate.getPosition().getX());
    }
    @Test void preInitializationInputIsCancelledBeforeHistoryRewriteOrTickReset() {
        var s=new SessionState();s.setProtocolVersion(2168);s.player().setProtocolVersion(2168);
        var start=new StartGamePacket();start.setPlayerPosition(Vector3f.from(8,64,8));s.observeBackend(start);s.setDetectionEnabled(true);
        s.player().doingInventoryAction=true;s.player().tickSinceBlockResync=5;var packet=input(0,80,300);var raw=packet.getPosition();
        assertFalse(s.receiveClient(packet,8500));assertEquals(raw,packet.getPosition());assertTrue(s.player().doingInventoryAction);
        assertEquals(5,s.player().tickSinceBlockResync);assertTrue(s.teleports().getAuthInputHistory().isEmpty());
    }
    @Test void exemptionLeavesTheOriginalPostDomainsUntouched() {
        var s=session();s.teleports().getAuthInputHistory().clear();s.player().setExempted(true);
        s.player().doingInventoryAction=true;s.player().tickSinceBlockResync=5;var packet=input(11,80,300);var raw=packet.getPosition();
        assertTrue(postInput(s,packet,false));assertFalse(postInput(s,packet,true));
        assertEquals(raw,packet.getPosition());assertTrue(s.player().doingInventoryAction);assertEquals(5,s.player().tickSinceBlockResync);
        assertTrue(s.teleports().getAuthInputHistory().isEmpty());
    }
    @Test void movementExemptionPreservesDeltaAndCollisionFlagsWhileRestoringPacketSpacePosition() {
        var s=session();s.player().setFlying(true);var packet=input(11,80,300);packet.setDelta(Vector3f.from(1,2,3));
        packet.getInputData().clear();packet.getInputData().add(PlayerAuthInputData.HORIZONTAL_COLLISION);
        assertTrue(postInput(s,packet,false));assertEquals(Vector3f.from(1,2,3),packet.getDelta());
        assertEquals(Set.of(PlayerAuthInputData.HORIZONTAL_COLLISION),packet.getInputData());assertEquals(Vector3f.from(8,65.62001F,8),packet.getPosition());
    }
    @Test void inspectionModeKeepsObservationForwardingAndDoesNotCreateNativeInputHistory() {
        var s=session();s.teleports().getAuthInputHistory().clear();s.setDetectionEnabled(false);s.player().tickSinceBlockResync=5;
        var packet=input(11,80,300);var raw=packet.getPosition();assertTrue(s.receiveClient(packet,8500));
        assertEquals(raw,packet.getPosition());assertEquals(5,s.player().tickSinceBlockResync);assertTrue(s.teleports().getAuthInputHistory().isEmpty());
    }
    @Test void historyUsesNativeSortedTrimmingAndDoesNotTrimDuringPendingTeleport() {
        var s=session();for(int tick=11;tick<=40;tick++)postInput(s,input(tick,8,64),false);
        assertEquals(20,s.teleports().getAuthInputHistory().size());assertEquals(21L,s.teleports().getAuthInputHistory().firstKey());
        var frame=RewindHistory.capture(s.authInputPackets().context(),10,s.playerYOffset());
        s.teleports().getQueuedTeleports().add(new CorrectionResetData(frame));
        for(int tick=41;tick<=45;tick++)assertFalse(postInput(s,input(tick,8,64),false));
        assertEquals(25,s.teleports().getAuthInputHistory().size());
        s.teleports().getQueuedTeleports().clear();postInput(s,input(46,8,64),false);
        assertEquals(20,s.teleports().getAuthInputHistory().size());assertEquals(27L,s.teleports().getAuthInputHistory().firstKey());
    }
    @Test void snapshotCopiesExactlyTheOriginalMovementFieldsWithoutRetainingActionPayloads() {
        var source=input(32,8,64);source.setCameraOrientation(Vector3f.from(1,2,3));source.setRawMoveVector(Vector2f.from(.2,.4));
        source.setInteractRotation(Vector2f.from(.3,.5));source.setInputInteractionModel(InputInteractionModel.CROSSHAIR);
        source.setPlayMode(ClientPlayMode.NORMAL);source.setPredictedVehicle(99);
        var action=new PlayerBlockActionData();action.setAction(PlayerActionType.START_BREAK);action.setBlockPosition(Vector3i.from(8,63,8));source.getPlayerActions().add(action);
        var snapshot=TickData.snapshotPacket(source);
        assertEquals(source.getRotation(),snapshot.getRotation());assertEquals(source.getDelta(),snapshot.getDelta());assertEquals(source.getMotion(),snapshot.getMotion());
        assertEquals(source.getInputInteractionModel(),snapshot.getInputInteractionModel());assertEquals(source.getCameraOrientation(),snapshot.getCameraOrientation());
        assertEquals(source.getRawMoveVector(),snapshot.getRawMoveVector());assertEquals(source.getInteractRotation(),snapshot.getInteractRotation());
        assertTrue(snapshot.getPlayerActions().isEmpty());assertEquals(0,snapshot.getPredictedVehicle());
    }
}
