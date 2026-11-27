//go:build windows

package raknet

import (
	"errors"
	"syscall"
)


const (
	wsaeConnRefused = syscall.Errno(10061)
	wsaeHostUnreach = syscall.Errno(10065)
	wsaeNetUnreach  = syscall.Errno(10051)
	wsaeConnReset   = syscall.Errno(10054)
	wsaeConnAborted = syscall.Errno(10053)
)




func isTransientUDPReadError(err error) bool {
	if err == nil {
		return false
	}
	var errno syscall.Errno
	if errors.As(err, &errno) {
		switch errno {
		case wsaeConnRefused, wsaeHostUnreach, wsaeNetUnreach, wsaeConnReset, wsaeConnAborted:
			return true
		}
	}
	return false
}
