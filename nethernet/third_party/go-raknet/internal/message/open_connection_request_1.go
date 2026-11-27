package message

import (
	"io"
	"sync"
)

type OpenConnectionRequest1 struct {
	ClientProtocol byte
	MTU            uint16
}


var (
	cachedOCR1Mu sync.Mutex
	cachedOCR1   = map[uint32][]byte{}
)

func (pk *OpenConnectionRequest1) MarshalBinary() (data []byte, err error) {
	cachedOCR1Mu.Lock()
	defer cachedOCR1Mu.Unlock()
	key := uint32(pk.ClientProtocol)<<16 | uint32(pk.MTU)
	if b, ok := cachedOCR1[key]; ok {


		return b, nil
	}
	b := make([]byte, pk.MTU-20-8)
	b[0] = IDOpenConnectionRequest1
	copy(b[1:], unconnectedMessageSequence[:])
	b[17] = pk.ClientProtocol

	cachedOCR1[key] = b
	return b, nil
}

func (pk *OpenConnectionRequest1) UnmarshalBinary(data []byte) error {
	if len(data) < 17 {
		return io.ErrUnexpectedEOF
	}

	pk.ClientProtocol = data[16]
	pk.MTU = uint16(len(data) + 20 + 8 + 1)
	return nil
}
