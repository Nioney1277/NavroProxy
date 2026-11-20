import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.geysermc.geyser.registry.BlockRegistries;

public final class GeyserRuntimePaletteExporter {
    public static void premain(String argument, Instrumentation instrumentation) {
        var export = new Thread(() -> {
            try {
                int separator = argument.indexOf(':');
                if (separator < 1) throw new IllegalArgumentException("Expected protocol:path");
                int protocol = Integer.parseInt(argument.substring(0, separator));
                Path target = Path.of(argument.substring(separator + 1));
                for (int attempt = 0; attempt < 300; attempt++) {
                    for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                        if (!loaded.getName().equals("org.geysermc.geyser.registry.BlockRegistries")) continue;
                        var mapping = BlockRegistries.BLOCKS.get(protocol);
                        if (mapping != null) {
                            var states = new ArrayList<NbtMap>();
                            for (var definition : mapping.getBedrockRuntimeMap()) {
                                if (definition.getRuntimeId() != states.size())
                                    throw new IllegalStateException("Non-sequential Geyser runtime IDs");
                                states.add(definition.getState());
                            }
                            var root = NbtMap.builder().putInt("protocol", protocol)
                                    .putList("states", NbtType.COMPOUND, states).build();
                            try (var out = NbtUtils.createGZIPWriter(Files.newOutputStream(target))) {
                                out.writeTag(root);
                            }
                            System.out.println("NAVRO_PALETTE_EXPORTED " + protocol + " " + states.size());
                            return;
                        }
                        break;
                    }
                    Thread.sleep(250);
                }
                throw new IllegalStateException("Geyser did not initialize the requested protocol");
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }, "Navro runtime palette exporter");
        export.setDaemon(true);
        export.start();
    }
}
