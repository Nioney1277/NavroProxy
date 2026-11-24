package ac.navro.proxy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NetworkConfigTest {
    @Test void selectsAllFourTransportsWithoutObservationMode() throws Exception {
        var defaults = NetworkConfig.parse(Map.of());
        assertEquals("raknet", defaults.frontend());
        assertEquals("raknet", defaults.backendTransport());
        assertEquals("native", defaults.backendAuth());

        var nethernetToNethernet = NetworkConfig.parse(Map.of(
                "frontend", "nethernet", "backend-transport", "nethernet",
                "backend-address", "https://backend.example:19134", "backend-auth", "private"));
        assertTrue(nethernetToNethernet.usesNetherNetFrontend());
        assertEquals("https://backend.example:19134", nethernetToNethernet.backendUrl());

        var nethernetToRaknet = NetworkConfig.parse(Map.of(
                "frontend", "nethernet", "backend-transport", "raknet",
                "backend-address", "backend.example:19132", "backend-auth", "native"));
        assertEquals("raknet://backend.example:19132", nethernetToRaknet.backendUrl());

        var raknetToNethernet = NetworkConfig.parse(Map.of(
                "frontend", "raknet", "backend-transport", "nethernet",
                "backend-address", "https://backend.example:19134", "backend-auth", "private"));
        assertFalse(raknetToNethernet.usesNetherNetFrontend());
    }

    @Test void requiresTlsForRemoteNetherNetAndRejectsInvalidAuthenticationPairs() {
        assertThrows(IOException.class, () -> NetworkConfig.parse(Map.of(
                "backend-transport", "nethernet", "backend-address", "http://backend.example:19134")));
        assertThrows(IOException.class, () -> NetworkConfig.parse(Map.of(
                "frontend", "raknet", "backend-transport", "nethernet",
                "backend-address", "https://backend.example:19134", "backend-auth", "geyser")));
        assertThrows(IOException.class, () -> NetworkConfig.parse(Map.of(
                "frontend", "observer")));
        assertThrows(IOException.class, () -> NetworkConfig.parse(Map.of(
                "frontend", "raknet", "backend-transport", "nethernet",
                "backend-address", "https://backend.example:19134/ignored-path")));
    }
}
