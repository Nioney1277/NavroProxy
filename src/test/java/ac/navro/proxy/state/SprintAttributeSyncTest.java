package ac.navro.proxy.state;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.diagnostics.WorldCollisionDiagnostics;
import ac.navro.anticheat.data.vanilla.AttributeInstance;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SprintAttributeSyncTest {
    static final String MOVEMENT="minecraft:movement";
    static UpdateAttributesPacket attributes(SessionState s,float base,float value) {
        var p=new UpdateAttributesPacket();p.setRuntimeEntityId(s.player().runtimeId());
        p.getAttributes().add(new AttributeData(MOVEMENT,0,1024,value,base));return p;
    }
    static void input(SessionState s,long tick,PlayerAuthInputData... actions) {
        MovementStateSyncTest.apply(s,MovementStateSyncTest.input(tick,1,actions));
    }
    static AttributeInstance speed(SessionState s){return s.player().attributes().get(MOVEMENT);}

    @Test void sprintAttributeAckClearsLocalModifierBeforeStopWithoutRecomputingTheWireValue() {
        var s=MovementStateSyncTest.session();input(s,1,PlayerAuthInputData.START_SPRINTING);
        s.player().getLatencyUtil().queue(10,true);s.observeBackend(attributes(s,.1F,.13F));
        assertEquals(1,speed(s).getModifiers().size());assertFalse(s.player().getLatencyUtil().onResponse(999));
        assertTrue(s.player().getLatencyUtil().onResponse(10));assertTrue(speed(s).getModifiers().isEmpty());
        input(s,2,PlayerAuthInputData.STOP_SPRINTING);
        assertFalse(s.player().predictionSprinting());assertEquals(.13F,speed(s).getValue());

        s.player().getLatencyUtil().queue(11,true);s.observeBackend(attributes(s,.1F,.1F));
        assertEquals(.13F,speed(s).getValue());s.player().getLatencyUtil().onResponse(11);
        assertEquals(.1F,speed(s).getValue());
    }
    @Test void lateStopAckThenImmediateStartAddsExactlyOneBoost() {
        var s=MovementStateSyncTest.session();input(s,1,PlayerAuthInputData.START_SPRINTING);
        s.player().getLatencyUtil().queue(10,true);s.observeBackend(attributes(s,.1F,.1F));
        input(s,2,PlayerAuthInputData.STOP_SPRINTING);input(s,3,PlayerAuthInputData.START_SPRINTING);
        s.player().getLatencyUtil().onResponse(10);assertEquals(.1F,speed(s).getValue());
        assertTrue(s.player().clientSprinting());assertTrue(speed(s).getModifiers().isEmpty());
        input(s,4,PlayerAuthInputData.START_SPRINTING);assertEquals(.13F,speed(s).getValue());
        assertEquals(1,speed(s).getModifiers().size());
    }
    @Test void queuedAttributesRetainListEntityAndTickWhenTheWirePacketIsReused() {
        var s=MovementStateSyncTest.session();var p=attributes(s,.2F,.26F);p.setTick(123);
        s.player().getLatencyUtil().queue(10,true);s.observeBackend(p);
        p.getAttributes().clear();p.getAttributes().add(new AttributeData(MOVEMENT,0,1024,9,9));p.setRuntimeEntityId(999);p.setTick(999);
        assertEquals(.1F,speed(s).getValue());s.player().getLatencyUtil().onResponse(10);
        assertEquals(.2F,speed(s).getBaseValue());assertEquals(.26F,speed(s).getValue());
    }
    @Test void foreignEntityAttributesDoNotClearThePlayersSprintModifier() {
        var s=MovementStateSyncTest.session();input(s,1,PlayerAuthInputData.START_SPRINTING);
        var start=new org.cloudburstmc.protocol.bedrock.packet.StartGamePacket();
        start.setRuntimeEntityId(7);start.setPlayerPosition(org.cloudburstmc.math.vector.Vector3f.ZERO);s.observeBackend(start);
        var p=attributes(s,.5F,.5F);p.setRuntimeEntityId(8);
        s.player().getLatencyUtil().queue(10,true);s.observeBackend(p);s.player().getLatencyUtil().onResponse(10);
        assertEquals(.13F,speed(s).getValue());assertEquals(1,speed(s).getModifiers().size());
    }
    @Test void attributeAndActionDiagnosticsCaptureDetachedSpeedAndAppliedAck() {
        var s=MovementStateSyncTest.session();var rows=new ArrayList<Map<String,Object>>();
        s.player().setWorldDiagnostics(new WorldCollisionDiagnostics(s.player(),s.world(),"sprint-test",rows::add));
        input(s,1,PlayerAuthInputData.START_SPRINTING);s.player().getLatencyUtil().queue(10,true);
        var p=attributes(s,.1F,.13F);p.setTick(50);s.observeBackend(p);s.player().getLatencyUtil().onResponse(10);
        input(s,2,PlayerAuthInputData.STOP_SPRINTING);
        assertEquals(List.of("sprint_action","movement_attributes_queued","movement_attributes_applied","sprint_action"),rows.stream().map(r->r.get("event")).toList());
        assertEquals(10L,rows.get(2).get("ackId"));assertEquals(50L,rows.get(2).get("packetTick"));
        var queued=(Map<?,?>)((Map<?,?>)rows.get(1).get("movementState")).get("movementAttribute");
        var applied=(Map<?,?>)((Map<?,?>)rows.get(2).get("movementState")).get("movementAttribute");
        assertEquals(1,((Map<?,?>)queued.get("modifiers")).size());assertTrue(((Map<?,?>)applied.get("modifiers")).isEmpty());
        assertThrows(UnsupportedOperationException.class,()->((Map<?,?>)queued.get("modifiers")).clear());
    }
    @Test void failingDiagnosticSinkDoesNotChangeSprintOrAcknowledgedSpeed() {
        var s=MovementStateSyncTest.session();s.player().setWorldDiagnostics(new WorldCollisionDiagnostics(s.player(),s.world(),"failed",row->{throw new IllegalStateException("sink");}));
        input(s,1,PlayerAuthInputData.START_SPRINTING);s.player().getLatencyUtil().queue(10,true);
        s.observeBackend(attributes(s,.1F,.13F));s.player().getLatencyUtil().onResponse(10);
        input(s,2,PlayerAuthInputData.STOP_SPRINTING);assertEquals(.13F,speed(s).getValue());assertFalse(s.player().clientSprinting());
    }
}
