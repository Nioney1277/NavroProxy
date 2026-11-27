package ac.navro.proxy.check.api.holder;

import ac.navro.proxy.check.impl.timer.NegativeTimer;
import ac.navro.proxy.check.impl.timer.Timer;
import ac.navro.proxy.check.impl.badpackets.BadPacketsC;
import ac.navro.proxy.check.impl.badpackets.BadPacketA;
import ac.navro.proxy.check.impl.badpackets.BadPacketB;
import ac.navro.proxy.check.impl.prediction.Prediction;
import ac.navro.proxy.check.impl.prediction.DebugOffsetA;
import ac.navro.proxy.check.impl.multiactions.MultiActionsA;
import ac.navro.proxy.check.impl.multiactions.MultiActionsB;
import ac.navro.proxy.check.impl.sprint.SprintA;
import ac.navro.proxy.check.impl.sprint.SprintB;
import ac.navro.proxy.check.impl.sprint.SprintC;
import ac.navro.proxy.check.impl.sprint.SprintD;
import ac.navro.proxy.check.api.Check;
import ac.navro.proxy.check.api.impl.PacketCheck;
import ac.navro.proxy.prediction.live.PredictionContext;
import ac.navro.proxy.state.WirePlayerState;

import java.util.LinkedHashMap;


public final class CheckHolder extends LinkedHashMap<Class<?>, Check> {
    private final WirePlayerState player;
    public CheckHolder(WirePlayerState player) {
        this.player=player;
        put(Timer.class, new Timer(player));
        put(NegativeTimer.class, new NegativeTimer(player));
        put(BadPacketsC.class, new BadPacketsC(player));
        put(BadPacketA.class, new BadPacketA(player));
        put(BadPacketB.class, new BadPacketB(player));
        put(MultiActionsA.class, new MultiActionsA(player));
        put(MultiActionsB.class, new MultiActionsB(player));
    }

    public void registerPredictionHandlers(PredictionContext prediction) {
        put(SprintA.class, new SprintA(player, prediction));
        put(SprintB.class, new SprintB(player, prediction));
        put(SprintC.class, new SprintC(player, prediction));
        put(SprintD.class, new SprintD(player, prediction));
        put(Prediction.class, new Prediction(player, prediction));
        put(ac.navro.proxy.check.impl.prediction.VehiclePredictionCheck.class,
                new ac.navro.proxy.check.impl.prediction.VehiclePredictionCheck(player,prediction));
        put(DebugOffsetA.class, new DebugOffsetA(player, prediction));
    }

    public boolean enabled(Class<?> type) { return player.checkEnabled(type); }
    public java.util.List<PacketCheck> packetChecks() {
        java.util.List<PacketCheck> result = new java.util.ArrayList<>();
        for (Check check : values()) if (player.checkEnabled(check.getClass()) && check instanceof PacketCheck packetCheck) result.add(packetCheck);
        return result;
    }

    public void manuallyFail(Class<?> type, String verbose) {
        Check check = get(type);
        if (check != null) check.fail(verbose);
    }
}
