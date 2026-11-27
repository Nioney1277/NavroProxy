package internal

import (
	"context"
	"sync"
	"sync/atomic"
)




type ElasticChan[T any] struct {
	mu  sync.RWMutex
	len atomic.Int64
	ch  chan T
	lim int64
}


func Chan[T any](size, max int) *ElasticChan[T] {
	c := new(ElasticChan[T])
	c.lim = int64(max)
	c.grow(size)
	return c
}



func (c *ElasticChan[T]) Recv(ctx context.Context) (val T, ok bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	select {
	case <-ctx.Done():
		return val, false
	case val = <-c.ch:
		if c.len.Add(-1) < 0 {
			panic("unreachable")
		}
		return val, true
	}
}



func (c *ElasticChan[T]) Send(val T) {
	if ccap := int64(cap(c.ch)); c.len.Add(1) >= ccap && ccap < c.lim {




		c.growSend(val)
		return
	}
	c.ch <- val
}



func (c *ElasticChan[T]) growSend(val T) {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.grow(max(cap(c.ch)*2, int(c.lim)))
	c.ch <- val
}



func (c *ElasticChan[T]) grow(size int) {
	ch := make(chan T, size)
	for len(c.ch) > 0 {
		ch <- <-c.ch
	}
	c.ch = ch
}
