package raknet


type packetQueue struct {
	lowest  uint24
	highest uint24
	queue   map[uint24][]byte
}


func newPacketQueue() *packetQueue {
	return &packetQueue{queue: make(map[uint24][]byte)}
}



func (queue *packetQueue) put(index uint24, packet []byte) bool {
	if index < queue.lowest {
		return false
	}
	if _, ok := queue.queue[index]; ok {
		return false
	}
	if index >= queue.highest {
		queue.highest = index + 1
	}
	queue.queue[index] = packet
	return true
}




func (queue *packetQueue) fetch() (packets [][]byte) {
	index := queue.lowest
	for index < queue.highest {
		packet, ok := queue.queue[index]
		if !ok {
			break
		}
		delete(queue.queue, index)
		packets = append(packets, packet)
		index++
	}
	queue.lowest = index
	return
}


func (queue *packetQueue) WindowSize() uint24 {
	return queue.highest - queue.lowest
}
