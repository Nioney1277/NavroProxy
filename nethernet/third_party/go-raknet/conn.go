package raknet

import (
	"bytes"
	"context"
	"encoding"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"slices"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sandertv/go-raknet/internal"
	"github.com/sandertv/go-raknet/internal/message"
)

const (


	protocolVersion byte = 11

	minMTUSize    = 400
	maxMTUSize    = 1492
	maxWindowSize = 2048
)





type Conn struct {


	rtt atomic.Int64



	systemStart time.Time

	closing atomic.Int64

	ctx        context.Context
	cancelFunc context.CancelFunc

	conn    net.PacketConn
	raddr   net.Addr
	handler connectionHandler

	once      sync.Once
	connected chan struct{}

	mu  sync.Mutex
	buf *bytes.Buffer

	ackBuf, nackBuf *bytes.Buffer

	pk *packet

	seq, orderIndex, messageIndex uint24
	splitID                       uint32




	mtu uint16




	splits map[uint16][][]byte




	win *datagramWindow

	ackMu sync.Mutex



	ackSlice []uint24



	packetQueue *packetQueue


	packets *internal.ElasticChan[[]byte]



	retransmission *resendMap

	lastActivity atomic.Pointer[time.Time]
}



func newConn(conn net.PacketConn, raddr net.Addr, mtu uint16, h connectionHandler) *Conn {
	mtu = clampMTU(mtu, minMTUSize)
	c := &Conn{
		raddr:          raddr,
		conn:           conn,
		mtu:            mtu,
		handler:        h,
		pk:             new(packet),
		connected:      make(chan struct{}),
		packets:        internal.Chan[[]byte](4, 4096),
		splits:         make(map[uint16][][]byte),
		win:            newDatagramWindow(),
		packetQueue:    newPacketQueue(),
		retransmission: newRecoveryQueue(),
		buf:            bytes.NewBuffer(make([]byte, 0, mtu-28)),
		ackBuf:         bytes.NewBuffer(make([]byte, 0, 128)),
		nackBuf:        bytes.NewBuffer(make([]byte, 0, 64)),
	}
	c.ctx, c.cancelFunc = context.WithCancel(context.Background())
	t := time.Now()
	c.lastActivity.Store(&t)
	go c.startTicking()
	return c
}


func clampMTU(mtu, minMTU uint16) uint16 {
	if mtu == 0 || mtu > maxMTUSize {
		return maxMTUSize
	}
	return max(mtu, minMTU)
}



func (conn *Conn) effectiveMTU() uint16 {
	return conn.mtu - 28
}




func (conn *Conn) startTicking() {
	var (
		interval = time.Second / 10
		ticker   = time.NewTicker(interval)
		i        int64
		acksLeft int
	)
	defer ticker.Stop()
	for {
		select {
		case t := <-ticker.C:
			i++
			conn.flushACKs()
			if i%3 == 0 {
				conn.checkResend(t)
			}
			if unix := conn.closing.Load(); unix != 0 {
				before := acksLeft
				conn.mu.Lock()
				acksLeft = len(conn.retransmission.unacknowledged)
				conn.mu.Unlock()

				if before != 0 && acksLeft == 0 {
					conn.closeImmediately()
				}
				since := t.Sub(time.Unix(unix, 0))
				if (acksLeft == 0 && since > time.Second) || since > time.Second*5 {
					conn.closeImmediately()
				}
				continue
			}
			if i%5 == 0 {

				_ = conn.send(&message.ConnectedPing{PingTime: timestamp()})

				conn.mu.Lock()
				if t.Sub(*conn.lastActivity.Load()) > time.Second*5+conn.retransmission.rtt(t)*2 {

					_ = conn.Close()
				}
				conn.mu.Unlock()
			}
		case <-conn.ctx.Done():
			return
		}
	}
}


func (conn *Conn) flushACKs() {
	conn.ackMu.Lock()
	defer conn.ackMu.Unlock()

	if len(conn.ackSlice) > 0 {


		if err := conn.sendACK(conn.ackSlice...); err != nil {
			return
		}
		conn.ackSlice = conn.ackSlice[:0]
	}
}




