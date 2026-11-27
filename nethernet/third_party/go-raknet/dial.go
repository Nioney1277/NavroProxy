package raknet

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math/rand/v2"
	"net"
	"sync/atomic"
	"time"

	"github.com/sandertv/go-raknet/internal"

	"github.com/sandertv/go-raknet/internal/message"
)


type UpstreamDialer interface {
	DialContext(ctx context.Context, network, address string) (net.Conn, error)
}







func Ping(address string) (response []byte, err error) {
	var d Dialer
	return d.Ping(address)
}







func PingTimeout(address string, timeout time.Duration) ([]byte, error) {
	var d Dialer
	return d.PingTimeout(address, timeout)
}








func PingContext(ctx context.Context, address string) (response []byte, err error) {
	var d Dialer
	return d.PingContext(ctx, address)
}







func Dial(address string) (*Conn, error) {
	var d Dialer
	return d.Dial(address)
}






func DialTimeout(address string, timeout time.Duration) (*Conn, error) {
	var d Dialer
	return d.DialTimeout(address, timeout)
}







func DialContext(ctx context.Context, address string) (*Conn, error) {
	var d Dialer
	return d.DialContext(ctx, address)
}



type Dialer struct {

	ProtocolVersion byte



	ErrorLog *slog.Logger



	UpstreamDialer UpstreamDialer







	MaxTransientErrors int










	MaxMTU uint16
}







func (dialer Dialer) Ping(address string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second*5)
	defer cancel()
	return dialer.PingContext(ctx, address)
}







func (dialer Dialer) PingTimeout(address string, timeout time.Duration) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	return dialer.PingContext(ctx, address)
}








func (dialer Dialer) PingContext(ctx context.Context, address string) (response []byte, err error) {
	conn, err := dialer.dial(ctx, address)
	if err != nil {
		return nil, dialer.error("ping", err)
	}
	defer conn.Close()

	data, _ := (&message.UnconnectedPing{PingTime: timestamp(), ClientGUID: atomic.AddInt64(&dialerID, 1)}).MarshalBinary()
	if _, err := conn.Write(data); err != nil {
		return nil, dialer.error("ping", err)
	}

	data = make([]byte, maxMTUSize)
	n, err := conn.Read(data)
	if err != nil {
		return nil, dialer.error("ping", err)
	}
	if n == 0 || data[0] != message.IDUnconnectedPong {
		return nil, dialer.error("ping", fmt.Errorf("non-pong packet found (id = %v)", data[0]))
	}
	pong := &message.UnconnectedPong{}
	if err := pong.UnmarshalBinary(data[1:n]); err != nil {
		return nil, dialer.error("ping", fmt.Errorf("read unconnected pong: %w", err))
	}
	return pong.Data, nil
}


func (dialer Dialer) error(op string, err error) *net.OpError {
	return &net.OpError{Op: op, Net: "raknet", Source: nil, Addr: nil, Err: err}
}



func (dialer Dialer) dial(ctx context.Context, address string) (net.Conn, error) {
	dial := (&net.Dialer{}).DialContext
	if dialer.UpstreamDialer != nil {
		dial = dialer.UpstreamDialer.DialContext
	}
	conn, err := dial(ctx, "udp", address)
	if err != nil {
		return nil, err
	}
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	return conn, nil
}



var dialerID = -rand.Int64()






func (dialer Dialer) Dial(address string) (*Conn, error) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second*10)
	defer cancel()
	return dialer.DialContext(ctx, address)
}






func (dialer Dialer) DialTimeout(address string, timeout time.Duration) (*Conn, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	return dialer.DialContext(ctx, address)
}







func (dialer Dialer) DialContext(ctx context.Context, address string) (*Conn, error) {
	selectedProtocol := dialer.ProtocolVersion
	if selectedProtocol == 0 {
		selectedProtocol = protocolVersion
	}
	if selectedProtocol < 9 || selectedProtocol > 11 {
		return nil, fmt.Errorf("unsupported RakNet protocol %v", selectedProtocol)
	}
	if dialer.ErrorLog == nil {
		dialer.ErrorLog = slog.New(internal.DiscardHandler{})
	}
	if dialer.MaxTransientErrors == 0 {
		dialer.MaxTransientErrors = 10
	}

	conn, err := dialer.dial(ctx, address)
	if err != nil {
		return nil, dialer.error("dial", err)
	}
	dialer.ErrorLog = dialer.ErrorLog.With("src", "dialer", "raddr", conn.RemoteAddr().String())

	cs := &connState{
		protocol:           selectedProtocol,
		conn:               conn,
		raddr:              conn.RemoteAddr(),
		id:                 atomic.AddInt64(&dialerID, 1),
		ticker:             time.NewTicker(time.Second / 2),
		maxTransientErrors: dialer.MaxTransientErrors,
		maxMTU:             dialer.MaxMTU,
	}
	defer cs.ticker.Stop()
	if err = cs.discoverMTU(ctx); err != nil {
		return nil, dialer.error("dial", fmt.Errorf("discover mtu: %w", err))
	} else if err = cs.openConnection(ctx); err != nil {
		return nil, dialer.error("dial", fmt.Errorf("open connection: %w", err))
	}
	return dialer.connect(ctx, cs)
}



