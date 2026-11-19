package ac.navro.proxy.packets.player;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static ac.navro.proxy.packets.player.PlayerEffectReferenceTest.*;

class PlayerEffectLifecycleTest {
    static void send(SessionState s,long marker,MobEffectPacket packet) {
        s.player().getLatencyUtil().queue(marker,true);s.observeBackend(packet);
    }
    static void input(SessionState s,long tick) {
        s.observeClient(AuthInputPacketsTest.input(tick,8,64,8,Vec3.ZERO));
    }
    @Test void addModifyAndRemoveUseTheirOwnAcknowledgmentsAndCaptureTheOriginalPacketFields() {
        var s=session();var w=s.player();var p=packet(7,MobEffectPacket.Event.ADD,8,2,3);
        send(s,10,p);assertFalse(w.hasEffect(8));
        p.setEffectId(1);p.setAmplifier(99);p.setDuration(100);p.setRuntimeEntityId(99);p.setEvent(MobEffectPacket.Event.REMOVE);
        assertFalse(w.getLatencyUtil().onResponse(999));assertFalse(w.hasEffect(8));
        assertTrue(w.getLatencyUtil().onResponse(10));assertEquals(2,w.effect(8).amplifier());assertEquals(4,w.effect(8).duration());
        send(s,11,packet(7,MobEffectPacket.Event.MODIFY,8,4,8));
        send(s,12,packet(7,MobEffectPacket.Event.REMOVE,8,0,0));assertEquals(2,w.effect(8).amplifier());
        assertTrue(w.getLatencyUtil().onResponse(11));assertEquals(4,w.effect(8).amplifier());assertEquals(9,w.effect(8).duration());
        assertFalse(w.getLatencyUtil().onResponse(11));assertTrue(w.hasEffect(8));
        assertTrue(w.getLatencyUtil().onResponse(12));assertFalse(w.hasEffect(8));
    }
    @Test void nativeEffectAvailabilityIncludesZeroAndLastRegisteredIdButRejectsUnregisteredIds() {
        var s=session();var w=s.player();
        for(int id:List.of(0,36,37,255,256,-1,Integer.MAX_VALUE)) {
            send(s,10+id,packet(7,MobEffectPacket.Event.ADD,id,1,20));
            assertTrue(w.getLatencyUtil().onResponse(10+id));
        }
        assertEquals(java.util.Set.of(0,36),w.effects().keySet());
        send(s,100,packet(7,MobEffectPacket.Event.NONE,0,99,99));w.getLatencyUtil().onResponse(100);
        assertEquals(1,w.effect(0).amplifier());assertEquals(21,w.effect(0).duration());
    }
    @Test void effectTargetIsCheckedAtEnqueueAndForeignPacketsCannotBecomeLocalBeforeAck() {
        var s=session();var w=s.player();
        send(s,10,packet(99,MobEffectPacket.Event.ADD,8,1,4));
        var start=new StartGamePacket();start.setRuntimeEntityId(99);s.observeBackend(start);
        w.getLatencyUtil().onResponse(10);assertFalse(w.hasEffect(8));
        send(s,11,packet(99,MobEffectPacket.Event.ADD,8,2,4));
        start=new StartGamePacket();start.setRuntimeEntityId(7);s.observeBackend(start);
        w.getLatencyUtil().onResponse(11);assertEquals(2,w.effect(8).amplifier());
    }
    @Test void finiteEffectsExpireBeforeThePredictionOfTheirLastInput() {
        var s=AuthInputPacketsTest.session();var w=s.player();
        send(s,10,packet(7,MobEffectPacket.Event.ADD,24,2,0));w.getLatencyUtil().onResponse(10);
        assertEquals(1,w.effect(24).duration());input(s,1);assertFalse(w.hasEffect(24));
        assertEquals(-.0784F,w.velocity().y);assertFalse(s.authInputPackets().context().hasEffect(24));
        send(s,11,packet(7,MobEffectPacket.Event.ADD,28,0,1));w.getLatencyUtil().onResponse(11);
        input(s,2);assertEquals(1,w.effect(28).duration());
        input(s,3);assertFalse(w.hasEffect(28));
    }
    @Test void negativeAndMaxDurationsRetainTheOriginalMaxValueAndTickBehavior() {
        var s=AuthInputPacketsTest.session();var w=s.player();
        send(s,10,packet(7,MobEffectPacket.Event.ADD,1,0,Integer.MIN_VALUE));
        send(s,11,packet(7,MobEffectPacket.Event.ADD,2,0,Integer.MAX_VALUE));
        send(s,12,packet(7,MobEffectPacket.Event.ADD,8,0,Integer.MAX_VALUE-1));
        w.getLatencyUtil().onResponse(12);
        for(int id:List.of(1,2,8))assertEquals(Integer.MAX_VALUE,w.effect(id).duration());
        input(s,1);for(int id:List.of(1,2,8))assertEquals(Integer.MAX_VALUE-1,w.effect(id).duration());
    }
    @Test void unspawnedExemptAndRejectedInputsDoNotAgeEffects() {
        var s=session();var w=s.player();send(s,10,packet(7,MobEffectPacket.Event.ADD,1,0,2));w.getLatencyUtil().onResponse(10);
        input(s,1);assertEquals(3,w.effect(1).duration());
        s.observeClient(new SetLocalPlayerAsInitializedPacket());w.setExempted(true);
        input(s,2);assertEquals(3,w.effect(1).duration());
        w.setExempted(false);
        assertFalse(s.receiveClient(AuthInputPacketsTest.input(-1,8,64,8,Vec3.ZERO),200));assertEquals(3,w.effect(1).duration());
    }
    @Test void speedEffectsDoNotApplyOrStripSprintModifiersAndAttributesKeepTheirOwnAck() {
        var s=session();var w=s.player();w.setPredictionSprintingAction(true);var speed=w.attributes().get("minecraft:movement");
        send(s,10,packet(7,MobEffectPacket.Event.ADD,1,3,20));w.getLatencyUtil().onResponse(10);
        assertEquals(.13F,speed.getValue());assertEquals(1,speed.getModifiers().size());
        var attributes=new UpdateAttributesPacket();attributes.setRuntimeEntityId(7);
        attributes.getAttributes().add(new AttributeData("minecraft:movement",0,1024,.234F,.1F));
        w.getLatencyUtil().queue(11,true);s.observeBackend(attributes);
        assertEquals(.13F,speed.getValue());w.getLatencyUtil().onResponse(11);
        assertEquals(.234F,speed.getValue());assertTrue(speed.getModifiers().isEmpty());
        send(s,12,packet(7,MobEffectPacket.Event.REMOVE,1,0,0));w.getLatencyUtil().onResponse(12);
        assertFalse(w.hasEffect(1));assertEquals(.234F,speed.getValue());assertTrue(speed.getModifiers().isEmpty());
    }
    @Test void inspectionKeepsRawWireDurationsAndIdsWithoutApplyingPredictionRules() {
        var s=session();s.setDetectionEnabled(false);
        s.observeBackend(packet(7,MobEffectPacket.Event.ADD,999,2,-1));
        assertEquals(-1,s.player().effect(999).duration());assertEquals(2,s.player().effect(999).amplifier());
    }
}
