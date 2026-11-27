package raknet

import (
	"time"
)


type datagramWindow struct {
	lowest, highest uint24
	queue           map[uint24]time.Time
}


func newDatagramWindow() *datagramWindow {
	return &datagramWindow{queue: make(map[uint24]time.Time)}
}


func (win *datagramWindow) add(index uint24) bool {
	if win.seen(index) {
		return false
	}
	win.highest = max(win.highest, index+1)
	win.queue[index] = time.Now()
	return true
}


func (win *datagramWindow) seen(index uint24) bool {
	if index < win.lowest {
		return true
	}
	_, ok := win.queue[index]
	return ok
}



func (win *datagramWindow) shift() (n int) {
	var index uint24
	for index = win.lowest; index < win.highest; index++ {
		if _, ok := win.queue[index]; !ok {
			break
		}
		delete(win.queue, index)
		n++
	}
	win.lowest = index
	return n
}




func (win *datagramWindow) missing(since time.Duration) (indices []uint24) {
	missing := false
	for index := int(win.highest) - 1; index >= int(win.lowest); index-- {
		i := uint24(index)
		t, ok := win.queue[i]
		if ok {
			if time.Since(t) >= since {


				missing = true
			}
			continue
		}
		if missing {
			indices = append(indices, i)
			win.queue[i] = time.Time{}
		}
	}
	win.shift()
	return indices
}


func (win *datagramWindow) size() uint24 {
	return win.highest - win.lowest
}
