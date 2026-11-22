package main

import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"time"

	"github.com/helix-bedrock/nethernet"
	"github.com/helix-bedrock/nethernet/endpoint"
)

const stockBridgeMagic = 0x4e564236

func selectedIP(conn *nethernet.Conn) net.IP {
	address, ok := conn.RemoteAddr().(*nethernet.Addr)
	if !ok || address.SelectedCandidate == nil {
		return nil
	}
	ip := net.ParseIP(address.SelectedCandidate.Address)
	if v4 := ip.To4(); v4 != nil {
		return v4
	}
	return ip
}

func stockSignaling() *endpoint.Client {
	return (endpoint.ClientConfig{HTTPClient: &http.Client{
		Transport: &http.Transport{
			DialContext:     (&net.Dialer{Timeout: 5 * time.Second}).DialContext,
			IdleConnTimeout: 5 * time.Second,
		},
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}}).New()
}

type batchConn interface {
	Context() context.Context
	ReadPacket() ([]byte, error)
	Write([]byte) (int, error)
}

func bridgeBatches(front, back batchConn, ipc net.Conn, in *bufio.Reader, out *bufio.Writer) error {
	const maxGroupCount = 64
	const maxGroupBytes = 2 << 20

	events := make(chan event)
	done := make(chan struct{})
	defer close(done)
	read := func(conn batchConn, backend bool) {
		for {
			batch, err := conn.ReadPacket()
			select {
			case events <- event{batch: batch, backend: backend, err: err}:
			case <-done:
				return
			}
			if err != nil {
				return
			}
		}
	}
	go read(front, false)
	go read(back, true)
	ticker := time.NewTicker(50 * time.Millisecond)
	defer ticker.Stop()
	group := make([]event, 0, maxGroupCount)
	var carry *event
	for {
		var first event
		if carry != nil {
			first, carry = *carry, nil
		} else {
			select {
			case <-front.Context().Done():
				return front.Context().Err()
			case <-back.Context().Done():
				return back.Context().Err()
			case <-ticker.C:
				first.tick = true
			case first = <-events:
			}
		}
		if first.err != nil {
			return first.err
		}
		group = append(group[:0], first)
		size := len(first.batch)
	drain:
		for len(group) < maxGroupCount && size < maxGroupBytes {
			select {
			case next := <-events:
				if next.err != nil || len(next.batch) > maxGroupBytes-size {
					carry = &next
					break drain
				}
				group = append(group, next)
				size += len(next.batch)
			default:
				break drain
			}
		}
		if err := exchangeBatchGroup(front, back, ipc, in, out, group); err != nil {
			return err
		}
		clear(group)
	}
}

func exchangeBatchGroup(front, back batchConn, ipc net.Conn, in *bufio.Reader, out *bufio.Writer, group []event) error {
	const maxBatch = 16 << 20
	if err := ipc.SetDeadline(time.Now().Add(30 * time.Second)); err != nil {
		return err
	}
	if err := out.WriteByte(byte(len(group))); err != nil {
		return err
	}
	for _, item := range group {
		if len(item.batch) > maxBatch || (len(item.batch) == 0) != item.tick {
			return errors.New("invalid NetherNet batch size")
		}
		direction := byte(0)
		if item.backend {
			direction = 1
		} else if item.tick {
			direction = 2
		}
		if err := out.WriteByte(direction); err != nil {
			return err
		}
		if err := binary.Write(out, binary.BigEndian, uint32(len(item.batch))); err != nil {
			return err
		}
		if _, err := out.Write(item.batch); err != nil {
			return err
		}
	}
	if err := out.Flush(); err != nil {
		return err
	}
	for _, item := range group {
		var size int32
		if err := binary.Read(in, binary.BigEndian, &size); err != nil {
			return fmt.Errorf("native batch adapter reply: %w", err)
		}
		if size != -1 {
			if item.tick {
				return errors.New("unexpected forwarded tick")
			}
			dst := back
			if item.backend {
				dst = front
			}
			if err := forwardBatch(in, dst, size); err != nil {
				return err
			}
		}
		for _, dst := range []batchConn{front, back} {
			var count uint32
			if err := binary.Read(in, binary.BigEndian, &count); err != nil {
				return err
			}
			if count > 4096 {
				return errors.New("too many native batch adapter outputs")
			}
			for range count {
				if err := binary.Read(in, binary.BigEndian, &size); err != nil {
					return err
				}
				if err := forwardBatch(in, dst, size); err != nil {
					return err
				}
			}
		}
	}
	return nil
}

func forwardBatch(in io.Reader, dst batchConn, size int32) error {
	if size <= 0 || size > 16<<20 {
		return errors.New("invalid native batch adapter output size")
	}
	batch := make([]byte, int(size))
	if _, err := io.ReadFull(in, batch); err != nil {
		return err
	}
	_, err := dst.Write(batch)
	return err
}
