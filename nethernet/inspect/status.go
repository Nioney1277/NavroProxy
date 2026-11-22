package main

import (
	"context"
	"time"

	"github.com/helix-bedrock/nethernet/endpoint"
	"github.com/sandertv/go-raknet"
)

func refreshBackendStatus(ctx context.Context, handler *endpoint.Handler, backend, rakNetAddress string) error {
	query, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	var status endpoint.Status
	var err error
	if rakNetAddress == "" {
		status, err = endpoint.NewClient().Status(query, backend)
	} else {
		var pong []byte
		pong, err = raknet.PingContext(query, rakNetAddress)
		if err == nil {
			status, err = endpoint.RakNetPongData(pong)
		}
	}
	if err != nil {
		return err
	}

	status.OnlineAuth = true
	status.SelfSignedAuth = false
	handler.Status(status)
	return nil
}

func mirrorBackendStatus(ctx context.Context, handler *endpoint.Handler, backend, rakNetAddress string) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		_ = refreshBackendStatus(ctx, handler, backend, rakNetAddress)
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}
