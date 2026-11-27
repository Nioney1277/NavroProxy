package internal

import (
	"net"
)




func ConnToPacketConn(conn net.Conn) net.PacketConn {
	return &wrappedConn{conn}
}



type wrappedConn struct {
	net.Conn
}



func (conn *wrappedConn) WriteTo(b []byte, _ net.Addr) (n int, err error) {
	return conn.Conn.Write(b)
}

func (conn *wrappedConn) ReadFrom([]byte) (int, net.Addr, error) { panic("unused") }
