package ac.navro.proxy.permission;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;


public final class ProxyPermissions {
    private final Path file;
    private final Consumer<String> log;
    public ProxyPermissions(Path file, Consumer<String> log) {
        this.file=file;this.log=log;
        try { if(!Files.exists(file))Files.writeString(file,"{\n  \"players\": {}\n}\n",StandardOpenOption.CREATE_NEW); }
        catch(IOException e){log.accept("Could not load permissions: "+e.getMessage());}
    }
    public boolean hasPermission(UUID identity,String node,boolean operator) {
        if(identity==null)return false;
        try {
            JsonObject root=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            JsonObject players=root.getAsJsonObject("players");
            if(players!=null&&players.has(identity.toString())) {
                JsonObject nodes=players.getAsJsonObject(identity.toString());
                if(nodes.has(node)) {
                    JsonElement value=nodes.get(node);
                    if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new IOException("Permission value must be boolean");
                    return value.getAsBoolean();
                }
            }
            return operator&&Set.of("navro.alert","navro.reload","navro.debug","navro.proxyping").contains(node);
        } catch(IOException|RuntimeException e) {log.accept("Could not load permissions: "+e.getMessage());return false;}
    }
}
