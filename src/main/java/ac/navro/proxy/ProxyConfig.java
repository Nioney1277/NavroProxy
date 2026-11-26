package ac.navro.proxy;

import java.net.InetSocketAddress;
import java.nio.file.Path;

/** Runtime configuration for the standalone Bedrock proxy. */
public record ProxyConfig(
        String listenHost,
        int listenPort,
        String backendHost,
        int backendPort,
        int fallbackProtocolVersion,
        long maxBalanceAdvantageMs,
        boolean debug,
        ac.navro.proxy.protocol.BackendType backendType,
        boolean smoothSetback,
        boolean encryptBackend,
        Path floodgateForwardingKey,
        boolean pmmpNgBackendAuth,
        Path backendSigningKey
) {
    public ProxyConfig {
        if (floodgateForwardingKey != null && !encryptBackend) {
            throw new IllegalArgumentException("--floodgate-forwarding-key requires --encrypt-backend");
        }
        if (pmmpNgBackendAuth && backendSigningKey == null) {
            throw new IllegalArgumentException("--backend-auth pmmp-ng requires --backend-signing-key");
        }
        if (!pmmpNgBackendAuth && backendSigningKey != null) {
            throw new IllegalArgumentException("--backend-signing-key requires --backend-auth pmmp-ng");
        }
        if (pmmpNgBackendAuth && floodgateForwardingKey != null) {
            throw new IllegalArgumentException("--backend-auth pmmp-ng cannot be combined with Floodgate forwarding");
        }
    }

    public ProxyConfig(String listenHost, int listenPort, String backendHost, int backendPort,
                       int protocol, long maxBalanceAdvantageMs, boolean debug,
                       ac.navro.proxy.protocol.BackendType backendType, boolean smoothSetback, boolean encryptBackend) {
        this(listenHost, listenPort, backendHost, backendPort, protocol, maxBalanceAdvantageMs,
                debug, backendType, smoothSetback, encryptBackend, null, false, null);
    }

    public ProxyConfig(String listenHost, int listenPort, String backendHost, int backendPort,
                       int protocol, long maxBalanceAdvantageMs, boolean debug,
                       ac.navro.proxy.protocol.BackendType backendType, boolean smoothSetback) {
        this(listenHost, listenPort, backendHost, backendPort, protocol, maxBalanceAdvantageMs,
                debug, backendType, smoothSetback, false);
    }

    public ProxyConfig(String listenHost, int listenPort, String backendHost, int backendPort,
                       int protocol, long maxBalanceAdvantageMs, boolean debug,
                       ac.navro.proxy.protocol.BackendType backendType) {
        this(listenHost, listenPort, backendHost, backendPort, protocol, maxBalanceAdvantageMs,
                debug, backendType, false);
    }

    public ProxyConfig(String listenHost, int listenPort, String backendHost, int backendPort,
                       int protocol, long maxBalanceAdvantageMs, boolean debug) {
        this(listenHost, listenPort, backendHost, backendPort, protocol, maxBalanceAdvantageMs,
                debug, ac.navro.proxy.protocol.BackendType.NAVRO);
    }

    public static ProxyConfig defaults() {
        return new ProxyConfig("0.0.0.0", 19132, "127.0.0.1", 19133, 898, 8500, false);
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
        var backendType = defaults.backendType();
        boolean smoothSetback = defaults.smoothSetback();
        boolean encryptBackend = defaults.encryptBackend();
        Path floodgateForwardingKey = defaults.floodgateForwardingKey();
        boolean pmmpNgBackendAuth = false;
        Path backendSigningKey = null;

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
                case "--backend-auth" -> {
                    String auth = requireValue(args, ++i, arg);
                    if (!auth.equals("native") && !auth.equals("pmmp-ng"))
                        throw new IllegalArgumentException("RakNet backend-auth must be native or pmmp-ng");
                    pmmpNgBackendAuth = auth.equals("pmmp-ng");
                }
                case "--backend-signing-key" -> backendSigningKey = Path.of(requireValue(args, ++i, arg));
                case "--debug" -> debug = true;
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
                maxBalanceAdvantageMs, debug, backendType, smoothSetback, encryptBackend, floodgateForwardingKey,
                pmmpNgBackendAuth, backendSigningKey);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private static InetSocketAddress parseAddress(String value, int defaultPort) {
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) throw new IllegalArgumentException("Invalid IPv6 address: " + value);
            String host = value.substring(1, close);
            int port = close + 1 < value.length() && value.charAt(close + 1) == ':'
                    ? Integer.parseInt(value.substring(close + 2)) : defaultPort;
            return new InetSocketAddress(host, port);
        }
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
        System.out.println("  --encrypt-backend    require Bedrock encryption with a trusted backend");
        System.out.println("  --floodgate-forwarding-key path    PKCS#8 PEM signing key for the Geyser backend (RakNet only)");
        System.out.println("  --protocol version    fallback Bedrock protocol (default 898)");
        System.out.println("  --max-balance-advantage-ms milliseconds (default 8500, Navro Timer)");
        System.out.println("  --smooth-setback      use Boar-style soft corrections (default false; hard correction remains available)");
        System.out.println("  --debug               log packet summaries");
    }
}
