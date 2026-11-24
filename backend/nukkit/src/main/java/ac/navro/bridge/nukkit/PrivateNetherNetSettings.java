package ac.navro.bridge.nukkit;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

record PrivateNetherNetSettings(InetAddress signalingBind, int signalingPort, InetAddress iceBind,
                               String advertisedAddress, int iceBegin, int iceEnd, int maxConnections,
                               Path publicKey, Path identityFile) {
    static PrivateNetherNetSettings parse(Map<?, ?> values, Path folder) throws Exception {
        String bind = text(values, "signaling-bind-address", "127.0.0.1");
        String ice = text(values, "ice-bind-address", "127.0.0.1");
        String advertised = text(values, "advertised-address", "127.0.0.1");
        if (!advertised.matches("[0-9a-fA-F:.]+") || InetAddress.getByName(advertised).isAnyLocalAddress())
            throw new IllegalArgumentException("advertised-address must be a reachable literal IP");
        Object raw = values.get("ice-port-range");
        List<?> ports = raw == null ? List.of(20000, 20127) : raw instanceof List<?> list ? list : List.of();
        if (ports.size() != 2) throw new IllegalArgumentException("ice-port-range must contain two ports");
        int begin = number(ports.get(0), 1, 65535, "ice-port-range");
        int end = number(ports.get(1), begin, 65535, "ice-port-range");
        return new PrivateNetherNetSettings(InetAddress.getByName(bind), integer(values,"signaling-port",19608,1,65535),
                InetAddress.getByName(ice), advertised, begin, end, integer(values,"max-connections",128,1,1024),
                folder.resolve(text(values,"proxy-public-key-file","navro-backend.pub.pem")),
                folder.resolve("keys/private-nethernet-identity.pem"));
    }
    private static String text(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String s) || s.isBlank()) throw new IllegalArgumentException(key + " must be text");
        return s;
    }
    private static int integer(Map<?, ?> map, String key, int fallback, int min, int max) {
        Object value = map.get(key); return value == null ? fallback : number(value,min,max,key);
    }
    private static int number(Object value, int min, int max, String key) {
        if (!(value instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < min || n.intValue() > max)
            throw new IllegalArgumentException(key + " must be an integer between " + min + " and " + max);
        return n.intValue();
    }
}
