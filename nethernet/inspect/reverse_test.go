package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/x509"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"testing"
	"time"

	"github.com/helix-bedrock/nethernet"
	"github.com/helix-bedrock/nethernet/endpoint"
	"github.com/sandertv/go-raknet"
)

func TestReverseEndpointAndModeBoundaries(t *testing.T) {
	for _, backend := range []string{
		"raknet://127.0.0.1:19134", "http://192.0.2.1:19134",
		"http://203.0.113.1:19134", "http://127.0.0.1", "http://127.0.0.1:0", "http://user@127.0.0.1:19134",
		"http://127.0.0.1:19134/path", "http://127.0.0.1:19134?", "http://127.0.0.1:19134#fragment",
	} {
		if err := validateReverseEndpoints(backend, "127.0.0.1:19138"); err == nil {
			t.Errorf("accepted %q", backend)
		}
	}
	for _, adapter := range []string{"192.0.2.1:19138", "localhost:19138", "127.0.0.1:0", "127.0.0.1"} {
		if err := validateReverseEndpoints("http://127.0.0.1:19134", adapter); err == nil {
			t.Errorf("accepted IPC %q", adapter)
		}
	}
	if err := validateReverseEndpoints("http://127.0.0.1:19134", "127.0.0.1:19138"); err != nil {
		t.Fatal(err)
	}
	if err := validateReverseEndpoints("https://bedrock.example:19134", "127.0.0.1:19138"); err != nil {
		t.Fatalf("rejected remote HTTPS backend: %v", err)
	}
	for _, backend := range []string{"https://192.0.2.8:19134", "https://bedrock.example:19134"} {
		if err := validateReverseEndpoints(backend, "127.0.0.1:19138"); err != nil {
			t.Errorf("rejected remote secure backend %q: %v", backend, err)
		}
	}
	for _, auth := range []string{"pmmp-ng", "geyser"} {
		if err := runRakNetFrontend("127.0.0.1:0", "http://127.0.0.1:19134", "127.0.0.1:19138", auth); err == nil {
			t.Errorf("accepted unsupported reverse backend auth %q", auth)
		}
	}
}

func TestReversePongUsesFrontendPortAndGuid(t *testing.T) {
	status := endpoint.Status{ServerName: "Reverse", Protocol: 2225, Version: "26.60", MaxPlayerCount: 20}
	data := reversePong(status, 12345, 19345)
	if !bytes.Contains(data, []byte(";12345;")) || !bytes.Contains(data, []byte(";19345;19345;")) {
		t.Fatalf("wrong pong: %s", data)
	}
	got, err := endpoint.RakNetPongData(data)
	if err != nil || got.ServerName != status.ServerName || got.Protocol != 2225 {
		t.Fatalf("bad status: %+v %v", got, err)
	}
}

