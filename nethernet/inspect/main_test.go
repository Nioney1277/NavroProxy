package main

import "testing"

func TestRTCInterfaceSelection(t *testing.T) {
	for _, address := range []string{"", "127.0.0.1"} {
		if _, err := rtcAPI(address, true); err != nil {
			t.Fatalf("rtcAPI(%q): %v", address, err)
		}
	}
	for _, address := range []string{"not-an-ip", "0.0.0.0", "::1"} {
		if _, err := rtcAPI(address, true); err == nil {
			t.Fatalf("rtcAPI(%q) accepted an invalid IPv4 filter", address)
		}
	}
}

func TestBackendURLSecurityAndRemoteSupport(t *testing.T) {
	for _, backend := range []string{"http://192.0.2.10:19134", "http://example.com:19134", "raknet://192.0.2.10:19134", "https://user@example.com:19134", "https://example.com", "https://example.com:19134/ignored-path"} {
		if err := validateNetherNetBackend(backend); err == nil {
			t.Fatalf("accepted insecure or invalid remote backend %q", backend)
		}
	}
	for _, backend := range []string{"https://192.0.2.10:19134", "https://example.com:19134", "http://127.0.0.1:19134", "http://localhost:19134"} {
		if err := validateNetherNetBackend(backend); err != nil {
			t.Fatalf("rejected supported backend %q: %v", backend, err)
		}
	}
	for _, backend := range []string{"raknet://192.0.2.10:19134", "raknet://bedrock.example:19132", "raknet://[2001:db8::2]:19132"} {
		if _, err := rakNetBackendAddress(backend); err != nil {
			t.Fatalf("rejected remote RakNet backend %q: %v", backend, err)
		}
	}
}
