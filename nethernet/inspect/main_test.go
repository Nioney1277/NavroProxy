package main

import "testing"

func TestRTCInterfaceSelection(t *testing.T) {
	for _, address := range []string{"", "127.0.0.1"} {
		if _, err := rtcAPI(address); err != nil {
			t.Fatalf("rtcAPI(%q): %v", address, err)
		}
	}
	for _, address := range []string{"not-an-ip", "0.0.0.0", "::1"} {
		if _, err := rtcAPI(address); err == nil {
			t.Fatalf("rtcAPI(%q) accepted an invalid IPv4 filter", address)
		}
	}
}

func TestPrivateBackendAndAdapterBoundaries(t *testing.T) {
	for _, scenario := range []struct {
		backend string
		adapter string
		offline bool
		stock   bool
	}{
		{"http://192.0.2.10:19134", "127.0.0.1:19138", true, false},
		{"http://192.0.2.10:19134", "127.0.0.1:19138", false, true},
		{"http://127.0.0.1:19134", "192.0.2.10:19138", true, false},
	} {
		if err := run("127.0.0.1:0", scenario.backend, scenario.adapter, "", "", scenario.offline, true, scenario.stock); err == nil {
			t.Fatalf("accepted a public private-backend or IPC endpoint: %+v", scenario)
		}
	}
}
