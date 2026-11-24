package ac.navro.proxy.command;

import ac.navro.proxy.config.CheckConfig;
import ac.navro.proxy.config.ConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NavroConfigResourceTest {
    @TempDir Path directory;

    @Test void missingConfigIsReleasedByteForByteAtTheOriginalDataFolderPath()throws Exception {
        assertEquals(Path.of("plugins","Navro","config.yml"),ConfigLoader.DEFAULT_FILE);
        byte[] expected;
        try(var resource=ConfigLoader.class.getResourceAsStream("/config.yml")){
            assertNotNull(resource);expected=resource.readAllBytes();
        }
        var file=directory.resolve(ConfigLoader.DEFAULT_FILE);
        var config=ConfigLoader.load(file,CheckConfig.defaults(false,8500));
        assertArrayEquals(expected,Files.readAllBytes(file));
        assertFalse(Files.exists(directory.resolve("config.yml")));
        assertEquals(20,config.rewindHistory());assertEquals(8500,config.maxBalanceAdvantage());
        var options=new org.yaml.snakeyaml.LoaderOptions();
        var yaml=new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(options));
        java.util.Map<?,?> values=yaml.load(Files.readString(file));
        assertEquals(java.util.Set.of("player-rewind-history-size-ticks","smooth-setback",
                "player-position-acceptance-threshold","differ-till-alert","disabled-checks",
                "max-latency-wait","max-balance-advantage"),values.keySet());
        assertFalse(values.keySet().stream().anyMatch(key->key.toString().contains("debug")||key.toString().contains("sampling")));
    }

    @Test void reloadKeepsExistingUserContentAndLoadsTheSameFile()throws Exception {
        var file=directory.resolve(ConfigLoader.DEFAULT_FILE);
        var runtime=new CommandRuntime(file,false,8500);
        String content="max-balance-advantage: 4200\nsmooth-setback: true\n";
        Files.writeString(file,content);
        Files.writeString(directory.resolve("plugins/Navro/messages.yml"),"prefix: '&bConfigured '\n");
        runtime.reload();
        assertEquals("&bConfigured ",runtime.config().prefix());
        assertEquals(4200,runtime.config().maxBalanceAdvantage());
        assertEquals(content,Files.readString(file));
        Files.writeString(file,"smooth-setback: [broken\n");
        assertThrows(java.io.IOException.class,runtime::reload);
        assertEquals("&bConfigured ",runtime.config().prefix());
        assertEquals("smooth-setback: [broken\n",Files.readString(file));
    }
}
