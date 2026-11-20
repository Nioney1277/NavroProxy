package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"flag"
	"fmt"
	"net"
	"net/url"
	"os"
	"time"

	"github.com/helix-bedrock/nethernet"
	"github.com/helix-bedrock/nethernet/endpoint"
	"github.com/pion/transport/v5"
	"github.com/pion/transport/v5/stdnet"
	"github.com/pion/webrtc/v4"
)



type probeNet struct {
	*stdnet.Net
	iface *transport.Interface
}

func (n *probeNet) Interfaces() ([]*transport.Interface, error) {
	return []*transport.Interface{n.iface}, nil
}
func (n *probeNet) InterfaceByIndex(index int) (*transport.Interface, error) {
	if index == n.iface.Index {
		return n.iface, nil
	}
	return nil, fmt.Errorf("unknown interface")
}
func (n *probeNet) InterfaceByName(name string) (*transport.Interface, error) {
	if name == n.iface.Name {
		return n.iface, nil
	}
	return nil, fmt.Errorf("unknown interface")
}

func main() {
	address := flag.String("address", "http://127.0.0.1:19134", "NetherNet HTTP endpoint")
	protocol := flag.Int("protocol", 2225, "Bedrock protocol")
	identity := flag.Bool("identity", true, "Include a self-signed probe identity")
	flag.Parse()
	if err := run(*address, *protocol, *identity); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run(address string, protocol int, withIdentity bool) error {
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	parsed, err := url.Parse(address)
	if err != nil {
		return err
	}
	remote, err := net.ResolveUDPAddr("udp4", parsed.Host)
	if err != nil {
		return err
	}
	route, err := net.DialUDP("udp4", nil, remote)
	if err != nil {
		return err
	}
	local := route.LocalAddr().(*net.UDPAddr).IP
	route.Close()
	flags := net.FlagUp | net.FlagRunning
	if local.IsLoopback() {
		flags |= net.FlagLoopback
	}
	iface := transport.NewInterface(net.Interface{Index: 1, Name: "navro-local-probe", MTU: 1500, Flags: flags})
	iface.AddAddress(&net.IPNet{IP: local, Mask: net.CIDRMask(32, 32)})
	var settings webrtc.SettingEngine
	nethernet.ApplySettings(&settings)
	settings.SetIncludeLoopbackCandidate(true)
	settings.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	settings.SetNet(&probeNet{Net: &stdnet.Net{}, iface: iface})
	api := webrtc.NewAPI(webrtc.WithSettingEngine(settings))
	d := nethernet.Dialer{DisableTrickleICE: true, API: api}
	if withIdentity {
		key, err := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
		if err != nil {
			return err
		}
		d.Identity, err = nethernet.SelfSignedIdentity(key, "Navro transport probe")
		if err != nil {
			return err
		}
	}
	c, err := d.DialContext(ctx, address, endpoint.NewClient())
	if err != nil {
		return fmt.Errorf("NetherNet connect: %w", err)
	}
	defer c.Close()
	fmt.Println("NETHERNET_WEBRTC_READY")


	request := []byte{6, 0xc1, 1, 0, 0, 0, 0}
	binary.BigEndian.PutUint32(request[3:], uint32(protocol))
	if _, err = c.Write(request); err != nil {
		return err
	}
	result := make(chan error, 1)
	go func() {
		b, err := c.ReadPacket()
		if err != nil {
			result <- err
			return
		}
		fmt.Println("BEDROCK_BATCH", hex.EncodeToString(b))
		if len(b) < 3 {
			result <- fmt.Errorf("unexpected Bedrock batch")
			return
		}
		_, size := binary.Uvarint(b)
		if size <= 0 {
			result <- fmt.Errorf("bad frame length")
			return
		}
		id, _ := binary.Uvarint(b[size:])
		if id&0x3ff != 143 {
			result <- fmt.Errorf("unexpected packet id %d", id&0x3ff)
			return
		}
		fmt.Println("RESULT NetworkSettingsPacket via independent NetherNet")
		result <- nil
	}()
	select {
	case err := <-result:
		return err
	case <-ctx.Done():
		return ctx.Err()
	}
}
