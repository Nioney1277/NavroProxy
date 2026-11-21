package ac.navro.proxy.check.impl.prediction;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.check.api.impl.OffsetHandlerCheck;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.prediction.live.PredictionContext;
import ac.navro.anticheat.util.math.Vec3;
import java.util.Locale;


@CheckInfo(name = "DebugOffset")
public final class DebugOffsetA extends OffsetHandlerCheck {
    private final PredictionContext prediction;

    public DebugOffsetA(WirePlayerState player, PredictionContext prediction) {
        super(player, prediction);
        this.prediction = prediction;
    }

    @Override
    public void onPredictionComplete(float offset) {
        SessionState session = prediction.session;
        PredictionContext player = prediction;
        if(!session.hasDebugSubscribers())return;
        float maxOffset=player.getMaxOffset();float eotOffset=player.unvalidatedTickEnd.distanceTo(player.velocity);
        Vec3 predicted=player.position.subtract(player.prevUnvalidatedPosition);
        Vec3 actual=player.unvalidatedPosition.subtract(player.prevUnvalidatedPosition);
        if(actual.length()<=1e-5&&offset<=maxOffset&&eotOffset<=maxOffset)return;
        session.sendDebug(true,String.format(Locale.ROOT,
                "§fDEBUGGER\n§7Offset: %.10f\nPrediction XYZ: %.10f %.10f %.10f\nActually XYZ: %.10f %.10f %.10f",
                offset,predicted.x,predicted.y,predicted.z,actual.x,actual.y,actual.z));
        String color=offset>maxOffset?"§c":offset>1e-5?"§6":"§a";
        if(player.inputPositionDistance(player.position)>maxOffset&&offset<maxOffset)color="§7";
        String prefix=session.commandPrefix()+"§3";
        session.sendDebug(false,prefix+color+"O:"+offset+", T: NORMAL, P: "+predicted.x+","+predicted.y+","+predicted.z+", pos="+player.position);
        boolean water=player.fluidPrediction.liquids!=null&&player.fluidPrediction.liquids.water().touching();
        session.sendDebug(false,prefix+color+"A: "+actual.x+","+actual.y+","+actual.z+", SPRINTING="+session.player().hasFlag(3)+", SNEAKING="+session.player().hasFlag(1)+", water="+water);
        session.sendDebug(false,prefix+"A EOT: "+player.velocity.toVector3f());
        session.sendDebug(false,prefix+"EOT O: "+(eotOffset>1e-4?"§b":"§a")+eotOffset+","+player.unvalidatedTickEnd.toVector3f());
    }
}
