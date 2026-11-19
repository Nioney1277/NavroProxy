package ac.navro.proxy.protocol;


public enum BackendType {
    NAVRO, BDS;

    public static BackendType parse(String value) {
        return valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }
}
