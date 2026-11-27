package ac.navro.proxy.command;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.check.api.Check;
import ac.navro.proxy.config.ConfigLoader;
import ac.navro.proxy.config.CheckConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ThreeConfigTest {
    @TempDir Path folder;
    CommandRuntime runtime(AtomicLong clock){return new CommandRuntime(folder.resolve("config.yml"),false,8500,clock::get);}
    SessionState session(CommandRuntime runtime,String name,List<String> alerts,List<String> kicks) {
        var session=new SessionState();session.player().setName(name);runtime.attach(UUID.randomUUID(),session);
        session.player().setFormattedAlertSink(alerts::add);session.player().setDisconnectSink(kicks::add);
        return session;
    }
    void rules(String value)throws Exception{Files.writeString(folder.resolve("punishments.yml"),value);}
    @Test void createsThreeDocumentedFilesAndMigratesExistingPrefixAndValues()throws Exception{
        Files.writeString(folder.resolve("config.yml"),"# Keep this user setting\nprefix: '&bCustom '\nmax-balance-advantage: 4321\n");
        var runtime=runtime(new AtomicLong());
        assertEquals("&bCustom ",runtime.config().prefix());assertEquals(4321,runtime.config().maxBalanceAdvantage());
        assertFalse(Files.readString(folder.resolve("config.yml")).contains("prefix:"));
        assertTrue(Files.readString(folder.resolve("config.yml")).contains("# Keep this user setting"));
        assertTrue(Files.readString(folder.resolve("messages.yml")).contains("&bCustom "));
        assertTrue(Files.readString(folder.resolve("punishments.yml")).contains("# Format:"));
        assertEquals(runtime.config(),runtime(new AtomicLong()).config());
    }
    @Test void eachCheckCanHaveIndependentActionsAndRealFailuresTriggerKickAtThreshold()throws Exception{
        var clock=new AtomicLong();var runtime=runtime(clock);
        rules("""
                Punishments:
                  All:
                    remove-violations-after: 300
                    checks: ["*", "!RotationPlace"]
                    commands: ["1:1 [alert]"]
                  Place:
                    remove-violations-after: 300
                    checks: ["RotationPlace"]
                    commands: ["2:0 kick %player% Block placement (%vl%)"]
                """);
        runtime.reload();var alerts=new ArrayList<String>();var kicks=new ArrayList<String>();var s=session(runtime,"Alice",alerts,kicks);
        var rotation=new Check(s.player(),"RotationPlace","",false);var timer=new Check(s.player(),"Timer","",false);
        rotation.fail("first");assertTrue(alerts.isEmpty());assertTrue(kicks.isEmpty());
        timer.fail("timing");assertEquals(1,alerts.size());assertTrue(alerts.get(0).contains("Timer"));assertTrue(kicks.isEmpty());
        rotation.fail("second");assertEquals(List.of("Block placement (2)"),kicks);assertTrue(s.player().disconnectRequested());
        rotation.fail("third");assertEquals(1,kicks.size());
        var other=session(runtime,"Bob",new ArrayList<>(),new ArrayList<>());
        new Check(other.player(),"RotationPlace","",false).fail();assertFalse(other.player().disconnectRequested());
    }
    @Test void intervalsExpiryAndReloadResetOnlyPunishmentCounters()throws Exception{
        var clock=new AtomicLong();var runtime=runtime(clock);
        rules("""
                Punishments:
                  Timer:
                    remove-violations-after: 1
                    checks: [Timer]
                    commands: ["2:2 [log]"]
                """);
        runtime.reload();var log=new ArrayList<String>();var s=session(runtime,"Alice",new ArrayList<>(),new ArrayList<>());
        s.player().setPunishmentLogSink(log::add);
        Files.writeString(folder.resolve("messages.yml"),"alerts-format: '%check%:%vl%:%check_vl%'\n");runtime.reload();
        var check=new Check(s.player(),"Timer","",false);
        for(int i=0;i<5;i++)check.fail();assertEquals(List.of("Timer:2:2","Timer:4:4"),log);
        clock.set(1000);check.fail();assertEquals(2,log.size());check.fail();assertEquals("Timer:2:7",log.get(2));
        runtime.reload();check.fail();assertEquals(3,log.size());check.fail();assertEquals("Timer:2:9",log.get(3));
    }
    @Test void invalidFileLeavesAllThreePreviousSettingsActive()throws Exception{
        var runtime=runtime(new AtomicLong());var before=runtime.config();
        String valid=Files.readString(folder.resolve("punishments.yml"));
        Files.writeString(folder.resolve("config.yml"),"smooth-setback: true\n");
        Files.writeString(folder.resolve("messages.yml"),"prefix: '&cChanged '\n");
        rules("Punishments:\n  Invalid:\n    checks: [Timer]\n    commands: ['1:0 ban %player%']\n");
        assertThrows(java.io.IOException.class,runtime::reload);
        assertSame(before,runtime.config());assertEquals("&aNavro &7>&r ",runtime.messages().prefix());
        rules(valid);Files.writeString(folder.resolve("messages.yml"),"prefix: [broken\n");
        assertThrows(java.io.IOException.class,runtime::reload);assertSame(before,runtime.config());
        Files.writeString(folder.resolve("messages.yml"),"prefix: '&cChanged '\n");runtime.reload();
        assertTrue(runtime.config().smoothSetback());assertEquals("&cChanged ",runtime.messages().prefix());
    }
    @Test void messageTemplatesReachRealCommandsAndPlayerTextCannotIntroducePlaceholders()throws Exception{
        var runtime=runtime(new AtomicLong());
        Files.writeString(folder.resolve("messages.yml"),"prefix: '&bTest '\nno-permission: '%prefix%Denied'\nalerts-enabled: '%prefix%Subscribed'\nalerts-format: '%prefix%%check%/%vl% %verbose%'\n");runtime.reload();
        var replies=new ArrayList<String>();var alerts=new ac.navro.proxy.alert.AlertManager(folder.resolve("alerts.json"),()->runtime.config().prefix(),s->{},Map::of);
        var sender=new NavroCommand.Sender(){public UUID identity(){return UUID.fromString("00000000-0000-0000-0000-000000000123");}public boolean hasPermission(String node){return false;}public void sendMessage(String message){replies.add(message);}};
        NavroCommand.handle("/navro alert",sender,alerts,runtime);assertEquals(List.of("§bTest Denied"),replies);
        var received=new ArrayList<String>();var s=session(runtime,"%vl%&c",received,new ArrayList<>());
        new Check(s.player(),"Timer","",false).fail("%player% &aUntrusted");
        assertEquals(List.of("§bTest Timer/1 %player% &aUntrusted"),received);
    }
    @Test void defaultRulesKeepAlertsAndDoNotEnableKicks()throws Exception{
        var runtime=runtime(new AtomicLong());var alerts=new ArrayList<String>();var kicks=new ArrayList<String>();var s=session(runtime,"Alice",alerts,kicks);
        var check=new Check(s.player(),"Timer","",false);
        for(int i=0;i<120;i++)check.fail();
        assertEquals(120,alerts.size());assertTrue(kicks.isEmpty());
        assertTrue(alerts.get(0).startsWith("§aNavro §7>§r §3§fAlice§7 failed§f Timer"));
    }
    @Test void defaultRulesHaveOneGroupPerCheckWithoutSharedViolationCounts()throws Exception {
        var runtime=runtime(new AtomicLong());
        var loaded=ConfigLoader.loadAll(folder.resolve("config.yml"),CheckConfig.defaults(false,8500));
        assertEquals(23,loaded.punishments().groups().size());
        assertEquals(ac.navro.proxy.config.PunishmentConfig.defaults(),loaded.punishments());
        for(var group:loaded.punishments().groups())assertEquals(List.of(group.name()),group.checks());
        Files.writeString(folder.resolve("messages.yml"),"alerts-format: '%check%/%vl%'\n");runtime.reload();
        var alerts=new ArrayList<String>();var s=session(runtime,"Alice",alerts,new ArrayList<>());
        new Check(s.player(),"Timer","",false).fail();
        new Check(s.player(),"NegativeTimer","",false).fail();
        new Check(s.player(),"Sprint","A",false).fail();
        new Check(s.player(),"Sprint","B",false).fail();
        assertEquals(List.of("Timer/1","NegativeTimer/1","Sprint-A/1","Sprint-B/1"),alerts);
    }
    @Test void exemptAndDisabledChecksNeverAccumulatePunishments()throws Exception{
        var runtime=runtime(new AtomicLong());
        rules("Punishments:\n  Timer:\n    checks: [Timer]\n    commands: ['1:0 kick %player%']\n");runtime.reload();
        var kicks=new ArrayList<String>();var s=session(runtime,"Alice",new ArrayList<>(),kicks);
        var check=s.player().getCheckHolder().get(ac.navro.proxy.check.impl.timer.Timer.class);
        s.player().setExempted(true);check.fail();assertTrue(kicks.isEmpty());
        s.player().setExempted(false);s.player().setDisabledChecks(java.util.Set.of("Timer"));check.fail();assertTrue(kicks.isEmpty());
        s.player().setDisabledChecks(java.util.Set.of());check.fail();assertEquals(1,kicks.size());
        assertTrue(kicks.get(0).contains("Timer"));
    }
    @Test void rollingExpiryDoesNotSuppressSubsequentRepeatActions()throws Exception{
        var clock=new AtomicLong();var runtime=runtime(clock);
        rules("Punishments:\n  Timer:\n    remove-violations-after: 1\n    checks: [Timer]\n    commands: ['1:1 [alert]']\n");runtime.reload();
        var alerts=new ArrayList<String>();var s=session(runtime,"Alice",alerts,new ArrayList<>());
        var check=new Check(s.player(),"Timer","",false);
        for(int i=0;i<12;i++){clock.set(i*500L);check.fail();}
        assertEquals(12,alerts.size());
    }
}
