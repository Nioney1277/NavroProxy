package ac.navro.proxy.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForwardingLatencyTest {
    @Test void rollingWindowEvictsOldSpikesAndKeepsDirectionsSeparate() {
        var stats=new ForwardingLatency();assertEquals(0,stats.sample(false).count());
        stats.record(false,900_000_000);
        for(int i=0;i<256;i++)stats.record(false,1_000_000);
        var sample=stats.sample(false);assertEquals(256,sample.count());
        assertEquals(1,sample.averageMs());assertEquals(1,sample.p95Ms());assertEquals(1,sample.maximumMs());
        assertEquals(0,stats.sample(true).count());
    }
    @Test void onlyMatchedProxyAcknowledgmentsSupplyClientRtt() {
        var latency=new LatencyUtil(null);assertEquals(-1,latency.lastClientRoundTripNanos());
        latency.queue(10,true);assertFalse(latency.onResponse(11));assertEquals(-1,latency.lastClientRoundTripNanos());
        assertTrue(latency.onResponse(10));assertTrue(latency.lastClientRoundTripNanos()>=0);
        long measured=latency.lastClientRoundTripNanos();latency.queue(20,false);latency.onResponse(20);
        assertEquals(measured,latency.lastClientRoundTripNanos());
    }
}
