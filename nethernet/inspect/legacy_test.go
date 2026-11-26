package main

import (
	"bytes"
	"compress/flate"
	"compress/zlib"
	"context"
	"encoding/binary"
	"net"
	"testing"
	"time"

	"github.com/sandertv/go-raknet"
)

func TestLegacyRakNetProtocolsConnectAndExchangePayloads(t *testing.T) {
	listener, err := (raknet.ListenConfig{ProtocolVersions: []byte{9, 10, 11}, DisableCookies: true}).Listen("127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	for _, version := range []byte{9, 10, 11} {
		t.Run(string(rune('0'+version)), func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			accepted := make(chan net.Conn, 1)
			go func() {
				c, e := listener.Accept()
				if e == nil {
					accepted <- c
				}
			}()
			client, err := (raknet.Dialer{ProtocolVersion: version}).DialContext(ctx, listener.Addr().String())
			if err != nil {
				t.Fatal(err)
			}
			defer client.Close()
			var server net.Conn
			select {
			case server = <-accepted:
			case <-ctx.Done():
				t.Fatal(ctx.Err())
			}
			defer server.Close()
			server.SetReadDeadline(time.Now().Add(3 * time.Second))
			payload := []byte{0xfe, 1, 2, 3, 4, 5}
			if _, err = client.Write(payload); err != nil {
				t.Fatal(err)
			}
			buffer := make([]byte, 128)
			n, err := server.Read(buffer)
			if err != nil || !bytes.Equal(buffer[:n], payload) {
				t.Fatalf("version %d payload=%x error=%v", version, buffer[:n], err)
			}
		})
	}
}

func TestInitialBatchChoosesLegacyOrModernRakNet(t *testing.T) {
	for _, protocol := range []uint32{291, 419, 554, 622, 729, 818, 2225} {
		id := uint64(1)
		if protocol >= 554 {
			id = 193
		}
		packet := binary.AppendUvarint(nil, id)
		packet = binary.BigEndian.AppendUint32(packet, protocol)
		batch := binary.AppendUvarint(nil, uint64(len(packet)))
		batch = append(batch, packet...)
		for encoding := 0; encoding < 3; encoding++ {
			wire := batch
			var encoded bytes.Buffer
			if encoding == 1 {
				w, _ := flate.NewWriter(&encoded, 6)
				w.Write(batch)
				w.Close()
				wire = encoded.Bytes()
			}
			if encoding == 2 {
				w := zlib.NewWriter(&encoded)
				w.Write(batch)
				w.Close()
				wire = encoded.Bytes()
			}
			if protocol >= 554 && encoding != 0 {
				continue
			}
			want := byte(10)
			if protocol >= 554 {
				want = 11
			} else if encoding == 2 {
				want = 9
			}
			got, err := initialRakNetVersion(wire)
			if err != nil || got != want {
				t.Fatalf("protocol %d encoding %d: got=%d want=%d err=%v", protocol, encoding, got, want, err)
			}
		}
	}
	for _, bad := range [][]byte{nil, {1}, {6, 193, 1}, {0xff, 0xff, 0xff}} {
		if _, err := initialRakNetVersion(bad); err == nil {
			t.Fatalf("accepted %x", bad)
		}
	}
}
