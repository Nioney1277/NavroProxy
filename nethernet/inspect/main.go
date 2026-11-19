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
	"github.com/pion/transport/v5"
	"github.com/pion/transport/v5/stdnet"
	"github.com/pion/webrtc/v4"
)

type localNet struct {
	*stdnet.Net
	interfaces []*transport.Interface
}

func (n *localNet) Interfaces() ([]*transport.Interface, error) { return n.interfaces, nil }
func (n *localNet) InterfaceByIndex(index int) (*transport.Interface, error) {
	for _, i := range n.interfaces {
		if i.Index == index {
			return i, nil
		}
	}
	return nil, fmt.Errorf("unknown interface")
}
func (n *localNet) InterfaceByName(name string) (*transport.Interface, error) {
	for _, i := range n.interfaces {
		if i.Name == name {
			return i, nil
		}
	}
	return nil, fmt.Errorf("unknown interface")
}
func rtcAPI(local string) (*webrtc.API, error) {
	ip := net.ParseIP(local)
	if ip == nil || ip.To4() == nil {
		return nil, fmt.Errorf("explicit local IPv4 required")
	}
	n := &localNet{Net: &stdnet.Net{}}
	for _, address := range []net.IP{net.IPv4(127, 0, 0, 1), ip} {
		if len(n.interfaces) > 0 && address.Equal(net.IPv4(127, 0, 0, 1)) {
			continue
		}
		flags := net.FlagUp | net.FlagRunning
		if address.IsLoopback() {
			flags |= net.FlagLoopback
		}
		i := transport.NewInterface(net.Interface{Index: len(n.interfaces) + 1, Name: fmt.Sprintf("navro%d", len(n.interfaces)), MTU: 1500, Flags: flags})
		i.AddAddress(&net.IPNet{IP: address, Mask: net.CIDRMask(32, 32)})
		n.interfaces = append(n.interfaces, i)
	}
	var s webrtc.SettingEngine
	nethernet.ApplySettings(&s)
	s.SetIncludeLoopbackCandidate(true)
	s.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	s.SetNet(n)
	if err := s.SetEphemeralUDPPortRange(19210, 19273); err != nil {
		return nil, err
	}
	return webrtc.NewAPI(webrtc.WithSettingEngine(s)), nil
}

func main() {
	listen := flag.String("listen", "0.0.0.0:19137", "Independent inspection signaling listener")
	backend := flag.String("backend", "http://127.0.0.1:19134", "NetherNet backend")
	adapter := flag.String("adapter", "127.0.0.1:19138", "Loopback Java native-batch adapter")
	local := flag.String("local-ip", "", "Local LAN IPv4 for Android ICE enumeration")
	keyPath := flag.String("identity", "keys/inspect-identity.pem", "Persistent public listener identity key")
	offline := flag.Bool("offline-backend", false, "Re-sign verified frontend login for a private offline NetherNet backend")
	detect := flag.Bool("detect", false, "Enable the existing Navro checks and corrections on this independent connection")
	flag.Parse()
	if err := run(*listen, *backend, *adapter, *local, *keyPath, *offline, *detect); err != nil {
		slog.Error("inspect stopped", "error", err)
		os.Exit(1)
	}
}
func run(listen, backend, adapter, local, keyPath string, offline, detect bool) error {
	if detect && !offline {
		return errors.New("detection currently requires an explicitly private offline backend")
	}
	if offline {
		u, err := url.Parse(backend)
		if err != nil {
			return err
		}
		ip := net.ParseIP(u.Hostname())
		if ip == nil || !ip.IsLoopback() {
			return errors.New("offline backend login is restricted to an explicit loopback endpoint")
		}
	}
	host, _, err := net.SplitHostPort(adapter)
	if err != nil {
		return err
	}
	if ip := net.ParseIP(host); ip == nil || !ip.IsLoopback() {
		return fmt.Errorf("batch adapter must be loopback")
	}
	api, err := rtcAPI(local)
	if err != nil {
		return err
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
	h.Status(endpoint.Status{ServerName: "Navro NetherNet " + mode, Protocol: 2225, Version: "1.26.63.1", MaxPlayerCount: 10, OnlineAuth: true})
	l, err := (nethernet.ListenConfig{API: api, DisableTrickleICE: true, AllowAnonymous: false,
		IssueServerIdentity: func(context.Context) (*nethernet.Identity, error) {
			return nethernet.ServerIdentity(key, "Navro NetherNet "+mode)
		},
	}).Listen(h)
	if err != nil {
		return err
	}
	defer l.Close()
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	go func() { <-ctx.Done(); l.Close(); h.Close() }()
	slog.Info("NETHERNET_TERMINATING_READY", "listen", listen, "backend", backend, "adapter", adapter, "mode", mode, "raknet", false)
	for {
		c, err := l.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		go func(front *nethernet.Conn) {
			if err := session(front, backend, adapter, api, offline, detect); err != nil {
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

func session(front *nethernet.Conn, backend, adapter string, api *webrtc.API, offline, detect bool) error {
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
	key, err := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	if err != nil {
		return err
	}
	out := bufio.NewWriter(ipc)
	in := bufio.NewReader(ipc)
	magic := uint32(0x4e564231)
	if offline {
		magic = 0x4e564233
	}
	binary.Write(out, binary.BigEndian, magic)
	binary.Write(out, binary.BigEndian, uint32(len(public)))
	out.Write(public)
	if offline {
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
		if detect {
			out.WriteByte(1)
		} else {
			out.WriteByte(0)
		}
	}
	if err = out.Flush(); err != nil {
		return err
	}
	identity, err := nethernet.SelfSignedIdentity(key, "https://authorization.franchise.minecraft-services.net/")
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	back, err := (nethernet.Dialer{API: api, Identity: identity, DisableTrickleICE: true}).DialContext(ctx, backend, endpoint.NewClient())
	if err != nil {
		return err
	}
	defer back.Close()
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
