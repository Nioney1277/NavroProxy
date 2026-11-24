package ac.navro.proxy.check.impl.timer;

import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.util.LatencyUtil;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class BoarTimerReferenceTest {
    @Test void upstreamTimerProducesIdenticalVerdictsBalancesAndAcknowledgmentState()throws Exception {
        var player=new WirePlayerState();var now=new AtomicLong();var wall=new AtomicLong();var flags=new ArrayList<String>();
        var timer=new Timer(player,now::get,flags::add,wall::get);
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/boar-timer-reference.bin.gz")))) {
            assertEquals(0x424f5431,in.readInt());int cases=in.readInt();assertEquals(800,cases);
            for(int i=0;i<cases;i++) {
                now.set(in.readLong());wall.set(in.readLong());player.inLoadingScreen=in.readBoolean();
                player.loadingScreenStartMs=0;player.sinceLoadingScreen=in.readInt();player.commitInputTick(i);
                player.setMaxBalanceAdvantageMs(in.readLong());
                assertEquals(in.readBoolean(),timer.isInvalid(),"verdict "+i);
                assertEquals(in.readInt(),flags.size(),"flags "+i);
                boolean ack=in.readBoolean();long sent=in.readLong();
                if(ack)timer.onLatencyAccepted(new LatencyUtil.Latency(i,sent/1000000,sent,true,List.of()));
                assertEquals(in.readLong(),timer.budget.lastNS,"lastNS "+i);
                assertEquals(in.readLong(),timer.budget.balance,"balance "+i);
                assertEquals(in.readLong(),timer.budget.loseBalance,"credit "+i);
                assertEquals(in.readBoolean(),timer.budget.beforeAuthInput,"ack eligible "+i);
            }
            assertEquals(-1,in.read());assertEquals(0,player.pendingCorrectionRequests());
        }
    }
    private record State(long lastNS,long balance,long credit,boolean eligible,int nanoCalls,int wallCalls) {
        static State read(DataInputStream in)throws Exception {
            return new State(in.readLong(),in.readLong(),in.readLong(),in.readBoolean(),in.readInt(),in.readInt());
        }
        static State capture(Timer timer,int[] calls) {
            return new State(timer.budget.lastNS,timer.budget.balance,timer.budget.loseBalance,
                    timer.budget.beforeAuthInput,calls[0],calls[1]);
        }
    }
    @Test void upstreamClockReadsAndFlagStateMatchEvenWhenTimeAdvancesInsideEachCall()throws Exception {
        var player=new WirePlayerState();var now=new AtomicLong();var wall=new AtomicLong();var step=new AtomicLong();
        int[] calls={0,0};var callbacks=new ArrayList<State>();var holder=new Timer[1];
        var timer=new Timer(player,()->{calls[0]++;return now.getAndAdd(step.get());},
                message->callbacks.add(State.capture(holder[0],calls)),()->{calls[1]++;return wall.get();});
        holder[0]=timer;int flags=0;
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/boar-timer-clock-trace.bin.gz")))) {
            assertEquals(0x42545432,in.readInt());int cases=in.readInt();assertEquals(800,cases);
            for(int i=0;i<cases;i++) {
                now.set(in.readLong());wall.set(in.readLong());step.set(in.readLong());
                player.inLoadingScreen=in.readBoolean();player.loadingScreenStartMs=0;
                player.sinceLoadingScreen=in.readInt();player.commitInputTick(i);player.setMaxBalanceAdvantageMs(in.readLong());
                calls[0]=calls[1]=0;callbacks.clear();
                assertEquals(in.readBoolean(),timer.isInvalid(),"verdict "+i);
                flags+=callbacks.size();assertEquals(in.readInt(),flags,"flags "+i);
                int flagStates=in.readInt();assertEquals(flagStates,callbacks.size());
                for(int j=0;j<flagStates;j++)assertEquals(State.read(in),callbacks.get(j),"state at flag "+i);
                boolean ack=in.readBoolean();long sent=in.readLong();
                if(ack)timer.onLatencyAccepted(new LatencyUtil.Latency(i,sent/1_000_000,sent,true,List.of()));
                assertEquals(State.read(in),State.capture(timer,calls),"state after ACK "+i);
                assertEquals(in.readLong(),now.get(),"clock read sequence "+i);
            }
            assertEquals(-1,in.read());assertTrue(flags>0);assertEquals(0,player.pendingCorrectionRequests());
        }
    }
}
