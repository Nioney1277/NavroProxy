package ac.navro.proxy;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

record NetworkConfig(String frontend, String listen, String backendTransport, String backendAddress,
                     String backendAuth, String backendType, int adapterPort, boolean smoothSetback,
                     boolean backendEncryption, Path backendSigningKey, String localIp,
                     List<Path> blockPalettes, boolean backendStateSync) {
    private static final Path FILE = Path.of("plugins", "Navro", "network.yml");

    static NetworkConfig load() throws IOException {
        if (!Files.exists(FILE)) {
            Files.createDirectories(FILE.toAbsolutePath().getParent());
            try (InputStream input = NetworkConfig.class.getResourceAsStream("/network.yml")) {
                if (input == null) throw new IOException("Missing bundled network.yml");
                Files.copy(input, FILE);
            }
        }
        Map<?, ?> values;
        try (var reader = Files.newBufferedReader(FILE)) {
            Object parsed = new Yaml(new SafeConstructor(loaderOptions())).load(reader);
            if (!(parsed instanceof Map<?, ?> map)) throw new IOException("network.yml must contain a mapping");
            values = map;
        } catch (RuntimeException error) {
            throw new IOException("Could not load network.yml: " + error.getMessage(), error);
        }

        return parse(values);
    }

    static NetworkConfig parse(Map<?, ?> values) throws IOException {
        String backendTransport = string(values, "backend-transport", "raknet").toLowerCase(java.util.Locale.ROOT);
        String auth = string(values, "backend-auth", "auto").toLowerCase(java.util.Locale.ROOT);
        if (auth.equals("auto")) auth = backendTransport.equals("nethernet") ? "private" : "native";
        var config = new NetworkConfig(
                string(values, "frontend", "raknet").toLowerCase(java.util.Locale.ROOT),
                string(values, "listen", "0.0.0.0:19132"),
                backendTransport,
                string(values, "backend-address", "127.0.0.1:19133"),
                auth,
                string(values, "backend-type", "navro").toLowerCase(java.util.Locale.ROOT),
                integer(values, "adapter-port", 19138),
                bool(values, "smooth-setback", false),
                bool(values, "backend-encryption", false),
                Path.of(string(values, "backend-signing-key", "keys/navro-backend.pem")),
                string(values, "local-ip", ""),
                strings(values, "block-palettes").stream().map(Path::of).toList(),
                bool(values, "backend-state-sync", false));
        config.validate();
        return config;
    }

    boolean usesNetherNetFrontend() { return frontend.equals("nethernet"); }

    String backendUrl() {
        if (backendTransport.equals("raknet")) return "raknet://" + backendAddress;
        if (backendAddress.startsWith("http://") || backendAddress.startsWith("https://")) return backendAddress;
        return "http://" + backendAddress;
    }

    private void validate() throws IOException {
        if (!frontend.equals("raknet") && !frontend.equals("nethernet"))
            throw new IOException("frontend must be raknet or nethernet");
        if (!backendTransport.equals("raknet") && !backendTransport.equals("nethernet"))
            throw new IOException("backend-transport must be raknet or nethernet");
        if (!List.of("native", "private", "pmmp-ng", "geyser").contains(backendAuth))
            throw new IOException("backend-auth must be native, private, pmmp-ng, or geyser");
        if (backendType.equals("bds") == false && !backendType.equals("navro"))
            throw new IOException("backend-type must be navro or bds");
        if (adapterPort < 1 || adapterPort > 65535) throw new IOException("adapter-port must be between 1 and 65535");
        if (!validHostPort(listen)) throw new IOException("listen must be host:port");
        if (backendTransport.equals("raknet")) {
            if (!backendAuth.equals("native")) throw new IOException("RakNet backends require backend-auth: native");
            if (!validHostPort(backendAddress)) throw new IOException("backend-address must be host:port for RakNet");
        } else {
            if (backendAuth.equals("native")) throw new IOException("NetherNet backends require private, pmmp-ng, or geyser authentication");
            try {
                URI uri = URI.create(backendUrl());
                if ((uri.getScheme().equals("http") || uri.getScheme().equals("https")) == false ||
                        uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null ||
                        (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")))
                    throw new IOException("NetherNet backend-address must be http(s)://host:port or host:port");
                if (uri.getScheme().equals("http") && !isLoopback(uri.getHost()))
                    throw new IOException("Remote NetherNet backend-address requires HTTPS");
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid NetherNet backend-address", error);
            }
        }
        if (frontend.equals("raknet") && backendTransport.equals("nethernet") && !backendAuth.equals("private"))
            throw new IOException("RakNet frontend currently requires backend-auth: private for NetherNet backends");
        if (backendEncryption && !backendTransport.equals("raknet"))
            throw new IOException("backend-encryption is only available with a RakNet backend");
    }

    private static boolean isLoopback(String host) {
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.matches("127\\.([0-9]{1,3}\\.){2}[0-9]{1,3}");
    }

    private static boolean validHostPort(String value) {
        try {
            URI uri = URI.create("raknet://" + value);
            return uri.getHost() != null && uri.getPort() > 0 && uri.getPort() <= 65535
                    && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getFragment() == null
                    && (uri.getPath() == null || uri.getPath().isEmpty());
        } catch (IllegalArgumentException error) { return false; }
    }

    private static LoaderOptions loaderOptions() {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        return options;
    }

    private static String string(Map<?, ?> values, String key, String fallback) throws IOException {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String text)) throw new IOException(key + " must be text");
        return text;
    }

    private static boolean bool(Map<?, ?> values, String key, boolean fallback) throws IOException {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean result)) throw new IOException(key + " must be true or false");
        return result;
    }

    private static int integer(Map<?, ?> values, String key, int fallback) throws IOException {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue())
            throw new IOException(key + " must be an integer");
        return number.intValue();
    }

    private static List<String> strings(Map<?, ?> values, String key) throws IOException {
        Object value = values.get(key);
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String)))
            throw new IOException(key + " must be a list of paths");
        return list.stream().map(String.class::cast).toList();
    }
}
