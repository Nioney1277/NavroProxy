package ac.navro.proxy.config;

public enum CommandSource {
    PROXY, BRIDGE;
    public static CommandSource parse(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("command-source must be proxy or bridge");
        return switch (text.toLowerCase(java.util.Locale.ROOT)) {
            case "proxy" -> PROXY;
            case "bridge" -> BRIDGE;
            default -> throw new IllegalArgumentException("command-source must be proxy or bridge");
        };
    }
    public String value() { return name().toLowerCase(java.util.Locale.ROOT); }
}
