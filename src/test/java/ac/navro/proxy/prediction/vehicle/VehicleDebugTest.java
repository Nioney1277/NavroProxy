package ac.navro.proxy.prediction.vehicle;

import ac.navro.proxy.check.impl.prediction.DebugOffsetA;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.diagnostics.WorldCollisionDiagnostics;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ac.navro.proxy.prediction.vehicle.VehiclePredictionTest.*;

class VehicleDebugTest {
    @Test void mountedPredictionPublishesBothSubscriberModesEvenWhileStationary() {
        var s=session(false);var debug=new ArrayList<String>();var debug2=new ArrayList<String>();
        var observer=UUID.randomUUID();s.toggleDebugSubscriber(false,observer,debug::add);s.toggleDebugSubscriber(true,observer,debug2::add);
        s.observeClient(input(s,10,8,64,8));
        assertEquals(4,debug.size());assertEquals(1,debug2.size());
        assertTrue(debug.getFirst().contains("minecraft:boat #42, state=predicted"));
        assertTrue(debug.get(1).contains("T: VEHICLE"));assertTrue(debug.get(2).contains("A EOT:"));
        assertTrue(debug2.getFirst().startsWith("§fDEBUGGER\n"));assertTrue(debug2.getFirst().contains("Actually EOT XYZ:"));
        assertTrue(debug2.getFirst().contains("Yaw predicted/reported: 90.0000000000 / 90.0000000000"));
        s.observeClient(input(s,11,8,64,8));assertEquals(8,debug.size());assertEquals(2,debug2.size());
    }
    @Test void debugTargetsAndTogglesStayIsolated() {
        var a=session(false);var b=session(false);var messages=new ArrayList<String>();var id=UUID.randomUUID();
        a.toggleDebugSubscriber(true,id,messages::add);b.observeClient(input(b,10,8,64,8));assertTrue(messages.isEmpty());
        a.observeClient(input(a,10,8,64,8));assertEquals(1,messages.size());
        assertFalse(a.toggleDebugSubscriber(true,id,messages::add));a.observeClient(input(a,11,8,64,8));assertEquals(1,messages.size());
    }
    @Test void passengersAndTerrainWaitReportStatusWithoutInventingAPrediction() {
        for(boolean passenger:new boolean[]{true,false}) {
            var s=session(false);var messages=new ArrayList<String>();s.toggleDebugSubscriber(true,UUID.randomUUID(),messages::add);
            if(passenger){link(s,70,EntityLinkData.Type.PASSENGER);ack(s);}
            else s.world().put(0,0,CompensatedWorld.CachedChunk.partialNetwork(-64,320,24,s.world().airNetworkId()));
            s.observeClient(input(s,10,8,64,8));assertEquals(1,messages.size());
            assertTrue(messages.getFirst().contains(passenger?"unsupported_or_passenger":"waiting_for_terrain"));
            assertTrue(messages.getFirst().contains("Prediction XYZ: N/A"));assertTrue(messages.getFirst().contains("Offset: N/A"));
        }
    }
    @Test void disablingDebugOffsetAlsoDisablesTheVehiclePublisher() {
        var s=session(false);var messages=new ArrayList<String>();s.toggleDebugSubscriber(true,UUID.randomUUID(),messages::add);
        s.player().setDisabledChecks(Set.of("DebugOffset"));assertFalse(s.player().checkEnabled(DebugOffsetA.class));
        s.observeClient(input(s,10,8,64,8));assertTrue(messages.isEmpty());
    }
    @Test void invalidVehicleInputKeepsDebugAndFileDiagnosticsValid() {
        var s=session(false);var messages=new ArrayList<String>();var events=new ArrayList<Map<String,Object>>();
        s.player().setWorldDiagnostics(new WorldCollisionDiagnostics(s.player(),s.world(),"vehicle-debug-test",events::add));
        s.toggleDebugSubscriber(true,UUID.randomUUID(),messages::add);
        var p=input(s,10,8,64,8);p.setVehicleRotation(Vector2f.from(0,Float.NaN));s.observeClient(p);
        assertEquals(1,messages.size());assertTrue(messages.getFirst().contains("invalid_vehicle_input"));
        var frame=events.stream().filter(row->row.get("event").equals("vehicle_prediction")).findFirst().orElseThrow();
        assertNull(frame.get("rotationOffset"));assertDoesNotThrow(()->new com.google.gson.Gson().toJson(frame));
    }
}