func (conn *Conn) checkResend(now time.Time) {
	conn.mu.Lock()
	defer conn.mu.Unlock()

	var (
		resend []uint24
		rtt    = conn.retransmission.rtt(now)
		delay  = rtt + rtt/2
	)
	conn.rtt.Store(int64(rtt))

	for seq, t := range conn.retransmission.unacknowledged {


		if now.Sub(t.timestamp) > delay {
			resend = append(resend, seq)
		}
	}
	_ = conn.resend(resend)
}





func (conn *Conn) Write(b []byte) (n int, err error) {
	return conn.writeWithReliability(b, reliabilityReliableOrdered)
}







func (conn *Conn) writeWithReliability(b []byte, rel reliability) (n int, err error) {
	select {
	case <-conn.ctx.Done():
		return 0, conn.error(net.ErrClosed, "write")
	default:
		conn.mu.Lock()
		defer conn.mu.Unlock()
		n, err = conn.write(b, rel)
		return n, conn.error(err, "write")
	}
}






func (conn *Conn) write(b []byte, rel reliability) (n int, err error) {
	fragments := split(b, conn.effectiveMTU())
	var orderIndex uint24
	if rel.sequencedOrOrdered() {
		orderIndex = conn.orderIndex.Inc()
	}

	splitID := uint16(conn.splitID)
	if len(fragments) > 1 {
		conn.splitID++
	}
	for splitIndex, content := range fragments {
		pk := packetPool.Get().(*packet)
		if cap(pk.content) < len(content) {
			pk.content = make([]byte, len(content))
		}



		pk.content = pk.content[:len(content)]
		copy(pk.content, content)

		pk.orderIndex = orderIndex
		pk.reliability = rel
		if rel.reliable() {
			pk.messageIndex = conn.messageIndex.Inc()
		}
		if pk.split = len(fragments) > 1; pk.split {


			pk.splitCount = uint32(len(fragments))
			pk.splitIndex = uint32(splitIndex)
			pk.splitID = splitID
		}
		if err = conn.sendDatagram(pk); err != nil {
			return 0, err
		}
		n += len(content)
	}
	return n, nil
}





func (conn *Conn) Read(b []byte) (n int, err error) {
	pk, ok := conn.packets.Recv(conn.ctx)
	if !ok {
		return 0, conn.error(net.ErrClosed, "read")
	} else if len(b) < len(pk) {
		return 0, conn.error(ErrBufferTooSmall, "read")
	}
	return copy(b, pk), err
}




func (conn *Conn) ReadPacket() (b []byte, err error) {
	pk, ok := conn.packets.Recv(conn.ctx)
	if !ok {
		return nil, conn.error(net.ErrClosed, "read")
	}
	return pk, err
}




func (conn *Conn) Close() error {
	conn.closing.CompareAndSwap(0, time.Now().Unix())
	return nil
}




func (conn *Conn) Context() context.Context {
	return conn.ctx
}



func (conn *Conn) closeImmediately() {
	conn.once.Do(func() {
		_, _ = conn.Write([]byte{message.IDDisconnectNotification})
		conn.handler.close(conn)
		conn.cancelFunc()

		conn.mu.Lock()
		defer conn.mu.Unlock()

		for _, record := range conn.retransmission.unacknowledged {
			record.pk.content = record.pk.content[:0]
			packetPool.Put(record.pk)
		}
		clear(conn.retransmission.unacknowledged)
	})
}



func (conn *Conn) RemoteAddr() net.Addr {
	return conn.raddr
}



func (conn *Conn) LocalAddr() net.Addr {
	return conn.conn.LocalAddr()
}


func (conn *Conn) SetReadDeadline(time.Time) error { return ErrNotSupported }


func (conn *Conn) SetWriteDeadline(time.Time) error { return ErrNotSupported }


func (conn *Conn) SetDeadline(time.Time) error { return ErrNotSupported }




func (conn *Conn) Latency() time.Duration {
	return time.Duration(conn.rtt.Load() / 2)
}


func (conn *Conn) send(pk encoding.BinaryMarshaler) error {
	b, _ := pk.MarshalBinary()
	_, err := conn.Write(b)
	return err
}



