package ac.navro.proxy;

import java.net.InetSocketAddress;
import java.nio.file.Path;


public record ProxyConfig(
        String listenHost,
        int listenPort,
        String backendHost,
        int backendPort,
        int fallbackProtocolVersion,
        long maxBalanceAdvantageMs,
        boolean debug,
        Mode mode,
        ac.navro.proxy.protocol.BackendType backendType,
        boolean smoothSetback,
        boolean encryptBackend,
        Path floodgateForwardingKey
) {
    public ProxyConfig {
        if (encryptBackend && !"127.0.0.1".equals(backendHost)) {
            throw new IllegalArgumentException("--encrypt-backend requires an explicit 127.0.0.1 backend");
        }
        if (floodgateForwardingKey != null && !encryptBackend) {
            throw new IllegalArgumentException("--floodgate-forwarding-key requires --encrypt-backend");
        }
    }

    public ProxyConfig(String listenHost, int listenPort, String backendHost, int backendPort,
                       int protocol, long maxBalanceAdvantageMs, boolean debug, Mode mode,
                       ac.navro.proxy.protocol.BackendType backendType, boolean smoothSetback, boolean encryptBackend) {
        this(listenHost, listenPort, backendHost, backendPort, protocol, maxBalanceAdvantageMs,
                debug, mode, backendType, smoothSetback, encryptBackend, null);
    }

    public ProxyConfig(String listenHost, int listenPort, String backendHost, int backendPort,
                       int protocol, long maxBalanceAdvantageMs, boolean debug, Mode mode,
                       ac.navro.proxy.protocol.BackendType backendType, boolean smoothSetback) {
        this(listenHost, listenPort, backendHost, backendPort, protocol, maxBalanceAdvantageMs,
                debug, mode, backendType, smoothSetback, false);
    }

    public ProxyConfig(String listenHost, int listenPort, String backendHost, int backendPort,
                       int protocol, long maxBalanceAdvantageMs, boolean debug, Mode mode,
                       ac.navro.proxy.protocol.BackendType backendType) {
        this(listenHost, listenPort, backendHost, backendPort, protocol, maxBalanceAdvantageMs,
                debug, mode, backendType, false);
    }

    public ProxyConfig(String listenHost, int listenPort, String backendHost, int backendPort,
                       int protocol, long maxBalanceAdvantageMs, boolean debug, Mode mode) {
        this(listenHost, listenPort, backendHost, backendPort, protocol, maxBalanceAdvantageMs,
                debug, mode, ac.navro.proxy.protocol.BackendType.NAVRO);
    }

    public enum Mode {
        INSPECT, DETECT
    }

    public static ProxyConfig defaults() {
        return new ProxyConfig("0.0.0.0", 19132, "127.0.0.1", 19133, 898, 8500, false, Mode.DETECT);
    }

    public static ProxyConfig parse(String[] args) {
        ProxyConfig defaults = defaults();
        String listenHost = defaults.listenHost();
        int listenPort = defaults.listenPort();
        String backendHost = defaults.backendHost();
        int backendPort = defaults.backendPort();
        int protocol = defaults.fallbackProtocolVersion();
        long maxBalanceAdvantageMs = defaults.maxBalanceAdvantageMs();
        boolean debug = defaults.debug();
        Mode mode = defaults.mode();
        var backendType = defaults.backendType();
        boolean smoothSetback = defaults.smoothSetback();
        boolean encryptBackend = defaults.encryptBackend();
        Path floodgateForwardingKey = defaults.floodgateForwardingKey();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--listen" -> {
                    String value = requireValue(args, ++i, arg);
                    InetSocketAddress address = parseAddress(value, listenPort);
                    listenHost = address.getHostString();
                    listenPort = address.getPort();
                }
                case "--backend" -> {
                    String value = requireValue(args, ++i, arg);
                    InetSocketAddress address = parseAddress(value, backendPort);
                    backendHost = address.getHostString();
                    backendPort = address.getPort();
                }
                case "--backend-type" -> backendType = ac.navro.proxy.protocol.BackendType.parse(requireValue(args, ++i, arg));
                case "--protocol" -> protocol = Integer.parseInt(requireValue(args, ++i, arg));
                case "--max-balance-advantage-ms" -> maxBalanceAdvantageMs = Long.parseLong(requireValue(args, ++i, arg));
                case "--smooth-setback" -> smoothSetback = true;
                case "--encrypt-backend" -> encryptBackend = true;
                case "--floodgate-forwarding-key" -> floodgateForwardingKey = Path.of(requireValue(args, ++i, arg));
                case "--debug" -> debug = true;
                case "--mode" -> mode = Mode.valueOf(requireValue(args, ++i, arg).toUpperCase(java.util.Locale.ROOT));
                case "--inspect" -> mode = Mode.INSPECT;
                case "--detect" -> mode = Mode.DETECT;
                case "--help", "-h" -> {
                    printUsage();
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }

        if (listenPort < 1 || listenPort > 65535 || backendPort < 1 || backendPort > 65535) {
            throw new IllegalArgumentException("Ports must be between 1 and 65535");
        }
        return new ProxyConfig(listenHost, listenPort, backendHost, backendPort, protocol,
                maxBalanceAdvantageMs, debug, mode, backendType, smoothSetback, encryptBackend, floodgateForwardingKey);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private static InetSocketAddress parseAddress(String value, int defaultPort) {
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            return new InetSocketAddress(value, defaultPort);
        }
        return new InetSocketAddress(value.substring(0, separator), Integer.parseInt(value.substring(separator + 1)));
    }

    private static void printUsage() {
        System.out.println("NavroProxy options:");
        System.out.println("  --listen host:port   listen address (default 0.0.0.0:19132)");
        System.out.println("  --backend host:port  Bedrock backend (default 127.0.0.1:19133)");
        System.out.println("  --backend-type navro|bds  movement semantics (default navro)");
        System.out.println("  --encrypt-backend    require Bedrock encryption with a trusted 127.0.0.1 backend");
        System.out.println("  --floodgate-forwarding-key path    PKCS#8 PEM signing key for the Geyser backend (RakNet only)");
        System.out.println("  --protocol version    fallback Bedrock protocol (default 898)");
        System.out.println("  --max-balance-advantage-ms milliseconds (default 8500, Navro Timer)");
        System.out.println("  --smooth-setback      use Boar-style soft corrections (default false; hard correction remains available)");
        System.out.println("  --debug               log packet summaries");
        System.out.println("  --mode detect|inspect    default detect; inspect observes without checks");
    }
}
