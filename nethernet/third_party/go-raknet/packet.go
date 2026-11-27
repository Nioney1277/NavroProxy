package raknet

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
)

const (


	bitFlagDatagram = 0x80

	bitFlagACK = 0x40

	bitFlagNACK = 0x20


	bitFlagNeedsBAndAS = 0x04
)

const (





	reliabilityUnreliable reliability = iota



	reliabilityUnreliableSequenced


	reliabilityReliable


	reliabilityReliableOrdered



	reliabilityReliableSequenced



	splitFlag = 0x10
)

type reliability byte



func (r reliability) reliable() bool {
	return r == reliabilityReliable ||
		r == reliabilityReliableOrdered ||
		r == reliabilityReliableSequenced
}




func (r reliability) sequenced() bool {
	return r == reliabilityUnreliableSequenced ||
		r == reliabilityReliableSequenced
}



func (r reliability) sequencedOrOrdered() bool {
	return r.sequenced() || r == reliabilityReliableOrdered
}



type packet struct {
	reliability reliability

	messageIndex  uint24
	sequenceIndex uint24
	orderIndex    uint24

	content []byte

	split      bool
	splitCount uint32
	splitIndex uint32
	splitID    uint16
}


func (pk *packet) write(buf *bytes.Buffer) {
	header := pk.reliability << 5
	if pk.split {
		header |= splitFlag
	}

	buf.WriteByte(byte(header))
	writeUint16(buf, uint16(len(pk.content))<<3)
	if pk.reliability.reliable() {
		writeUint24(buf, pk.messageIndex)
	}
	if pk.reliability.sequenced() {
		writeUint24(buf, pk.sequenceIndex)
	}
	if pk.reliability.sequencedOrOrdered() {
		writeUint24(buf, pk.orderIndex)

		buf.WriteByte(0)
	}
	if pk.split {
		writeUint32(buf, pk.splitCount)
		writeUint16(buf, pk.splitID)
		writeUint32(buf, pk.splitIndex)
	}
	buf.Write(pk.content)
}


func (pk *packet) read(b []byte) (int, error) {
	if len(b) < 3 {
		return 0, io.ErrUnexpectedEOF
	}
	header := b[0]
	pk.split = (header & splitFlag) != 0
	pk.reliability = reliability((header & 224) >> 5)

	n := binary.BigEndian.Uint16(b[1:]) >> 3
	if n == 0 {
		return 0, errors.New("invalid packet length: cannot be 0")
	}
	offset := 3

	if pk.reliability.reliable() {
		if len(b)-offset < 3 {
			return 0, io.ErrUnexpectedEOF
		}
		pk.messageIndex = loadUint24(b[offset:])
		offset += 3
	}

	if pk.reliability.sequenced() {
		if len(b)-offset < 3 {
			return 0, io.ErrUnexpectedEOF
		}
		pk.sequenceIndex = loadUint24(b[offset:])
		offset += 3
	}

	if pk.reliability.sequencedOrOrdered() {
		if len(b)-offset < 4 {
			return 0, io.ErrUnexpectedEOF
		}
		pk.orderIndex = loadUint24(b[offset:])

		offset += 4
	}

	if pk.split {
		if len(b)-offset < 10 {
			return 0, io.ErrUnexpectedEOF
		}
		pk.splitCount = binary.BigEndian.Uint32(b[offset:])
		pk.splitID = binary.BigEndian.Uint16(b[offset+4:])
		pk.splitIndex = binary.BigEndian.Uint32(b[offset+6:])
		offset += 10
	}

	pk.content = make([]byte, n)
	if got := copy(pk.content, b[offset:]); got != int(n) {
		return 0, io.ErrUnexpectedEOF
	}
	return offset + int(n), nil
}

const (







	packetAdditionalSize = 1 + 3 + 1 + 2 + 3 + 3 + 1



	splitAdditionalSize = 4 + 2 + 4
)



func split(b []byte, mtu uint16) [][]byte {
	n := len(b)
	maxSize := int(mtu - packetAdditionalSize)

	if n > maxSize {



		maxSize -= splitAdditionalSize
	}


	fragmentCount := n/maxSize + min(n%maxSize, 1)
	fragments := make([][]byte, fragmentCount)
	for i := range fragmentCount - 1 {
		fragments[i] = b[:maxSize]
		b = b[maxSize:]
	}
	fragments[len(fragments)-1] = b
	return fragments
}
