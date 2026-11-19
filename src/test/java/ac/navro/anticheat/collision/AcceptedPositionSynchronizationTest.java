package ac.navro.anticheat.collision;

import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcceptedPositionSynchronizationTest {
    private static void set(WirePlayerState player, String name, Object value) {
        try { var field=WirePlayerState.class.getDeclaredField(name);field.setAccessible(true);field.set(player,value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static Vec3 vector(JsonElement e) {
        var a=e.getAsJsonArray();return new Vec3(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat());
    }
    private static Box box(JsonElement e) {
        var a=e.getAsJsonArray();return new Box(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat(),
                a.get(3).getAsFloat(),a.get(4).getAsFloat(),a.get(5).getAsFloat());
    }

    @Test void recordedFreeMovementAndLongStationaryTailDoNotRetainAcceptedPositionBias() throws Exception {
        final JsonArray frames;
        try(var reader=new InputStreamReader(getClass().getResourceAsStream("/accepted-position-small-offset-sequence.json"),StandardCharsets.UTF_8)) {
            frames=JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("frames");
        }
        assertEquals(27,frames.size());
        var first=frames.get(0).getAsJsonObject();var player=new WirePlayerState();
        player.applyDimensions(EntityDimensions.fixed(0.58F,1.8F));
        player.setResolvedPosition(vector(first.get("initialFoot")),box(first.get("initialBox")),false);
        long revision=player.aabbResizeRevision();
        for(int i=0;i<frames.size()+1246;i++) {
            var row=frames.get(Math.min(i,frames.size()-1)).getAsJsonObject();
            var shapes=row.getAsJsonArray("colliders").asList().stream().map(AcceptedPositionSynchronizationTest::box).toList();
            var move=ProtocolCollisionSolver.solveResolved(vector(row.get("requested")),player.boundingBox(),shapes,
                    vector(row.get("maxDepenetration")),row.get("stepHeight").getAsFloat(),row.get("canStep").getAsBoolean());
            player.setResolvedMovement(move.aabb,true);
            var client=vector(row.get("clientFoot"));float offset=player.position().distanceTo(client);
            assertEquals(i==0?3.0517578E-5F:0,offset,"continuous input "+i);
            player.acceptInputPosition(client,shapes);
            assertEquals(0,player.boundingBox().feetCenter().distanceTo(client));
            assertEquals(revision,player.aabbResizeRevision(),"Acceptance must not reconstruct nominal dimensions");
            assertTrue(shapes.stream().noneMatch(player.boundingBox()::intersects));
        }
        var saved=player.snapshotAabb();Box accepted=player.boundingBox();
        player.acceptPosition(new Vec3(0,0,0));player.restoreAabb(saved,saved.position());
        assertTrue(accepted.equals(player.boundingBox()));
        assertEquals(0,player.position().distanceTo(player.boundingBox().feetCenter()));
    }

    @Test void headContactStopsAcceptanceWithoutReplacingTheActualHeightOrMotion() {
        var player=new WirePlayerState();Box ceiling=new Box(-2,64,-2,2,65,2);
        Box actual=new Box(-0.29F,62.200126F,-0.29F,0.29F,64,0.29F);
        player.setResolvedPosition(actual.feetCenter(),actual,false);
        set(player,"velocity",new Vec3(0,-0.0784F,0));set(player,"verticalCollision",true);set(player,"onGround",true);
        Vec3 target=player.position().up(Math.ulp(player.position().y));
        player.acceptInputPosition(target,List.of(ceiling));
        assertTrue(actual.equals(player.boundingBox()));assertEquals(actual.minY,player.position().y);
        assertEquals(64,player.boundingBox().maxY);assertFalse(player.boundingBox().intersects(ceiling));
        assertEquals(-0.0784F,player.velocity().y);assertTrue(player.verticalCollision());assertTrue(player.onGround());
    }

    @Test void existingOverlapCannotBeDeepenedButIndependentTangentialSynchronizationStillApplies() {
        var player=new WirePlayerState();Box wall=new Box(1,0,-2,2,3,2);
        Box before=new Box(0.421F,0,0,1.001F,1.8F,0.58F);
        player.setResolvedPosition(before.feetCenter(),before,false);
        set(player,"collisionPenetratingFrames",2);
        Vec3 target=player.position().add(0.00005F,0,0.00005F);
        var unchecked=SweptMovement.computeYXZMoveWithDepenetration(target.subtract(before.feetCenter()),before,List.of(wall),Vec3.ZERO);
        assertTrue(unchecked.aabb.maxX>before.maxX,"Zero-budget overlap movement is not a safe position rebase by itself");
        player.acceptInputPosition(target,List.of(wall));
        assertEquals(before.maxX,player.boundingBox().maxX);assertEquals(before.minX,player.boundingBox().minX);
        assertTrue(player.boundingBox().minZ>before.minZ);
        assertTrue(player.boundingBox().maxZ>before.maxZ);
        assertEquals(target.z,player.boundingBox().feetCenter().z);
        assertEquals(2,player.collisionPenetratingFrames(),"Acceptance does not erase movement overlap history");
        assertEquals(player.boundingBox().feetCenter().x,player.position().x);
    }
}