func (conn *Conn) sendUnreliable(pk encoding.BinaryMarshaler) error {
	b, _ := pk.MarshalBinary()
	_, err := conn.writeWithReliability(b, reliabilityUnreliable)
	return err
}


var packetPool = sync.Pool{New: func() any { return &packet{reliability: reliabilityReliableOrdered} }}



func (conn *Conn) receive(b []byte) error {
	t := time.Now()
	conn.lastActivity.Store(&t)

	switch {
	case b[0]&bitFlagACK != 0:
		return conn.handleACK(b[1:])
	case b[0]&bitFlagNACK != 0:
		return conn.handleNACK(b[1:])
	case b[0]&bitFlagDatagram != 0:
		return conn.receiveDatagram(b[1:])
	}
	return nil
}




func (conn *Conn) receiveDatagram(b []byte) error {
	if len(b) < 3 {
		return fmt.Errorf("read datagram: %w", io.ErrUnexpectedEOF)
	}
	seq := loadUint24(b)
	if !conn.win.add(seq) {




		return nil
	}
	conn.ackMu.Lock()


	conn.ackSlice = append(conn.ackSlice, seq)
	conn.ackMu.Unlock()

	if conn.win.shift() == 0 {


		rtt := time.Duration(conn.rtt.Load())
		if missing := conn.win.missing(rtt + rtt/2); len(missing) > 0 {
			if err := conn.sendNACK(missing); err != nil {
				return fmt.Errorf("receive datagram: send NACK: %w", err)
			}
		}
	}
	if conn.win.size() > maxWindowSize && conn.handler.limitsEnabled() {
		return fmt.Errorf("receive datagram: queue window size is too big (%v-%v)", conn.win.lowest, conn.win.highest)
	}
	return conn.handleDatagram(b[3:])
}


func (conn *Conn) handleDatagram(b []byte) error {
	for len(b) > 0 {
		n, err := conn.pk.read(b)
		if err != nil {
			return fmt.Errorf("handle datagram: read packet: %w", err)
		}
		b = b[n:]

		handle := conn.receivePacket
		if conn.pk.split {
			handle = conn.receiveSplitPacket
		}
		if err := handle(conn.pk); err != nil {
			return fmt.Errorf("handle datagram: receive packet: %w", err)
		}
	}
	return nil
}




func (conn *Conn) receivePacket(packet *packet) error {
	if packet.reliability != reliabilityReliableOrdered {

		return conn.handlePacket(packet.content)
	}
	if !conn.packetQueue.put(packet.orderIndex, packet.content) {

		return nil
	}
	if conn.packetQueue.WindowSize() > maxWindowSize && conn.handler.limitsEnabled() {
		return fmt.Errorf("packet queue window size is too big (%v-%v)", conn.packetQueue.lowest, conn.packetQueue.highest)
	}
	for _, content := range conn.packetQueue.fetch() {
		if err := conn.handlePacket(content); err != nil {
			return err
		}
	}
	return nil
}

var errZeroPacket = errors.New("handle packet: zero packet length")




func (conn *Conn) handlePacket(b []byte) error {
	if len(b) == 0 {
		return errZeroPacket
	}
	if conn.closing.Load() != 0 {

		return nil
	}
	handled, err := conn.handler.handle(conn, b)
	if err != nil {
		return fmt.Errorf("handle packet: %w", err)
	}
	if !handled {
		conn.packets.Send(b)
	}
	return nil
}

func resolve(addr net.Addr) netip.AddrPort {
	if udpAddr, ok := addr.(*net.UDPAddr); ok {
		uaddr := *udpAddr
		ip, _ := netip.AddrFromSlice(uaddr.IP)
		if ip.Is4In6() {
			ip = ip.Unmap()
		}
		return netip.AddrPortFrom(ip, uint16(uaddr.Port))
	}
	return netip.AddrPort{}
}




