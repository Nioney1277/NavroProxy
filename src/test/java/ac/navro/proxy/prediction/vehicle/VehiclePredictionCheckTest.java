package ac.navro.proxy.prediction.vehicle;

import ac.navro.proxy.check.impl.prediction.VehiclePredictionCheck;
import ac.navro.proxy.command.CommandRuntime;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ac.navro.proxy.prediction.vehicle.VehiclePredictionTest.*;

class VehiclePredictionCheckTest {
    @TempDir Path folder;
    @Test void offsetsUseIndependentVlAndTheExistingPredictionFieldLayout() {
        var s=session(false);var alerts=new ArrayList<String>();s.player().setAlertSink(alerts::add);
        s.player().setName("Driver");
        s.observeClient(input(s,10,9,64,8));
        assertEquals(1,alerts.size());
        assertTrue(alerts.getFirst().contains("Driver§7 failed§f VehiclePrediction §7x§a1 §7o: "),alerts.toString());
        assertTrue(alerts.getFirst().contains(", expected: "),alerts.toString());
        assertTrue(alerts.getFirst().contains(", actual: "),alerts.toString());
        s.observeClient(input(s,11,9,64,8));
        assertEquals(2,alerts.size());assertTrue(alerts.getLast().contains("VehiclePrediction §7x§a2"));
        assertTrue(alerts.stream().noneMatch(x->x.contains("failed§f Horizonal")||x.contains("failed§f Vertical")));
    }
    @Test void disabledAndUnloadedOrCorrectMovementDoNotAccumulateVehicleVl() {
        var s=session(false);var alerts=new ArrayList<String>();s.player().setAlertSink(alerts::add);
        s.player().setDisabledChecks(Set.of("VehiclePrediction"));s.observeClient(input(s,10,20,64,8));
        assertTrue(alerts.isEmpty());s.player().setDisabledChecks(Set.of());
        s.observeClient(input(s,11,20,64,8));assertEquals(1,alerts.size());assertTrue(alerts.getFirst().contains("§7x§a1"));
        var correct=session(false);correct.player().setAlertSink(alerts::add);correct.observeClient(input(correct,10,8,64,8));
        assertEquals(1,alerts.size());
        var missing=session(false);missing.player().setAlertSink(alerts::add);
        missing.world().put(0,0,CompensatedWorld.CachedChunk.partialNetwork(-64,320,24,missing.world().airNetworkId()));
        missing.observeClient(input(missing,10,20,64,8));assertEquals(1,alerts.size());
    }
    @Test void vehiclePunishmentsUseTheirOwnConfiguredThreshold() throws Exception {
        var runtime=new CommandRuntime(folder.resolve("config.yml"),false,8500);
        Files.writeString(folder.resolve("punishments.yml"),"Punishments:\n  Vehicles:\n    checks: [VehiclePrediction]\n    commands: ['2:0 [alert]']\n");runtime.reload();
        var s=session(false);runtime.attach(UUID.randomUUID(),s);var alerts=new ArrayList<String>();s.player().setFormattedAlertSink(alerts::add);
        s.observeClient(input(s,10,20,64,8));assertTrue(alerts.isEmpty());
        s.observeClient(input(s,11,20,64,8));assertEquals(1,alerts.size());
        assertTrue(alerts.getFirst().contains("VehiclePrediction"));
        assertTrue(s.player().getCheckHolder().containsKey(VehiclePredictionCheck.class));
    }
}
