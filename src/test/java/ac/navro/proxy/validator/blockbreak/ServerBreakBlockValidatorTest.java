package ac.navro.proxy.validator.blockbreak;

import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.collision.CollisionPhysicsState;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.anticheat.util.math.Vec3;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerBreakBlockValidatorTest {
    private static final Vector3i FIRST=Vector3i.from(8,64,8),SECOND=Vector3i.from(9,64,8);
    private static final class Context implements ServerBreakBlockValidator.Context {
        final CompensatedWorld world=new CompensatedWorld(new CollisionPhysicsState(),()->100);
        final List<Vector3i> restored=new ArrayList<>();Vec3 position=new Vec3(8,64,8);GameType mode=GameType.SURVIVAL;
        Context(){this(2193);}
        Context(int protocol){world.setProtocolVersion(protocol);world.apply(new StartGamePacket());world.put(0,0,CompensatedWorld.CachedChunk.network(-64,320,24,world.airNetworkId()));}
        @Override public CompensatedWorld world(){return world;}
        @Override public Vec3 position(){return position;}
        @Override public GameType gameType(){return mode;}
        @Override public String name(){return "fixture";}
        @Override public void restoreCorrectBlock(Vector3i position){restored.add(position);}
    }
    private static PlayerBlockActionData action(PlayerActionType type,Vector3i p,int face) {
        var a=new PlayerBlockActionData();a.setAction(type);a.setBlockPosition(p);a.setFace(face);return a;
    }
    private static PlayerAuthInputPacket packet(PlayerBlockActionData... data) {
        var p=new PlayerAuthInputPacket();p.setPosition(Vector3f.from(500,500,500));p.setRotation(Vector3f.ZERO);
        p.getInputData().add(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS);p.getPlayerActions().addAll(Arrays.asList(data));return p;
    }
    @Test void defaultContextUsesNativeHardnessAndOnlyTheExactCreativeModeException() {
        var c=new Context();var validator=new ServerBreakBlockValidator(c);c.world.updateLegacyBlock(FIRST,0,7<<13);
        c.mode=GameType.CREATIVE_VIEWER;
        var denied=packet(action(PlayerActionType.START_BREAK,FIRST,1));validator.handle(denied);
        assertTrue(denied.getPlayerActions().isEmpty());assertEquals(List.of(FIRST),c.restored);
        c.mode=GameType.CREATIVE;
        var accepted=packet(action(PlayerActionType.START_BREAK,FIRST,1),action(PlayerActionType.BLOCK_PREDICT_DESTROY,FIRST,1));
        validator.handle(accepted);assertEquals(2,accepted.getPlayerActions().size());assertTrue(c.world.isPredictedBlockBreak(FIRST));
        assertTrue(c.world.hadRecentPlacementSupport(FIRST));
    }
    @Test void protocol2225UsesExportedHardnessDuringActualBlockActions() {
        var c=new Context(2225);var validator=new ServerBreakBlockValidator(c);
        var registry=ac.navro.proxy.data.block.NetworkBlockStateRegistry.forProtocol(2225,false);
        var bedrock=registry.states().values().stream().filter(s->s.is("minecraft:bedrock")).findFirst().orElseThrow();
        c.world.updateBlock(FIRST.getX(),FIRST.getY(),FIRST.getZ(),0,bedrock.networkId());
        assertEquals(-1,c.blockState(FIRST,0).hardness());
        var denied=packet(action(PlayerActionType.START_BREAK,FIRST,1));validator.handle(denied);
        assertTrue(denied.getPlayerActions().isEmpty());
        var stone=registry.states().values().stream().filter(s->s.is("minecraft:stone")).findFirst().orElseThrow();
        c.world.updateBlock(FIRST.getX(),FIRST.getY(),FIRST.getZ(),0,stone.networkId());
        assertEquals(1.5,c.blockState(FIRST,0).hardness());
        var accepted=packet(action(PlayerActionType.START_BREAK,FIRST,1),action(PlayerActionType.BLOCK_PREDICT_DESTROY,FIRST,1));
        validator.handle(accepted);assertEquals(2,accepted.getPlayerActions().size());
        assertTrue(c.world.isPredictedBlockBreak(FIRST));
    }
    @Test void actualPaletteDoorHalvesAndConsecutiveTargetsRetainOrderedPredictions() {
        var c=new Context();var validator=new ServerBreakBlockValidator(c);var upper=FIRST.add(0,1,0);
        c.world.updateLegacyBlock(FIRST,0,64<<13);c.world.updateLegacyBlock(upper,0,(64<<13)|8);c.world.updateLegacyBlock(SECOND,0,1<<13);
        var input=packet(action(PlayerActionType.START_BREAK,FIRST,1),action(PlayerActionType.BLOCK_PREDICT_DESTROY,FIRST,1),
                action(PlayerActionType.START_BREAK,SECOND,1),action(PlayerActionType.BLOCK_PREDICT_DESTROY,SECOND,1));
        validator.handle(input);assertEquals(4,input.getPlayerActions().size());
        for(var p:List.of(FIRST,upper,SECOND))assertTrue(c.world.isPredictedBlockBreak(p));
        assertTrue(c.restored.isEmpty());
        var duplicate=packet(action(PlayerActionType.BLOCK_CONTINUE_DESTROY,FIRST,1),action(PlayerActionType.BLOCK_PREDICT_DESTROY,upper,1));
        validator.handle(duplicate);assertTrue(duplicate.getPlayerActions().isEmpty());assertTrue(c.restored.isEmpty());
    }
    @Test void cancellationPrecedesBlockFaceAndDistanceChecksAndPositionUsesNativeFloor() {
        var c=new Context();var validator=new ServerBreakBlockValidator(c);c.world.updateLegacyBlock(FIRST,0,1<<13);
        validator.handle(packet(action(PlayerActionType.START_BREAK,FIRST,1)));c.world.updateLegacyBlock(FIRST,0,0);c.position=new Vec3(500,500,500);
        var abort=packet(action(PlayerActionType.ABORT_BREAK,FIRST,Integer.MIN_VALUE));validator.handle(abort);
        assertEquals(1,abort.getPlayerActions().size());assertTrue(c.restored.isEmpty());
        c.position=new Vec3(-0.1F,64,8);var edge=Vector3i.from(12,64,8);c.world.updateLegacyBlock(edge,0,1<<13);
        var far=packet(action(PlayerActionType.START_BREAK,edge,1));validator.handle(far);
        assertTrue(far.getPlayerActions().isEmpty());assertEquals(List.of(edge),c.restored);
    }
}
