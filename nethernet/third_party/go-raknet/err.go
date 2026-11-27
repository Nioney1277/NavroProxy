package raknet

import (
	"errors"
	"net"
)

var (


	ErrBufferTooSmall = errors.New("a message sent was larger than the buffer used to receive the message into")


	ErrListenerClosed = errors.New("use of closed listener")


	ErrNotSupported = errors.New("feature not supported")
)



func (conn *Conn) error(err error, op string) error {
	if err == nil {
		return nil
	}
	return &net.OpError{
		Op:     op,
		Net:    "raknet",
		Source: conn.LocalAddr(),
		Addr:   conn.raddr,
		Err:    err,
	}
}
