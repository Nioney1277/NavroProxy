//go:build !windows

package raknet

import (
	"errors"
	"syscall"
)




func isTransientUDPReadError(err error) bool {
	if err == nil {
		return false
	}
	var errno syscall.Errno
	if errors.As(err, &errno) {
		switch errno {
		case syscall.ECONNREFUSED, syscall.EHOSTUNREACH, syscall.ENETUNREACH, syscall.ECONNRESET:
			return true
		}
	}
	return false
}
