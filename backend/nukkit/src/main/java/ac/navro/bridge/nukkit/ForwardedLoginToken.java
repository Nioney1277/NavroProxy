package ac.navro.bridge.nukkit;

import cn.nukkit.utils.BinaryStream;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;

/** Reads either a pre-1.26 certificate or the modern token without trusting its claims. */
final class ForwardedLoginToken {
    private ForwardedLoginToken() { }
    static String read(BinaryStream input) {
        int size = input.getLInt();
        if (size < 1 || size > 65536) throw new IllegalArgumentException("Invalid forwarded auth size");
        var auth = JsonParser.parseString(new String(input.get(size), StandardCharsets.UTF_8)).getAsJsonObject();
        if (auth.has("AuthenticationType") && auth.get("AuthenticationType").getAsInt() != 2)
            throw new IllegalArgumentException("Private listener requires a SELF_SIGNED proxy login");
        if (auth.has("Token") && auth.get("Token").isJsonPrimitive() && !auth.get("Token").getAsString().isEmpty()) {
            if (!auth.has("AuthenticationType")) throw new IllegalArgumentException("Missing proxy authentication type");
            return auth.get("Token").getAsString();
        }
        JsonObject certificate = auth;
        if (auth.has("Certificate")) certificate = JsonParser.parseString(auth.get("Certificate").getAsString()).getAsJsonObject();
        if (!certificate.has("chain") || !certificate.get("chain").isJsonArray())
            throw new IllegalArgumentException("Missing proxy certificate chain");
        var chain = certificate.getAsJsonArray("chain");
        if (chain.size() != 1 || !chain.get(0).isJsonPrimitive() || !chain.get(0).getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected exactly one proxy certificate");
        return chain.get(0).getAsString();
    }
}
