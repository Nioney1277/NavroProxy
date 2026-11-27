package ac.navro.proxy.command;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.alert.AlertManager;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import ac.navro.anticheat.util.math.Vec3;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Commands drive actual config reload, session subscriptions and real prediction/debug output. */
class NavroCommandTest {
    @TempDir Path directory;
    static final UUID OBSERVER=UUID.fromString("00000000-0000-0000-0000-000000000111");
    static final UUID TARGET=UUID.fromString("00000000-0000-0000-0000-000000000222");
    static final String PREFIX="§aNavro §7>§r ";
    final List<String> messages=new ArrayList<>();
    class Sender implements NavroCommand.Sender {
        boolean allowed=true,player=true;
        public UUID identity(){return player?OBSERVER:AlertManager.CONSOLE_UUID;}
        public String name(){return "Observer";}
        public boolean hasPermission(String node){return allowed;}
        public void sendMessage(String s){messages.add(s);}
        public boolean isPlayer(){return player;}
    }
    record Fixture(CommandRuntime runtime,AlertManager alerts,SessionState target,SessionState observer) { }
    Fixture fixture() {
        var runtime=new CommandRuntime(directory.resolve("config.yml"),false,8500);
        var alerts=new AlertManager(directory.resolve("alerts.json"),()->runtime.config().prefix(),s->{},Map::of);
        var target=AuthInputPacketsTest.session();target.player().setName("Target");runtime.attach(TARGET,target);
        var observer=AuthInputPacketsTest.session();observer.player().setName("Observer");runtime.attach(OBSERVER,observer);
        return new Fixture(runtime,alerts,target,observer);
    }
    void command(String s,Sender sender,Fixture f){assertTrue(NavroCommand.handle(s,sender,f.alerts(),f.runtime()));}
    void move(SessionState state,long tick) {
        var p=AuthInputPacketsTest.input(tick,8,64,8.098F,new Vec3(0,-.0784F,.098F*.54600006F));
        p.setMotion(org.cloudburstmc.math.vector.Vector2f.from(0,1));p.setRawMoveVector(org.cloudburstmc.math.vector.Vector2f.from(0,1));
        state.observeClient(p);
    }
    @Test void allPrivilegedBranchesDenyBeforeChangingConfigOrSubscriptions() throws Exception {
        var f=fixture();var sender=new Sender();sender.allowed=false;
        Files.writeString(directory.resolve("config.yml"),"prefix: '&cChanged '\nsmooth-setback: true\n");
        for(String s:List.of("alert","reload","debug Target","debug2"))command("/navro "+s,sender,f);
        assertEquals(Collections.nCopies(4,"§cYou do not have permission to use this command."),messages);
        assertFalse(f.alerts().hasAlert(OBSERVER));assertFalse(f.target().hasDebugSubscribers());assertFalse(f.observer().hasDebugSubscribers());
        assertFalse(f.runtime().config().smoothSetback());assertEquals(PREFIX,f.alerts().getPrefix());
    }
    @Test void targetDebugTogglesDeliverOriginalFourLinesFromActualPredictionAndStopAfterDisable() {
        var f=fixture();var sender=new Sender();command("/navro debug",sender,f);
        assertEquals(List.of(PREFIX+"Usage: /navro debug <player>"),messages);messages.clear();
        command("/navro debug missing",sender,f);
        assertEquals(List.of(PREFIX+"§cFailed to find player session."),messages);messages.clear();
        command("/NAVRO debug tArGeT",sender,f);
        assertEquals(List.of(PREFIX+"§fEnabled debug for Target."),messages);messages.clear();
        move(f.target(),10);
        assertEquals(4,messages.size());assertTrue(messages.get(0).startsWith(PREFIX+"§3§aO:0.0, T: NORMAL, P: "));
        assertTrue(messages.get(1).contains("SPRINTING=false, SNEAKING=false, water=false"));
        assertEquals(PREFIX+"§3A EOT: "+f.target().authInputPackets().lastResult().predictedVelocity().toVector3f(),messages.get(2));
        assertTrue(messages.get(3).startsWith(PREFIX+"§3EOT O: §a"));
        assertTrue(messages.get(3).endsWith(","+new Vec3(0,-.0784F,.098F*.54600006F).toVector3f()));messages.clear();
        command("/navro debug Target",sender,f);assertEquals(PREFIX+"§fDisabled debug for Target.",messages.remove(0));
        f.target().observeClient(AuthInputPacketsTest.input(11,8,64,8.151508F,new Vec3(0,-.0784F,.053508F*.54600006F)));
        assertTrue(messages.isEmpty());
    }
    @Test void debug2DefaultsToSelfAndUsesOriginalTenDecimalOutputWithoutAlertPrefix() {
        var f=fixture();var sender=new Sender();command("/navro:navro debug2",sender,f);
        assertEquals(List.of(PREFIX+"§fEnabled debug2 for Observer."),messages);messages.clear();move(f.observer(),10);
        assertEquals(1,messages.size());
        var result=f.observer().authInputPackets().lastResult();float movement=result.predictedPosition().z-8F;
        assertEquals(String.format(Locale.ROOT,"§fDEBUGGER\n§7Offset: %.10f\nPrediction XYZ: %.10f %.10f %.10f\nActually XYZ: %.10f %.10f %.10f",0F,0F,0F,movement,0F,0F,movement),messages.get(0));
        messages.clear();sender.player=false;command("/navro debug2",sender,f);
        assertEquals(List.of(PREFIX+"Usage: /navro debug2 <player>"),messages);
    }
    @Test void reloadChangesOnlinePredictionThresholdCorrectionsPrefixHistoryAndCheckGates() throws Exception {
        var f=fixture();var sender=new Sender();
        Files.writeString(directory.resolve("config.yml"),"prefix: '&bNew '\nsmooth-setback: true\nplayer-position-acceptance-threshold: 0.0002\ndiffer-till-alert: 0.01\nplayer-rewind-history-size-ticks: 5\nmax-latency-wait: 21000\nmax-balance-advantage: 4200\ndisabled-checks: [Timer, BadPacketsC, Prediction, DebugOffset]\n");
        Files.writeString(directory.resolve("messages.yml"),"prefix: '&bNew '\n");
        command("/navro reload",sender,f);
        assertTrue(messages.get(0).startsWith("§bNew §fReloaded config! New config: "));messages.clear();
        f.target().observeClient(AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0)));
        assertEquals(.0002F,f.target().authInputPackets().context().maxOffset);assertEquals(.01F,f.target().authInputPackets().context().alertThreshold);
        assertTrue(f.target().smoothSetback());assertEquals(5,f.target().rewindHistorySize());assertEquals(21000,f.target().maxLatencyWait());assertEquals(4200,f.target().player().maxBalanceAdvantageMs());
        // A configured disabled Prediction must not emit alerts or a correction for this constructed invalid input.
        var alerts=new ArrayList<String>();var corrections=AuthInputPacketsTest.transport(f.target());f.target().player().setAlertSink(alerts::add);
        f.target().observeClient(AuthInputPacketsTest.input(11,100,64,8,Vec3.ZERO));
        assertTrue(alerts.isEmpty());assertTrue(corrections.isEmpty());assertFalse(f.target().teleports().isTeleporting());
        assertEquals("§bNew ",f.alerts().getPrefix());
        assertFalse(f.target().player().checkEnabled(ac.navro.proxy.check.impl.timer.Timer.class));
        assertFalse(f.target().player().checkEnabled(ac.navro.proxy.check.impl.badpackets.BadPacketsC.class));
        assertInstanceOf(ac.navro.proxy.check.api.impl.OffsetHandlerCheck.class,
                f.target().player().getCheckHolder().get(ac.navro.proxy.check.impl.prediction.DebugOffsetA.class));
        assertFalse(f.target().player().checkEnabled(ac.navro.proxy.check.impl.prediction.DebugOffsetA.class));
    }
    @Test void invalidReloadReportsFailureAndKeepsLastWorkingConfiguration() throws Exception {
        var f=fixture();var sender=new Sender();var before=f.runtime().config();
        Files.writeString(directory.resolve("config.yml"),"smooth-setback: [broken\n");command("/navro reload",sender,f);
        assertTrue(messages.get(0).startsWith(PREFIX+"§cCould not reload config:"));assertSame(before,f.runtime().config());
    }
    @Test void pocketMineBridgeOwnsAlertsWhileProxyKeepsManagementSubcommands() {
        var f = fixture();
        var sender = new Sender();
        var packet = new org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket();
        var permission = org.cloudburstmc.protocol.bedrock.data.command.CommandPermission.ANY;
        var overloads = new org.cloudburstmc.protocol.bedrock.data.command.CommandOverloadData[0];
        packet.getCommands().add(new org.cloudburstmc.protocol.bedrock.data.command.CommandData(
                "navro", "Unrelated backend command", Set.of(), permission, null, List.of(), overloads));
        var unrelated = NavroCommand.routing(packet, true);
        assertFalse(NavroCommand.handle("/navro reload", sender, f.alerts(), f.runtime(), unrelated));

        packet.getCommands().clear();
        packet.getCommands().add(new org.cloudburstmc.protocol.bedrock.data.command.CommandData(
                "navro", "Manage NavroProxy alerts and diagnostics", Set.of(), permission, null, List.of(), overloads));
        var bridge = NavroCommand.routing(packet, true);
        assertTrue(bridge.bridgeAlerts());
        assertFalse(NavroCommand.handle("/navro alert", sender, f.alerts(), f.runtime(), bridge));
        assertFalse(f.alerts().hasAlert(OBSERVER));
        assertTrue(NavroCommand.handle("/navro reload", sender, f.alerts(), f.runtime(), bridge));
        assertTrue(NavroCommand.handle("/navro debug Target", sender, f.alerts(), f.runtime(), bridge));
        assertTrue(f.target().hasDebugSubscribers());
        assertTrue(NavroCommand.handle("/navro debug2", sender, f.alerts(), f.runtime(), bridge));
        assertTrue(f.observer().hasDebugSubscribers());
    }
    @Test void disconnectRemovesObserverFromAllTargetsAndReconnectDoesNotInheritOldSubscriptions() {
        var f=fixture();var sender=new Sender();command("/navro debug2 Target",sender,f);messages.clear();
        f.runtime().detach(OBSERVER,f.observer());move(f.target(),10);assertTrue(messages.isEmpty());
        f.runtime().attach(OBSERVER,f.observer());assertFalse(f.target().hasDebugSubscribers());
    }
    @Test void unknownSubcommandsStayLocalAndDisplayAllNativeUsageLines() {
        var f=fixture();var sender=new Sender();command("/navro unknown",sender,f);
        assertEquals(List.of(PREFIX+"Unknown subcommand.",PREFIX+"Usage: /navro alert",PREFIX+"Usage: /navro reload",PREFIX+"Usage: /navro debug <player>",PREFIX+"Usage: /navro debug2 [player]"),messages);
    }
    @Test void helpDenialAndMissingTargetRepliesMatchDirectlyExecutedOriginalNavro() throws Exception {
        var f=fixture();var sender=new Sender();sender.player=false;
        try(var in=new java.io.InputStreamReader(getClass().getResourceAsStream("/navro-command-reference.json"))) {
            var rows=com.google.gson.JsonParser.parseReader(in).getAsJsonObject().getAsJsonArray("cases");
            assertEquals(14,rows.size());
            for(var item:rows) {
                var row=item.getAsJsonObject();sender.allowed=row.get("allowed").getAsBoolean();messages.clear();
                command("/navro "+row.get("subcommand").getAsString(),sender,f);
                var expected=new ArrayList<String>();row.getAsJsonArray("messages").forEach(v->expected.add(v.getAsString()));
                assertEquals(expected,messages,row.toString());
            }
        }
    }
    @Test void debug2ReportedOffsetAndMotionMatchOriginalNativeFormatter() throws Exception {
        var f=fixture();var sender=new Sender();command("/navro debug2 Target",sender,f);messages.clear();
        f.target().observeClient(AuthInputPacketsTest.input(10,8.125F,64,8,new Vec3(0,-.0784F,0)));
        try(var in=new java.io.InputStreamReader(getClass().getResourceAsStream("/navro-command-reference.json"))) {
            var expected=com.google.gson.JsonParser.parseReader(in).getAsJsonObject().get("debug2").getAsString();
            assertEquals(List.of(expected),messages);
        }
    }
}
