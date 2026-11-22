package ac.navro.proxy.config;

import java.util.LinkedHashMap;
import java.util.Map;

public record MessageConfig(Map<String,String> templates) {
    public MessageConfig { templates=Map.copyOf(templates); }
    public static MessageConfig defaults(String prefix) {
        var m=new LinkedHashMap<String,String>();
        m.put("prefix",prefix);
        m.put("alerts-format","%prefix%&3%message%");
        m.put("alerts-enabled","%prefix%&fEnabled alerts.");
        m.put("alerts-disabled","%prefix%&fDisabled alerts.");
        m.put("alerts-save-failed","%prefix%&cCould not save alerts preference.");
        m.put("no-permission","&cYou do not have permission to use this command.");
        m.put("reloaded","%prefix%&fReloaded config! New config: %config%");
        m.put("reload-failed","%prefix%&cCould not reload config: %error%");
        m.put("player-not-found","%prefix%&cFailed to find player session.");
        m.put("debug-enabled","%prefix%&fEnabled %mode% for %player%.");
        m.put("debug-disabled","%prefix%&fDisabled %mode% for %player%.");
        m.put("unknown-subcommand","%prefix%Unknown subcommand.");
        m.put("usage","%prefix%Usage: /navro %usage%");
        m.put("kick-message","%prefix%&cDisconnected for %check_name% violations.");
        m.put("disconnect-timeout","Timed out!");
        m.put("disconnect-invalid-tick","Impossible tick id=%tick%");
        return new MessageConfig(m);
    }
    public String prefix() { return templates.get("prefix"); }
    public String format(String key,Map<String,?> values) {
        String template=templates.get(key);
        if(template==null)throw new IllegalArgumentException("Unknown message: "+key);
        return render(template,values);
    }
    public String render(String template,Map<String,?> values) {
        String expanded=template.replace("%prefix%",prefix()).replace('&','§');


        var matcher=java.util.regex.Pattern.compile("%([a-z_]+)%").matcher(expanded);
        return matcher.replaceAll(match -> {
            Object value=values.get(match.group(1));
            return java.util.regex.Matcher.quoteReplacement(value==null?match.group():value.toString());
        });
    }
}
