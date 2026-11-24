package main

import (
	"bufio"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/binary"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/url"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/helix-bedrock/nethernet"
	"github.com/helix-bedrock/nethernet/endpoint"
	"github.com/pion/webrtc/v4"
	"github.com/sandertv/go-raknet"
)

const rakNetFrontendMagic = 0x4e564238

func validateNetherNetBackend(backend string) error {
	u, err := url.Parse(backend)
	if err != nil {
		return err
	}
	port, err := strconv.ParseUint(u.Port(), 10, 16)
	if (u.Scheme != "http" && u.Scheme != "https") || u.Hostname() == "" || err != nil || port == 0 ||
		u.User != nil || (u.Path != "" && u.Path != "/") || u.ForceQuery || u.RawQuery != "" || u.Fragment != "" {
		return errors.New("NetherNet backend requires an http(s) URL with an explicit port")
	}
	if u.Scheme != "https" && !loopbackHost(u.Hostname()) {
		return errors.New("remote NetherNet backends require HTTPS signaling")
	}
	return nil
}

func validateReverseEndpoints(backend, adapter string) error {
	u, err := url.Parse(backend)
	if err != nil {
		return err
	}
	port, err := strconv.ParseUint(u.Port(), 10, 16)
	if (u.Scheme != "http" && u.Scheme != "https") || u.Hostname() == "" || err != nil || port == 0 ||
		u.User != nil || (u.Path != "" && u.Path != "/") || u.ForceQuery || u.RawQuery != "" || u.Fragment != "" {
		return errors.New("RakNet frontend requires an http(s):// NetherNet backend with an explicit port")
	}
	if err := validateNetherNetBackend(backend); err != nil {
		return err
	}
	host, portText, err := net.SplitHostPort(adapter)
	if err != nil {
		return err
	}
	ip := net.ParseIP(host)
	port, err = strconv.ParseUint(portText, 10, 16)
	if ip == nil || !ip.IsLoopback() || err != nil || port == 0 {
		return errors.New("RakNet frontend batch adapter must be an explicit loopback endpoint")
	}
	return nil
}

func runRakNetFrontend(listen, backend, adapter, backendAuth string) error {
	if backendAuth != "private" {
		return errors.New("RakNet frontend currently requires -backend-auth private")
	}
	if err := validateReverseEndpoints(backend, adapter); err != nil {
		return err
	}
	api, err := rtcAPI("", true)
	if err != nil {
		return err
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	listener, err := (raknet.ListenConfig{}).Listen(listen)
	if err != nil {
		return err
	}
	defer listener.Close()
	go func() { <-ctx.Done(); listener.Close() }()
	go mirrorReverseStatus(ctx, listener, backend)
	slots := make(chan struct{}, 32)
	var sessions sync.WaitGroup
	defer sessions.Wait()
	defer cancel()
	slog.Info("RAKNET_NETHERNET_READY", "listen", listener.Addr(), "backend", backend, "adapter", adapter, "mode", "DETECT")
	for {
		conn, err := listener.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		select {
		case slots <- struct{}{}:
			sessions.Add(1)
			go func(front *raknet.Conn) {
				defer sessions.Done()
				defer func() { <-slots }()
				if err := reverseSession(ctx, front, backend, adapter, api); err != nil {
					slog.Info("raknet_nethernet_session_closed", "error", err)
				}
			}(conn.(*raknet.Conn))
		default:
			conn.Close()
		}
	}
}

func reverseSession(ctx context.Context, front *raknet.Conn, backend, adapter string, api *webrtc.API) error {
	defer front.Close()
	sessionCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	go func() {
		select {
		case <-front.Context().Done():
			cancel()
		case <-sessionCtx.Done():
		}
	}()
	stopFront := context.AfterFunc(sessionCtx, func() { front.Close() })
	defer stopFront()
	ipc, err := (&net.Dialer{Timeout: 5 * time.Second}).DialContext(sessionCtx, "tcp", adapter)
	if err != nil {
		return err
	}
	defer ipc.Close()
	stopIPC := context.AfterFunc(sessionCtx, func() { ipc.Close() })
	defer stopIPC()
	if err := ipc.SetDeadline(time.Now().Add(10 * time.Second)); err != nil {
		return err
	}
	key, err := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	if err != nil {
		return err
	}
	public, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		return err
	}
	private, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return err
	}
	out := bufio.NewWriter(ipc)
	in := bufio.NewReader(ipc)
	if err := binary.Write(out, binary.BigEndian, uint32(rakNetFrontendMagic)); err != nil {
		return err
	}
	// RakNet authenticates in the Java login/Bedrock encryption handshake, not DTLS.
	if err := binary.Write(out, binary.BigEndian, uint32(0)); err != nil {
		return err
	}
	for _, data := range [][]byte{public, private} {
		if err := binary.Write(out, binary.BigEndian, uint32(len(data))); err != nil {
			clear(private)
			return err
		}
		if _, err := out.Write(data); err != nil {
			clear(private)
			return err
		}
	}
	if err := out.Flush(); err != nil {
		clear(private)
		return err
	}
	clear(private)
	var accepted uint32
	if err := binary.Read(in, binary.BigEndian, &accepted); err != nil {
		return fmt.Errorf("RakNet frontend adapter handshake: %w", err)
	}
	if accepted != rakNetFrontendMagic {
		return errors.New("adapter did not confirm RakNet frontend mode")
	}
	identity, err := nethernet.SelfSignedIdentity(key, "https://authorization.franchise.minecraft-services.net/")
	if err != nil {
		return err
	}
	dialCtx, stopDial := context.WithTimeout(sessionCtx, 25*time.Second)
	back, err := (nethernet.Dialer{API: api, Identity: identity, DisableTrickleICE: true}).DialContext(dialCtx, backend, stockSignaling())
	stopDial()
	if err != nil {
		return fmt.Errorf("dial NetherNet backend: %w", err)
	}
	defer back.Close()
	return bridgeBatches(rakNetBatches{front}, back, ipc, in, out)
}

func loopbackHost(host string) bool {
	if strings.EqualFold(host, "localhost") {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func mirrorReverseStatus(ctx context.Context, listener *raknet.Listener, backend string) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		query, cancel := context.WithTimeout(ctx, 3*time.Second)
		status, err := stockSignaling().Status(query, backend)
		cancel()
		if err == nil {
			listener.PongData(reversePong(status, listener.ID(), listener.Addr().(*net.UDPAddr).Port))
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func reversePong(status endpoint.Status, guid int64, port int) []byte {
	fields := strings.Split(string(status.RakNet()), ";")
	fields[6] = strconv.FormatInt(guid, 10)
	fields[10], fields[11] = strconv.Itoa(port), strconv.Itoa(port)
	return []byte(strings.Join(fields, ";"))
}
