package main

import (
	"context"
	"time"

	"github.com/helix-bedrock/nethernet/endpoint"
)

func refreshBackendStatus(ctx context.Context, handler *endpoint.Handler, backend string) error {
	query, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	status, err := endpoint.NewClient().Status(query, backend)
	if err != nil {
		return err
	}

	status.OnlineAuth = true
	status.SelfSignedAuth = false
	handler.Status(status)
	return nil
}

func mirrorBackendStatus(ctx context.Context, handler *endpoint.Handler, backend string) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		_ = refreshBackendStatus(ctx, handler, backend)
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}
