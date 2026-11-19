package ac.navro.proxy.check.api.impl;

import ac.navro.proxy.check.api.Check;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.util.LatencyUtil;

public class PingBasedCheck extends Check {
    public PingBasedCheck(WirePlayerState player) { super(player); }
    public void onLatencyAccepted(LatencyUtil.Latency latency) { }
}
