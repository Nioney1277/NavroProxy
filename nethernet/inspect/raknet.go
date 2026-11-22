package main

import (
	"context"
	"errors"
	"net"
	"net/url"
	"strconv"

	"github.com/sandertv/go-raknet"
)

const (
	rakNetBridgeMagic = 0x4e564237
	rakNetBatchID     = 0xfe
)

func rakNetBackendAddress(backend string) (string, error) {
	u, err := url.Parse(backend)
	if err != nil {
		return "", err
	}
	if u.Scheme != "raknet" {
		return "", nil
	}
	ip := net.ParseIP(u.Hostname())
	port, err := strconv.ParseUint(u.Port(), 10, 16)
	if ip == nil || !ip.IsLoopback() || err != nil || port == 0 || u.User != nil ||
		(u.Path != "" && u.Path != "/") || u.ForceQuery || u.RawQuery != "" || u.Fragment != "" {
		return "", errors.New("RakNet backend requires raknet:// with a numeric loopback address and port")
	}
	return u.Host, nil
}

type rakNetBatches struct {
	*raknet.Conn
}

func dialRakNetBackend(ctx context.Context, address string) (rakNetBatches, error) {
	conn, err := raknet.DialContext(ctx, address)
	return rakNetBatches{conn}, err
}

func (c rakNetBatches) ReadPacket() ([]byte, error) {
	packet, err := c.Conn.ReadPacket()
	if err != nil {
		return nil, err
	}
	if len(packet) < 2 || packet[0] != rakNetBatchID {
		return nil, errors.New("invalid RakNet backend Bedrock batch")
	}
	return packet[1:], nil
}

func (c rakNetBatches) Write(batch []byte) (int, error) {
	packet := make([]byte, len(batch)+1)
	packet[0] = rakNetBatchID
	copy(packet[1:], batch)
	if _, err := c.Conn.Write(packet); err != nil {
		return 0, err
	}
	return len(batch), nil
}
