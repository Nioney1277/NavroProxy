package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/helix-bedrock/nethernet/endpoint"
)

func TestBackendDisplayStatusIsPreservedAndRefreshed(t *testing.T) {
	status := endpoint.Status{ServerName: "原服务器 §a测试", LevelName: "原世界简介",
		Protocol: 2225, Version: "1.26.63", PlayerCount: 3, MaxPlayerCount: 27, GameType: 1}
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/join" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		_ = json.NewEncoder(w).Encode(status)
	}))
	handler := endpoint.NewHandler()
	defer handler.Close()
	frontend := httptest.NewServer(handler)
	defer frontend.Close()
	for _, name := range []string{status.ServerName, "后端更改后的名字", ""} {
		status.ServerName = name
		if name == "" {
			status.LevelName = ""
		}
		if err := refreshBackendStatus(context.Background(), handler, backend.URL, ""); err != nil {
			t.Fatal(err)
		}
		actual, err := endpoint.NewClient().Status(context.Background(), frontend.URL)
		if err != nil {
			t.Fatal(err)
		}
		if actual.ServerName != status.ServerName || actual.LevelName != status.LevelName ||
			actual.Protocol != status.Protocol || actual.Version != status.Version ||
			actual.PlayerCount != status.PlayerCount || actual.MaxPlayerCount != status.MaxPlayerCount ||
			actual.GameType != status.GameType {
			t.Fatalf("display changed: %+v", actual)
		}
	}
	backend.Close()
	if err := refreshBackendStatus(context.Background(), handler, backend.URL, ""); err == nil {
		t.Fatal("expected query failure")
	}
	actual, err := endpoint.NewClient().Status(context.Background(), frontend.URL)
	if err != nil {
		t.Fatal(err)
	}
	if actual.ServerName != status.ServerName {
		t.Fatalf("lost last backend name: %+v", actual)
	}
}
