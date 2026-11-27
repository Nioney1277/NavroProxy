package raknet

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"slices"
)

const (


	packetRange = iota

	packetSingle
)

var errMaxAcknowledgement = errors.New("maximum amount of packets in acknowledgement exceeded")



type acknowledgement struct {
	packets []uint24
}



func (ack *acknowledgement) write(buf *bytes.Buffer, mtu uint16) int {
	lenOffset := buf.Len()
	writeUint16(buf, 0)

	packets := ack.packets
	if len(packets) == 0 {
		return 0
	}

	var firstPacketInRange, lastPacketInRange uint24
	var records uint16
	n := 0


	slices.Sort(packets)

	for index, pk := range packets {
		if buf.Len() >= int(mtu-7) {


			break
		}
		n++
		if index == 0 {

			firstPacketInRange, lastPacketInRange = pk, pk
			continue
		}
		if pk == lastPacketInRange+1 {



			lastPacketInRange = pk
			continue
		}
		ack.writeRecord(buf, firstPacketInRange, lastPacketInRange, &records)
		firstPacketInRange, lastPacketInRange = pk, pk
	}


	ack.writeRecord(buf, firstPacketInRange, lastPacketInRange, &records)

	binary.BigEndian.PutUint16(buf.Bytes()[lenOffset:], records)
	return n
}

func (ack *acknowledgement) writeRecord(buf *bytes.Buffer, first, last uint24, count *uint16) {
	if first == last {



		buf.WriteByte(packetSingle)
		writeUint24(buf, first)
	} else {



		buf.WriteByte(packetRange)
		writeUint24(buf, first)
		writeUint24(buf, last)
	}
	*count++
}



func (ack *acknowledgement) read(b []byte) error {
	const maxAcknowledgementPackets = 8192
	if len(b) < 2 {
		return io.ErrUnexpectedEOF
	}
	offset := 2
	for range binary.BigEndian.Uint16(b) {
		if len(b)-offset < 4 {
			return io.ErrUnexpectedEOF
		}
		switch b[offset] {
		case packetRange:
			if len(b)-offset < 7 {
				return io.ErrUnexpectedEOF
			}
			start, end := loadUint24(b[offset+1:]), loadUint24(b[offset+4:])
			if uint24(len(ack.packets))+end-start > maxAcknowledgementPackets {
				return errMaxAcknowledgement
			}
			for pk := start; pk <= end; pk++ {
				ack.packets = append(ack.packets, pk)
			}
			offset += 7
		case packetSingle:
			if len(ack.packets)+1 > maxAcknowledgementPackets {
				return errMaxAcknowledgement
			}
			ack.packets = append(ack.packets, loadUint24(b[offset+1:]))
			offset += 4
		}
	}
	return nil
}
