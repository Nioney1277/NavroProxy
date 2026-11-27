package ac.navro.proxy.config;

public record RuntimeConfiguration(CheckConfig checks,MessageConfig messages,PunishmentConfig punishments,
                                   CommandSource commandSource) {
    public RuntimeConfiguration(CheckConfig checks, MessageConfig messages, PunishmentConfig punishments) {
        this(checks,messages,punishments,CommandSource.PROXY);
    }
}
