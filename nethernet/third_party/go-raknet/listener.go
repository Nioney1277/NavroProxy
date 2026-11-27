package raknet

import (
	"errors"
	"fmt"
	"log/slog"
	"maps"
	"math"
	"math/rand/v2"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sandertv/go-raknet/internal"
)


type UpstreamPacketListener interface {
	ListenPacket(network, address string) (net.PacketConn, error)
}


type ListenConfig struct {


	ProtocolVersions []byte



	ErrorLog *slog.Logger


	UpstreamPacketListener UpstreamPacketListener






	DisableCookies bool


	MaxMTU uint16




	BlockDuration time.Duration
}




type Listener struct {
	conf    ListenConfig
	handler *listenerConnectionHandler
	sec     *security

	once   sync.Once
	closed chan struct{}

	conn net.PacketConn


	incoming chan *Conn



	connections sync.Map



	id int64



	pongData atomic.Pointer[[]byte]



	pongDataFunc atomic.Pointer[func(addr net.Addr) []byte]
}


var listenerID = rand.Int64()






func (conf ListenConfig) Listen(address string) (*Listener, error) {
	if conf.ErrorLog == nil {
		conf.ErrorLog = slog.New(internal.DiscardHandler{})
	}
	conf.ErrorLog = conf.ErrorLog.With("src", "listener")

	if conf.BlockDuration == 0 {
		conf.BlockDuration = time.Second * 10
	}
	conf.MaxMTU = clampMTU(conf.MaxMTU, minMTUSize)
	var conn net.PacketConn
	var err error

	if conf.UpstreamPacketListener == nil {
		conn, err = net.ListenPacket("udp", address)
	} else {
		conn, err = conf.UpstreamPacketListener.ListenPacket("udp", address)
	}
	if err != nil {
		return nil, &net.OpError{Op: "listen", Net: "raknet", Source: nil, Addr: nil, Err: err}
	}
	listener := &Listener{
		conf:     conf,
		conn:     conn,
		incoming: make(chan *Conn),
		closed:   make(chan struct{}),
		id:       atomic.AddInt64(&listenerID, 1),
	}
	listener.handler = &listenerConnectionHandler{l: listener, cookieSalt: &atomic.Uint64{}, previousSalt: &atomic.Uint64{}}
	listener.sec = newSecurity(conf, listener.handler)
	listener.pongData.Store(new([]byte))

	go listener.listen()
	go listener.sec.tick(listener.closed)
	return listener, nil
}






func Listen(address string) (*Listener, error) {
	var lc ListenConfig
	return lc.Listen(address)
}





func (listener *Listener) Accept() (net.Conn, error) {
	conn, ok := <-listener.incoming
	if !ok {
		return nil, &net.OpError{Op: "accept", Net: "raknet", Source: nil, Addr: nil, Err: ErrListenerClosed}
	}
	return conn, nil
}



func (listener *Listener) Addr() net.Addr {
	return listener.conn.LocalAddr()
}


func (listener *Listener) Block(addr net.Addr) {
	listener.sec.block(addr)
}


func (listener *Listener) BlockFor(addr net.Addr, duration time.Duration) {
	listener.sec.blockFor(addr, duration)
}



func (listener *Listener) Close() error {
	var err error
	listener.once.Do(func() {
		close(listener.closed)
		err = listener.conn.Close()
	})
	return err
}





func (listener *Listener) PongData(data []byte) {
	if len(data) > math.MaxInt16 {
		panic(fmt.Sprintf("pong data: must be no longer than %v bytes, got %v", math.MaxInt16, len(data)))
	}
	listener.pongData.Store(&data)
}




func (listener *Listener) PongDataFunc(f func(addr net.Addr) []byte) {
	if f == nil {
		listener.pongDataFunc.Store(nil)
	} else {
		listener.pongDataFunc.Store(&f)
	}
}



func (listener *Listener) ID() int64 {
	return listener.id
}


func (listener *Listener) maxMTU() uint16 {
	return clampMTU(listener.conf.MaxMTU, minMTUSize)
}



func (listener *Listener) listen() {


	b := make([]byte, 1500)
	for {
		n, addr, err := listener.conn.ReadFrom(b)
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				close(listener.incoming)
				return
			}
			listener.conf.ErrorLog.Error("read from: "+err.Error(), "raddr", addrToStr(addr))
			continue
		} else if n == 0 || listener.sec.blocked(addr) {
			continue
		}
		if err = listener.handle(b[:n], addr); err != nil && !errors.Is(err, net.ErrClosed) {
			listener.conf.ErrorLog.Error("handle packet: "+err.Error(), "raddr", addrToStr(addr), "block-duration", max(0, listener.conf.BlockDuration))
			listener.sec.block(addr)
		}
	}
}


func addrToStr(addr net.Addr) string {
	if addr != nil {
		return addr.String()
	}
	return "unknown"
}



func (listener *Listener) handle(b []byte, addr net.Addr) error {
	value, found := listener.connections.Load(resolve(addr))
	if !found {
		return listener.handler.handleUnconnected(b, addr)
	}
	conn := value.(*Conn)
	select {
	case <-conn.ctx.Done():

		return nil
	default:
		if err := conn.receive(b); err != nil {
			conn.closeImmediately()
			return err
		}
		return nil
	}
}



type security struct {
	conf ListenConfig
	h    *listenerConnectionHandler

	blockCount atomic.Uint32

	mu     sync.Mutex
	blocks map[[16]byte]time.Time
}


func newSecurity(conf ListenConfig, h *listenerConnectionHandler) *security {
	h.cookieSalt.Store(rand.Uint64())
	h.previousSalt.Store(rand.Uint64())
	return &security{h: h, conf: conf, blocks: make(map[[16]byte]time.Time)}
}




func (s *security) tick(stop <-chan struct{}) {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	i := 0

	for {
		select {
		case <-ticker.C:
			s.gcBlocks()
			if i++; i%2 == 0 {

				s.h.previousSalt.Store(s.h.cookieSalt.Load())
				s.h.cookieSalt.Store(rand.Uint64())
			}
		case <-stop:
			return
		}
	}
}


func (s *security) block(addr net.Addr) {
	s.blockFor(addr, s.conf.BlockDuration)
}


func (s *security) blockFor(addr net.Addr, duration time.Duration) {
	if duration <= 0 {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	ip := [16]byte(addr.(*net.UDPAddr).IP.To16())

	if _, ok := s.blocks[ip]; !ok {
		s.blockCount.Add(1)
	}

	s.blocks[ip] = time.Now().Add(duration)
}



func (s *security) blocked(addr net.Addr) bool {
	if s.blockCount.Load() == 0 {

		return false
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	ip := [16]byte(addr.(*net.UDPAddr).IP.To16())

	expiresAt, blocked := s.blocks[ip]
	if !blocked {
		return false
	}
	if !time.Now().Before(expiresAt) {
		delete(s.blocks, ip)
		s.blockCount.Store(uint32(len(s.blocks)))
		return false
	}

	return true
}




func (s *security) gcBlocks() {
	if s.blockCount.Load() == 0 {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now()
	maps.DeleteFunc(s.blocks, func(ip [16]byte, t time.Time) bool {
		return !now.Before(t)
	})
	s.blockCount.Store(uint32(len(s.blocks)))
}