func TestReverseTransportRealRakNetAndNetherNet(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	api, err := rtcAPI("127.0.0.1", false)
	if err != nil {
		t.Fatal(err)
	}
	handler, err := endpoint.Serve("127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer handler.Close()
	backListener, err := (nethernet.ListenConfig{API: api, DisableTrickleICE: true}).Listen(handler)
	if err != nil {
		t.Fatal(err)
	}
	defer backListener.Close()
	frontListener, err := raknet.Listen("127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer frontListener.Close()
	adapter, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer adapter.Close()
	expectedKey := make(chan []byte, 1)
	errorsCh := make(chan error, 3)
	go func() {
		conn, err := adapter.Accept()
		if err != nil {
			errorsCh <- err
			return
		}
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(25 * time.Second))
		in := bufio.NewReader(conn)
		out := bufio.NewWriter(conn)
		var magic, sz uint32
		if err = binary.Read(in, binary.BigEndian, &magic); err != nil {
			errorsCh <- err
			return
		}
		if magic != rakNetFrontendMagic {
			errorsCh <- fmt.Errorf("wrong magic %x", magic)
			return
		}
		if err = binary.Read(in, binary.BigEndian, &sz); err != nil || sz != 0 {
			errorsCh <- fmt.Errorf("unexpected transport key: %v %d", err, sz)
			return
		}
		if err = binary.Read(in, binary.BigEndian, &sz); err != nil {
			errorsCh <- err
			return
		}
		public := make([]byte, sz)
		if _, err = io.ReadFull(in, public); err != nil {
			errorsCh <- err
			return
		}
		if err = binary.Read(in, binary.BigEndian, &sz); err != nil {
			errorsCh <- err
			return
		}
		private := make([]byte, sz)
		if _, err = io.ReadFull(in, private); err != nil {
			errorsCh <- err
			return
		}
		if _, err = x509.ParsePKCS8PrivateKey(private); err != nil {
			errorsCh <- err
			return
		}
		clear(private)
		expectedKey <- public
		binary.Write(out, binary.BigEndian, magic)
		out.Flush()
		// A transport-only adapter fixture. Java tests separately exercise real login validation and encryption.
		for {
			count, err := in.ReadByte()
			if err != nil {
				if !errors.Is(err, io.EOF) {
					errorsCh <- err
				}
				return
			}
			for range int(count) {
				direction, err := in.ReadByte()
				if err != nil {
					errorsCh <- err
					return
				}
				if err = binary.Read(in, binary.BigEndian, &sz); err != nil {
					errorsCh <- err
					return
				}
				batch := make([]byte, sz)
				if _, err = io.ReadFull(in, batch); err != nil {
					errorsCh <- err
					return
				}
				size := int32(len(batch))
				if direction == 2 {
					size = -1
				}
				binary.Write(out, binary.BigEndian, size)
				out.Write(batch)
				binary.Write(out, binary.BigEndian, uint32(0))
				binary.Write(out, binary.BigEndian, uint32(0))
			}
			if err = out.Flush(); err != nil {
				errorsCh <- err
				return
			}
		}
	}()
	go func() {
		front, err := frontListener.Accept()
		if err != nil {
			errorsCh <- err
			return
		}
		errorsCh <- reverseSession(ctx, front.(*raknet.Conn), "http://"+handler.Addr().String(), adapter.Addr().String(), api)
	}()
	go func() {
		back, err := backListener.Accept()
		if err != nil {
			errorsCh <- err
			return
		}
		defer back.Close()
		key := <-expectedKey
		public, err := x509.MarshalPKIXPublicKey(back.(*nethernet.Conn).PublicKey())
		if err != nil {
			errorsCh <- err
			return
		}
		if !bytes.Equal(key, public) {
			errorsCh <- errors.New("IPC signing key does not match actual backend DTLS identity")
			return
		}
		batch, err := back.(*nethernet.Conn).ReadPacket()
		if err != nil {
			errorsCh <- err
			return
		}
		if !bytes.Equal(batch, []byte{1, 2, 3}) {
			errorsCh <- fmt.Errorf("wrong backend batch: %x", batch)
			return
		}
		_, err = back.Write([]byte{4, 5, 6})
		if err != nil {
			errorsCh <- err
			return
		}
		<-ctx.Done()
	}()
	front, err := raknet.DialContext(ctx, frontListener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer front.Close()
	front.SetDeadline(time.Now().Add(25 * time.Second))
	if _, err = front.Write([]byte{0xfe, 1, 2, 3}); err != nil {
		t.Fatal(err)
	}
	batch, err := front.ReadPacket()
	if err != nil {
		select {
		case problem := <-errorsCh:
			t.Fatalf("transport: %v; read: %v", problem, err)
		default:
			t.Fatal(err)
		}
	}
	if !bytes.Equal(batch, []byte{0xfe, 4, 5, 6}) {
		t.Fatalf("wrong RakNet batch %x", batch)
	}
	cancel()
}