func (dialer Dialer) connect(ctx context.Context, state *connState) (*Conn, error) {
	conn := newConn(internal.ConnToPacketConn(state.conn), state.raddr, state.mtu, dialerConnectionHandler{l: dialer.ErrorLog})
	if err := conn.send((&message.ConnectionRequest{ClientGUID: state.id, RequestTime: timestamp()})); err != nil {
		return nil, dialer.error("dial", fmt.Errorf("send connection request: %w", err))
	}

	go dialer.clientListen(conn, state.conn)

	select {
	case <-conn.connected:

		_ = conn.conn.SetDeadline(time.Time{})
		return conn, nil
	case <-ctx.Done():
		_ = conn.Close()
		return nil, dialer.error("dial", ctx.Err())
	}
}



func (dialer Dialer) clientListen(rakConn *Conn, conn net.Conn) {


	b := make([]byte, maxMTUSize)
	for {
		n, err := conn.Read(b)
		if err == nil && n != 0 {
			err = rakConn.receive(b[:n])
		}
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}


			dialer.ErrorLog.Error("handle packet: " + err.Error())
		}
	}
}



type connState struct {
	protocol byte
	conn     net.Conn
	raddr    net.Addr
	id       int64



	mtu uint16



	maxMTU uint16

	serverSecurity bool
	cookie         uint32

	ticker *time.Ticker

	transientErrorCount int
	maxTransientErrors  int
}

const minSupportedMTU = 576


var mtuSizes = []uint16{maxMTUSize, 1200, minSupportedMTU}





func mtuSizesFor(maxMTU uint16) []uint16 {
	maxMTU = clampMTU(maxMTU, minSupportedMTU)
	if maxMTU == maxMTUSize {
		return mtuSizes
	}
	out := []uint16{maxMTU}
	for _, s := range mtuSizes {
		if s < maxMTU {
			out = append(out, s)
		}
	}
	return out
}




func (state *connState) discoverMTU(ctx context.Context) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	go state.request1(ctx, mtuSizesFor(state.maxMTU))

	b := make([]byte, maxMTUSize)
	for {


		n, err := state.conn.Read(b)
		if err != nil {
			if isTransientUDPReadError(err) && (state.maxTransientErrors == -1 || state.transientErrorCount < state.maxTransientErrors) {
				state.transientErrorCount++
				continue
			}
			state.close()
			return err
		}
		if n == 0 {
			continue
		}
		switch b[0] {
		case message.IDOpenConnectionReply1:
			response := &message.OpenConnectionReply1{}
			if err := response.UnmarshalBinary(b[1:n]); err != nil {
				return fmt.Errorf("read open connection reply 1: %w", err)
			}
			state.serverSecurity, state.cookie = response.ServerHasSecurity, response.Cookie
			if response.ServerGUID == 0 || response.MTU < 400 || response.MTU > 1500 {




				state.openConnectionRequest2(response.MTU)
				continue
			}
			state.mtu = response.MTU
			return nil
		case message.IDIncompatibleProtocolVersion:
			response := &message.IncompatibleProtocolVersion{}
			if err := response.UnmarshalBinary(b[1:n]); err != nil {
				return fmt.Errorf("read incompatible protocol version: %w", err)
			}
			return fmt.Errorf("mismatched protocol: client protocol = %v, server protocol = %v", protocolVersion, response.ServerProtocol)
		}
	}
}



func (state *connState) request1(ctx context.Context, sizes []uint16) {
	state.ticker.Reset(time.Second / 2)
	for _, size := range sizes {
		for range 4 {
			state.openConnectionRequest1(size)
			select {
			case <-state.ticker.C:
				continue
			case <-ctx.Done():
				return
			}
		}
	}
}



func (state *connState) openConnection(ctx context.Context) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	go state.request2(ctx, state.mtu)

	b := make([]byte, maxMTUSize)
	for {


		n, err := state.conn.Read(b)
		if err != nil {
			if isTransientUDPReadError(err) && (state.maxTransientErrors == -1 || state.transientErrorCount < state.maxTransientErrors) {
				state.transientErrorCount++
				continue
			}
			state.close()
			return err
		}
		if n == 0 {
			continue
		}
		if b[0] != message.IDOpenConnectionReply2 {
			continue
		}
		pk := &message.OpenConnectionReply2{}
		if err = pk.UnmarshalBinary(b[1:n]); err != nil {
			return fmt.Errorf("read open connection reply 2: %w", err)
		}
		state.mtu = pk.MTU
		return nil
	}
}


func (state *connState) request2(ctx context.Context, mtu uint16) {
	state.ticker.Reset(time.Second / 2)
	for {
		state.openConnectionRequest2(mtu)
		select {
		case <-state.ticker.C:
			continue
		case <-ctx.Done():
			return
		}
	}
}



func (state *connState) openConnectionRequest1(mtu uint16) {
	data, _ := (&message.OpenConnectionRequest1{ClientProtocol: state.protocol, MTU: mtu}).MarshalBinary()
	_, _ = state.conn.Write(data)
}



func (state *connState) openConnectionRequest2(mtu uint16) {
	data, _ := (&message.OpenConnectionRequest2{
		ServerAddress:     resolve(state.raddr),
		MTU:               mtu,
		ClientGUID:        state.id,
		ServerHasSecurity: state.serverSecurity,
		Cookie:            state.cookie,
	}).MarshalBinary()
	_, _ = state.conn.Write(data)
}


func (state *connState) close() {
	_ = state.conn.Close()
}
