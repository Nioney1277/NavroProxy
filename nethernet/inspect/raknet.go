package main

import (
	"bytes"
	"compress/flate"
	"compress/zlib"
	"context"
	"encoding/binary"
	"errors"
	"io"
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
	port, err := strconv.ParseUint(u.Port(), 10, 16)
	if u.Hostname() == "" || err != nil || port == 0 || u.User != nil ||
		(u.Path != "" && u.Path != "/") || u.ForceQuery || u.RawQuery != "" || u.Fragment != "" {
		return "", errors.New("RakNet backend requires raknet://host:port")
	}
	return u.Host, nil
}

type rakNetBatches struct {
	*raknet.Conn
}

func dialRakNetBackend(ctx context.Context, address string) (rakNetBatches, error) {
	return dialRakNetBackendVersion(ctx, address, 11)
}

func dialRakNetBackendVersion(ctx context.Context, address string, version byte) (rakNetBatches, error) {
	conn, err := (raknet.Dialer{ProtocolVersion: version}).DialContext(ctx, address)
	return rakNetBatches{conn}, err
}

type prefetchedBatches struct {
	batchConn
	first []byte
}

func (c *prefetchedBatches) ReadPacket() ([]byte, error) {
	if c.first != nil {
		first := c.first
		c.first = nil
		return first, nil
	}
	return c.batchConn.ReadPacket()
}

func initialRakNetVersion(batch []byte) (byte, error) {
	const limit = 3 << 20
	if len(batch) == 0 || len(batch) > limit {
		return 0, errors.New("invalid initial Bedrock batch size")
	}
	parse := func(data []byte) (int, bool) {
		size, n := binary.Uvarint(data)
		if n <= 0 || n > 5 || size < 5 || size > uint64(len(data)-n) {
			return 0, false
		}
		packet := data[n : n+int(size)]
		id, n := binary.Uvarint(packet)
		if n <= 0 || n > 5 || len(packet)-n < 4 {
			return 0, false
		}
		protocol := int(binary.BigEndian.Uint32(packet[n:]))
		return protocol, id == 193 && protocol >= 554 || id == 1 && protocol >= 291 && protocol < 554
	}
	if protocol, ok := parse(batch); ok {
		if protocol >= 554 {
			return 11, nil
		}
		return 10, nil
	}
	for _, wrapped := range []bool{false, true} {
		var reader io.ReadCloser
		var err error
		if wrapped {
			reader, err = zlib.NewReader(bytes.NewReader(batch))
		} else {
			reader = flate.NewReader(bytes.NewReader(batch))
		}
		if err != nil {
			continue
		}
		data, err := io.ReadAll(io.LimitReader(reader, limit+1))
		reader.Close()
		if err != nil || len(data) > limit {
			continue
		}
		if protocol, ok := parse(data); ok && protocol < 554 {
			if wrapped {
				return 9, nil
			}
			return 10, nil
		}
	}
	return 0, errors.New("missing initial Bedrock protocol declaration")
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
