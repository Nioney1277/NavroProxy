package ac.navro.proxy.config;

import java.util.*;


public record CheckConfig(String prefix, int rewindHistory, boolean smoothSetback,
                          float acceptanceThreshold, float alertThreshold,
                          Set<String> disabledChecks, long maxLatencyWait, long maxBalanceAdvantage) {
    public CheckConfig {
        disabledChecks = Set.copyOf(disabledChecks);
        if (rewindHistory < 1 || rewindHistory > 4096 || !Float.isFinite(acceptanceThreshold)
                || acceptanceThreshold <= 0 || !Float.isFinite(alertThreshold) || alertThreshold < 0
                || maxLatencyWait < 1 || maxBalanceAdvantage < 0)
            throw new IllegalArgumentException("Invalid Navro check configuration");
    }
    public static CheckConfig defaults(boolean smooth, long maxBalance) {
        return new CheckConfig("&aNavro &7>&r ",20,smooth,1e-4F,0,Set.of(),15000,maxBalance);
    }
    public String formattedPrefix() { return prefix.replace('&','§'); }
}
