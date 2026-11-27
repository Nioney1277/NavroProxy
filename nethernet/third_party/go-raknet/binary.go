package raknet

import (
	"bytes"
	"io"
)



type uint24 uint32


func (u *uint24) Inc() (old uint24) {
	ret := *u
	*u += 1
	return ret
}



func readUint24(buf *bytes.Buffer) (uint24, error) {
	b := make([]byte, 3)
	if _, err := buf.Read(b); err != nil {
		return 0, io.ErrUnexpectedEOF
	}
	return loadUint24(b), nil
}


func loadUint24(b []byte) uint24 {
	return uint24(b[0]) | (uint24(b[1]) << 8) | (uint24(b[2]) << 16)
}



func writeUint24(b *bytes.Buffer, v uint24) {
	b.Write([]byte{
		byte(v),
		byte(v >> 8),
		byte(v >> 16),
	})
}

func writeUint16(b *bytes.Buffer, v uint16) {
	b.Write([]byte{
		byte(v >> 8),
		byte(v),
	})
}

func writeUint32(b *bytes.Buffer, v uint32) {
	b.Write([]byte{
		byte(v >> 24),
		byte(v >> 16),
		byte(v >> 8),
		byte(v),
	})
}
