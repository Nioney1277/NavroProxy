package raknet

import (
	"maps"
	"time"
)



type resendMap struct {
	unacknowledged map[uint24]resendRecord
	delays         map[time.Time]time.Duration
}



type resendRecord struct {
	pk        *packet
	timestamp time.Time
}


func newRecoveryQueue() *resendMap {
	return &resendMap{
		delays:         make(map[time.Time]time.Duration),
		unacknowledged: make(map[uint24]resendRecord),
	}
}


func (m *resendMap) add(index uint24, pk *packet) {
	m.unacknowledged[index] = resendRecord{pk: pk, timestamp: time.Now()}
}



func (m *resendMap) acknowledge(index uint24) (*packet, bool) {
	return m.remove(index, 1)
}



func (m *resendMap) retransmit(index uint24) (*packet, bool) {
	return m.remove(index, 2)
}



func (m *resendMap) remove(index uint24, mul int) (*packet, bool) {
	record, ok := m.unacknowledged[index]
	if !ok {
		return nil, false
	}
	delete(m.unacknowledged, index)

	now := time.Now()
	m.delays[now] = now.Sub(record.timestamp) * time.Duration(mul)
	return record.pk, true
}




func (m *resendMap) rtt(now time.Time) time.Duration {
	const rttCalculationWindow = time.Second * 5
	maps.DeleteFunc(m.delays, func(t time.Time, duration time.Duration) bool {

		return now.Sub(t) > rttCalculationWindow
	})
	if len(m.delays) == 0 {


		return time.Millisecond * 50
	}
	var total time.Duration
	for _, rtt := range m.delays {
		total += rtt
	}
	return total / time.Duration(len(m.delays))
}
