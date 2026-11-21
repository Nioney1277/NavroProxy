package ac.navro.proxy.check.api;

import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.check.api.annotations.Experimental;
import ac.navro.proxy.state.WirePlayerState;


public class Check {
    protected final WirePlayerState player;
    private final String name, type;
    private final boolean experimental;
    private int vl;
    private final double setbackVl, decay;
    private double setbackViolations;

    public Check(WirePlayerState player) {
        this.player = player;
        CheckInfo info = getClass().getDeclaredAnnotation(CheckInfo.class);
        name = info != null ? info.name() : getClass().getSimpleName();
        type = info != null ? info.type() : "";
        experimental = getClass().getDeclaredAnnotation(Experimental.class) != null;
        setbackVl = info == null ? 25 : info.setback();
        decay = info == null ? 0.05 : info.decay();
    }

    public Check(WirePlayerState player, String name, String type, boolean experimental) {
        this.player = player;
        this.name = name;
        this.type = type;
        this.experimental = experimental;
        setbackVl = 25;
        decay = 0.05;
    }

    public void fail() { fail(""); }

    protected final boolean flagWithSetback() {
        if (player.isExempted()) return false;
        fail();
        setbackViolations++;
        setbackIfAboveSetbackVL();
        return true;
    }

    protected final boolean setbackIfAboveSetbackVL() {
        if (setbackViolations <= setbackVl) return false;
        player.requestRewind(player.tick());
        return true;
    }

    protected final void reward() {
        setbackViolations = Math.max(0, setbackViolations - decay);
    }

    public void fail(String verbose) {
        if (player.isExempted() || player.disconnectRequested() || !player.checkEnabled(getClass())) return;
        vl++;
        StringBuilder builder = new StringBuilder("§f").append(player.getName()).append("§7 failed§f ");
        if ("Bad Packet".equals(name)) builder.append("BadPackets").append(type);
        else {
            builder.append(name);
            if (!type.isBlank()) builder.append(" (").append(type).append(")");
        }
        if (experimental) builder.append(" §7(Experimental)");
        appendViolationDetails(builder, vl, verbose == null ? "" : verbose);
        String display="Bad Packet".equals(name)?"BadPackets"+type:name+(type.isBlank()?"":" ("+type+")");
        player.violation(new CheckViolation(type.isBlank()?name:name+"-"+type,name,display,vl,experimental,
                verbose==null?"":verbose,builder.toString()));
    }

    protected void appendViolationDetails(StringBuilder builder, int vl, String verbose) {
        builder.append(" §7x§a").append(vl).append(" §7").append(verbose);
    }

    protected final String getDisplayName() { return player.getName(); }
}