func (conn *Conn) receiveSplitPacket(p *packet) error {
	const maxSplitCount = 512
	const maxConcurrentSplits = 16

	if p.splitCount > maxSplitCount && conn.handler.limitsEnabled() {
		return fmt.Errorf("split packet: split count %v exceeds the maximum %v", p.splitCount, maxSplitCount)
	}
	if len(conn.splits) > maxConcurrentSplits && conn.handler.limitsEnabled() {
		return fmt.Errorf("split packet: maximum concurrent splits %v reached", maxConcurrentSplits)
	}
	m, ok := conn.splits[p.splitID]
	if !ok {
		m = make([][]byte, p.splitCount)
		conn.splits[p.splitID] = m
	}
	if p.splitIndex > uint32(len(m)-1) {


		return fmt.Errorf("split packet: split index %v is out of range (0 - %v)", p.splitIndex, len(m)-1)
	}
	m[p.splitIndex] = p.content

	if slices.ContainsFunc(m, func(i []byte) bool { return len(i) == 0 }) {


		return nil
	}
	p.content = slices.Concat(m...)

	delete(conn.splits, p.splitID)
	return conn.receivePacket(p)
}



func (conn *Conn) sendACK(packets ...uint24) error {
	defer conn.ackBuf.Reset()
	return conn.sendAcknowledgement(packets, bitFlagACK, conn.ackBuf)
}



func (conn *Conn) sendNACK(packets []uint24) error {
	defer conn.nackBuf.Reset()
	return conn.sendAcknowledgement(packets, bitFlagNACK, conn.nackBuf)
}




func (conn *Conn) sendAcknowledgement(packets []uint24, bitflag byte, buf *bytes.Buffer) error {
	ack := &acknowledgement{packets: packets}

	for len(ack.packets) != 0 {
		buf.WriteByte(bitflag | bitFlagDatagram)
		n := ack.write(buf, conn.effectiveMTU())


		ack.packets = ack.packets[n:]
		if err := conn.writeTo(buf.Bytes(), conn.raddr); err != nil {
			return fmt.Errorf("send acknowlegement: %w", err)
		}
		buf.Reset()
	}
	return nil
}




func (conn *Conn) handleACK(b []byte) error {
	conn.mu.Lock()
	defer conn.mu.Unlock()

	ack := &acknowledgement{}
	if err := ack.read(b); err != nil {
		return fmt.Errorf("read ACK: %w", err)
	}
	for _, sequenceNumber := range ack.packets {

		if p, ok := conn.retransmission.acknowledge(sequenceNumber); ok {


			p.content = p.content[:0]
			packetPool.Put(p)
		}
	}
	return nil
}



func (conn *Conn) handleNACK(b []byte) error {
	conn.mu.Lock()
	defer conn.mu.Unlock()

	nack := &acknowledgement{}
	if err := nack.read(b); err != nil {
		return fmt.Errorf("read NACK: %w", err)
	}
	return conn.resend(nack.packets)
}



func (conn *Conn) resend(sequenceNumbers []uint24) (err error) {
	for _, sequenceNumber := range sequenceNumbers {
		pk, ok := conn.retransmission.retransmit(sequenceNumber)
		if !ok {
			continue
		}
		if err = conn.sendDatagram(pk); err != nil {
			return err
		}
	}
	return nil
}



func (conn *Conn) sendDatagram(pk *packet) error {
	conn.buf.WriteByte(bitFlagDatagram | bitFlagNeedsBAndAS)
	seq := conn.seq.Inc()
	writeUint24(conn.buf, seq)
	pk.write(conn.buf)
	defer conn.buf.Reset()

	if pk.reliability.reliable() {


		conn.retransmission.add(seq, pk)
	}

	if err := conn.writeTo(conn.buf.Bytes(), conn.raddr); err != nil {
		return fmt.Errorf("send datagram: %w", err)
	}
	return nil
}





func (conn *Conn) writeTo(p []byte, raddr net.Addr) error {
	if _, err := conn.conn.WriteTo(p, raddr); errors.Is(err, net.ErrClosed) {
		return fmt.Errorf("write to: %w", err)
	} else if err != nil {
		conn.handler.log().Error("write to: "+err.Error(), "raddr", raddr.String())
	}
	return nil
}


func (conn *Conn) SystemUptime() time.Duration {
	return time.Since(conn.systemStart)
}


var startTime = time.Now()


func timestamp() int64 {
	return time.Since(startTime).Milliseconds()
}
