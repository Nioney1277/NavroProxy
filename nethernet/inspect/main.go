package main

import (
	"bufio"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/binary"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/helix-bedrock/nethernet"
	"github.com/helix-bedrock/nethernet/endpoint"
	"github.com/pion/webrtc/v4"
)

func rtcAPI(local string, publicPorts bool) (*webrtc.API, error) {
	var s webrtc.SettingEngine
	nethernet.ApplySettings(&s)
	s.SetIncludeLoopbackCandidate(true)
	s.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	if local != "" {
		ip := net.ParseIP(local)
		if ip == nil || ip.To4() == nil || ip.IsUnspecified() {
			return nil, fmt.Errorf("local-ip must be a local IPv4 address")
		}
		s.SetIPFilter(func(candidate net.IP) bool { return candidate.IsLoopback() || candidate.Equal(ip) })
	}
	if publicPorts {
		if err := s.SetEphemeralUDPPortRange(19210, 19273); err != nil {
			return nil, err
		}
	}
	return webrtc.NewAPI(webrtc.WithSettingEngine(s)), nil
}

func main() {
	listen := flag.String("listen", "0.0.0.0:19132", "Independent inspection HTTP/TCP signaling listener")
	backend := flag.String("backend", "http://127.0.0.1:19134", "NetherNet URL or private RakNet URL (raknet://127.0.0.1:port)")
	adapter := flag.String("adapter", "127.0.0.1:19138", "Loopback Java native-batch adapter")
	local := flag.String("local-ip", "", "Optional local IPv4 filter for ICE candidates (default: automatic interface discovery)")
	keyPath := flag.String("identity", "keys/inspect-identity.pem", "Persistent public listener identity key")
	offline := flag.Bool("offline-backend", false, "Re-sign verified frontend login for a private offline NetherNet backend")
	stock := flag.Bool("stock-geyser-backend", false, "Use stock private NetherNet Geyser with WaterdogPE forwarding")
	detect := flag.Bool("detect", false, "Enable the existing Navro checks and corrections on this independent connection")
	backendSigningKey := flag.String("backend-signing-key", "", "RSA private key for the pinned private NetherNet backend")
	initSigningKey := flag.String("init-backend-signing-key", "", "Generate a backend RSA private key and its .pub.pem public key, then exit")
	flag.Parse()
	if *initSigningKey != "" {
		if err := initBackendSigningKey(*initSigningKey); err != nil {
			slog.Error("backend signing key setup failed", "error", err)
			os.Exit(1)
		}
		slog.Info("backend signing key pair created", "publicKey", backendPublicKeyPath(*initSigningKey))
		return
	}
	var signer *rsa.PrivateKey
	if *backendSigningKey != "" {
		var err error
		signer, err = loadBackendSigningKey(*backendSigningKey)
		if err != nil {
			slog.Error("invalid backend signing key", "error", err)
			os.Exit(1)
		}
	}
	if err := run(*listen, *backend, *adapter, *local, *keyPath, *offline, *detect, *stock, signer); err != nil {
		slog.Error("inspect stopped", "error", err)
		os.Exit(1)
	}
}
func run(listen, backend, adapter, local, keyPath string, offline, detect, stock bool, signer *rsa.PrivateKey) error {
	rakNetAddress, err := rakNetBackendAddress(backend)
	if err != nil {
		return err
	}
	if rakNetAddress != "" && (!detect || offline || stock || signer != nil) {
		return errors.New("RakNet backend requires -detect and uses no NetherNet backend identity options")
	}
	if stock && (!detect || offline) {
		return errors.New("stock Geyser mode requires -detect and is separate from -offline-backend")
	}
	if detect && !offline && !stock && rakNetAddress == "" {
		return errors.New("detection currently requires an explicitly private offline backend")
	}
	if signer != nil && !offline {
		return errors.New("-backend-signing-key requires -offline-backend")
	}
	if offline || stock {
		u, err := url.Parse(backend)
		if err != nil {
			return err
		}
		ip := net.ParseIP(u.Hostname())
		if ip == nil || !ip.IsLoopback() || ((stock || signer != nil) && (u.Scheme != "http" || u.Port() == "" || u.User != nil)) {
			return errors.New("private backend login requires an explicit loopback endpoint")
		}
	}
	host, _, err := net.SplitHostPort(adapter)
	if err != nil {
		return err
	}
	if ip := net.ParseIP(host); ip == nil || !ip.IsLoopback() {
		return fmt.Errorf("batch adapter must be loopback")
	}
	api, err := rtcAPI(local, true)
	if err != nil {
		return err
	}
	backendAPI := api
	if stock || signer != nil {
		backendAPI, err = rtcAPI("127.0.0.1", stock)
		if err != nil {
			return err
		}
	}
	key, saved, err := nethernet.LoadOrGenerateIdentityKey(keyPath)
	if err != nil {
		return err
	}
	if !saved {
		if err = os.MkdirAll(filepath.Dir(keyPath), 0700); err != nil {
			return err
		}
		if err = nethernet.SaveIdentityKey(keyPath, key); err != nil {
			return err
		}
	}
	h, err := endpoint.Serve(listen)
	if err != nil {
		return err
	}
	defer h.Close()
	mode := "INSPECT"
	if detect {
		mode = "DETECT"
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	go mirrorBackendStatus(ctx, h, backend, rakNetAddress)
	l, err := (nethernet.ListenConfig{API: api, DisableTrickleICE: true, AllowAnonymous: false,
		IssueServerIdentity: func(context.Context) (*nethernet.Identity, error) {
			return nethernet.ServerIdentity(key, "")
		},
	}).Listen(h)
	if err != nil {
		return err
	}
	defer l.Close()
	go func() { <-ctx.Done(); l.Close(); h.Close() }()
	slog.Info("NETHERNET_TERMINATING_READY", "listen", listen, "backend", backend, "adapter", adapter, "mode", mode, "raknet", rakNetAddress != "")
	for {
		c, err := l.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		go func(front *nethernet.Conn) {
			if err := session(front, backend, adapter, rakNetAddress, backendAPI, offline, detect, stock, signer); err != nil {
				slog.Info("inspect_session_closed", "error", err)
			}
		}(c.(*nethernet.Conn))
	}
}

type event struct {
	backend bool
	batch   []byte
	err     error
	tick    bool
}

func session(front *nethernet.Conn, backend, adapter, rakNetAddress string, api *webrtc.API, offline, detect, stock bool, signer *rsa.PrivateKey) error {
	defer front.Close()
	if front.PublicKey() == nil {
		return errors.New("missing frontend DTLS identity")
	}
	ipc, err := net.DialTimeout("tcp", adapter, 5*time.Second)
	if err != nil {
		return err
	}
	defer ipc.Close()
	public, err := x509.MarshalPKIXPublicKey(front.PublicKey())
	if err != nil {
		return err
	}
	out := bufio.NewWriter(ipc)
	in := bufio.NewReader(ipc)
	magic := uint32(0x4e564231)
	if rakNetAddress != "" {
		magic = rakNetBridgeMagic
	} else if stock {
		magic = stockBridgeMagic
	} else if offline {
		magic = 0x4e564233
	}
	binary.Write(out, binary.BigEndian, magic)
	binary.Write(out, binary.BigEndian, uint32(len(public)))
	out.Write(public)
	var key *ecdsa.PrivateKey
	if rakNetAddress == "" {
		key, err = ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
		if err != nil {
			return err
		}
	}
	if offline || stock {
		pub, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
		if err != nil {
			return err
		}
		priv, err := x509.MarshalPKCS8PrivateKey(key)
		if err != nil {
			return err
		}
		binary.Write(out, binary.BigEndian, uint32(len(pub)))
		out.Write(pub)
		binary.Write(out, binary.BigEndian, uint32(len(priv)))
		out.Write(priv)
		clear(priv)
		if stock {
			ip := selectedIP(front)
			if ip == nil {
				return errors.New("missing frontend ICE address")
			}
			out.WriteByte(byte(len(ip)))
			out.Write(ip)
		} else if detect {
			out.WriteByte(1)
		} else {
			out.WriteByte(0)
		}
	}
	if err = out.Flush(); err != nil {
		return err
	}
	if stock || rakNetAddress != "" {
		if err := ipc.SetDeadline(time.Now().Add(10 * time.Second)); err != nil {
			return err
		}
		var accepted uint32
		if err := binary.Read(in, binary.BigEndian, &accepted); err != nil {
			return fmt.Errorf("native batch adapter handshake: %w", err)
		}
		if accepted != magic {
			return errors.New("native batch adapter did not confirm detection")
		}
	}
	if rakNetAddress != "" {
		ctx, cancel := context.WithTimeout(front.Context(), 10*time.Second)
		back, err := dialRakNetBackend(ctx, rakNetAddress)
		cancel()
		if err != nil {
			return fmt.Errorf("dial RakNet backend: %w", err)
		}
		defer back.Close()
		return bridgeBatches(front, back, ipc, in, out)
	}

	var identity *nethernet.Identity
	if signer != nil {
		identity, err = signedBackendIdentity(key, signer)
	} else {
		identity, err = nethernet.SelfSignedIdentity(key, "https://authorization.franchise.minecraft-services.net/")
	}
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	signaling := endpoint.NewClient()
	if stock || signer != nil {
		signaling = stockSignaling()
	}
	back, err := (nethernet.Dialer{API: api, Identity: identity, DisableTrickleICE: true}).DialContext(ctx, backend, signaling)
	if err != nil {
		return err
	}
	defer back.Close()
	if stock || signer != nil {
		if !selectedIP(back).IsLoopback() {
			return errors.New("private NetherNet backend media is outside loopback")
		}
	}
	if stock {
		return bridgeBatches(front, back, ipc, in, out)
	}
	events := make(chan event, 8)
	done := make(chan struct{})
	defer close(done)
	reader := func(c *nethernet.Conn, backend bool) {
		for {
			b, err := c.ReadPacket()
			select {
			case events <- event{backend: backend, batch: b, err: err}:
			case <-done:
				return
			}
			if err != nil {
				return
			}
		}
	}
	go reader(front, false)
	go reader(back, true)
	ticker := time.NewTicker(50 * time.Millisecond)
	defer ticker.Stop()
	for {
		var e event
		select {
		case <-front.Context().Done():
			return front.Context().Err()
		case <-back.Context().Done():
			return back.Context().Err()
		case e = <-events:
			if e.err != nil {
				return e.err
			}
		case <-ticker.C:
			if !offline {
				continue
			}
			e.tick = true
		}
		ipc.SetDeadline(time.Now().Add(30 * time.Second))
		direction := byte(0)
		if e.backend {
			direction = 1
		}
		if e.tick {
			direction = 2
		}
		out.WriteByte(direction)
		binary.Write(out, binary.BigEndian, uint32(len(e.batch)))
		out.Write(e.batch)
		if err = out.Flush(); err != nil {
			return err
		}
		var size int32
		if err = binary.Read(in, binary.BigEndian, &size); err != nil {
			return fmt.Errorf("native batch adapter: %w", err)
		}
		if size < -1 || size > 16<<20 {
			return errors.New("invalid adapter result length")
		}
		if size >= 0 {
			b := make([]byte, int(size))
			if _, err = io.ReadFull(in, b); err != nil {
				return err
			}
			dst := back
			if e.backend {
				dst = front
			}
			if _, err = dst.Write(b); err != nil {
				return err
			}
		}
		var count uint32
		if err = binary.Read(in, binary.BigEndian, &count); err != nil {
			return err
		}
		if count > 4096 {
			return errors.New("too many adapter outputs")
		}
		for range count {
			var n uint32
			if err = binary.Read(in, binary.BigEndian, &n); err != nil {
				return err
			}
			if n == 0 || n > 16<<20 {
				return errors.New("invalid adapter output size")
			}
			b := make([]byte, int(n))
			if _, err = io.ReadFull(in, b); err != nil {
				return err
			}
			if _, err = front.Write(b); err != nil {
				return err
			}
		}
		if offline {
			if err = binary.Read(in, binary.BigEndian, &count); err != nil {
				return err
			}
			if count > 4096 {
				return errors.New("too many deferred backend packets")
			}
			for range count {
				var n uint32
				if err = binary.Read(in, binary.BigEndian, &n); err != nil {
					return err
				}
				if n == 0 || n > 16<<20 {
					return errors.New("invalid backend packet size")
				}
				b := make([]byte, int(n))
				if _, err = io.ReadFull(in, b); err != nil {
					return err
				}
				if _, err = back.Write(b); err != nil {
					return err
				}
			}
		}
	}
}
