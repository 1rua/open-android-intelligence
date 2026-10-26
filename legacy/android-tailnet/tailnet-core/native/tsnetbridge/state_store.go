package tsnetbridge

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"sort"
	"sync"

	"tailscale.com/ipn"
)

const (
	stateMagic   = "ALSTATE1"
	stateVersion = 1
	maxStateBlob = 4_194_304
)

// StateSink is the only callback boundary exported to Kotlin. Every Tailscale
// state write produces the full binary snapshot; Kotlin must durably persist
// it before returning nil, or the write fails closed.
type StateSink interface {
	PersistState(state []byte) error
}

// memoryStateStore implements ipn.StateStore over the callback-backed state
// sink. State is never written to filesystem or Android Backup by Go.
type memoryStateStore struct {
	mu           sync.Mutex
	states       map[ipn.StateKey][]byte
	sink         StateSink
	closed       bool
	persistCalls int
	firstDurable chan struct{}
	firstOnce    sync.Once
}

func newMemoryStateStore(sink StateSink) *memoryStateStore {
	return &memoryStateStore{
		states:       make(map[ipn.StateKey][]byte),
		sink:         sink,
		firstDurable: make(chan struct{}),
	}
}

func (s *memoryStateStore) ReadState(id ipn.StateKey) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil, ipn.ErrStateNotExist
	}
	bs, ok := s.states[id]
	if !ok {
		return nil, ipn.ErrStateNotExist
	}
	return bytes.Clone(bs), nil
}

func (s *memoryStateStore) WriteState(id ipn.StateKey, bs []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return newBridgeError(ErrCodeStatePersistFailed, "state store is closed")
	}

	next := make(map[ipn.StateKey][]byte, len(s.states)+1)
	for k, v := range s.states {
		next[k] = bytes.Clone(v)
	}
	if bs == nil {
		delete(next, id)
	} else {
		next[id] = bytes.Clone(bs)
	}
	snapshot := encodeStateSnapshot(next)
	if len(snapshot) > maxStateBlob {
		return newBridgeError(ErrCodeStatePersistFailed, "node state exceeds the allowed size")
	}
	if s.sink != nil {
		if err := s.sink.PersistState(bytes.Clone(snapshot)); err != nil {
			return newBridgeError(ErrCodeStatePersistFailed, "node state persistence failed")
		}
	}
	s.states = next
	s.persistCalls++
	if s.persistCalls == 1 {
		s.firstOnce.Do(func() { close(s.firstDurable) })
	}
	return nil
}

func (s *memoryStateStore) snapshotLocked() []byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil
	}
	return encodeStateSnapshot(s.states)
}

func (s *memoryStateStore) RestoreFrom(blob []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return newBridgeError(ErrCodeStateRestoreFailed, "state store is closed")
	}
	if len(blob) == 0 {
		return newBridgeError(ErrCodeStateRestoreFailed, "state blob is empty")
	}
	if len(blob) > maxStateBlob {
		return newBridgeError(ErrCodeStateRestoreFailed, "state blob exceeds the allowed size")
	}
	restored, err := decodeStateSnapshot(blob)
	if err != nil {
		return err
	}
	s.states = restored
	return nil
}

func (s *memoryStateStore) Close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return
	}
	for key, value := range s.states {
		wipe(value)
		delete(s.states, key)
	}
	s.states = nil
	s.sink = nil
	s.closed = true
}

func (s *memoryStateStore) durableCalls() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.persistCalls
}

func (s *memoryStateStore) waitFirstDurable(ctx context.Context) bool {
	select {
	case <-s.firstDurable:
		return true
	case <-ctx.Done():
		return false
	}
}

func encodeStateSnapshot(states map[ipn.StateKey][]byte) []byte {
	keys := make([]string, 0, len(states))
	for k := range states {
		keys = append(keys, string(k))
	}
	sort.Strings(keys)

	out := make([]byte, 0, 128)
	out = append(out, stateMagic...)
	out = append(out, stateVersion)
	var hdr [4]byte
	for _, key := range keys {
		val := states[ipn.StateKey(key)]
		binary.BigEndian.PutUint32(hdr[:], uint32(len(key)))
		out = append(out, hdr[:]...)
		out = append(out, key...)
		binary.BigEndian.PutUint32(hdr[:], uint32(len(val)))
		out = append(out, hdr[:]...)
		out = append(out, val...)
	}
	sum := sha256.Sum256(out)
	out = append(out, sum[:]...)
	return out
}

func decodeStateSnapshot(blob []byte) (map[ipn.StateKey][]byte, error) {
	if len(blob) < sha256.Size || !bytes.Equal(blob[:len(stateMagic)], []byte(stateMagic)) {
		return nil, newBridgeError(ErrCodeStateRestoreFailed, "invalid state magic")
	}
	payload := blob[:len(blob)-sha256.Size]
	sum := sha256.Sum256(payload)
	if !bytes.Equal(sum[:], blob[len(blob)-sha256.Size:]) {
		return nil, newBridgeError(ErrCodeStateRestoreFailed, "state digest mismatch")
	}
	body := payload[len(stateMagic):]
	if len(body) == 0 || body[0] != stateVersion {
		return nil, newBridgeError(ErrCodeStateRestoreFailed, "unsupported state version")
	}
	body = body[1:]
	states := make(map[ipn.StateKey][]byte)
	for len(body) > 0 {
		if len(body) < 4 {
			return nil, newBridgeError(ErrCodeStateRestoreFailed, "truncated state key header")
		}
		kl := binary.BigEndian.Uint32(body[:4])
		body = body[4:]
		if uint64(kl) > uint64(len(body)) {
			return nil, newBridgeError(ErrCodeStateRestoreFailed, "state key length overflows blob")
		}
		key := string(body[:kl])
		body = body[kl:]
		if len(body) < 4 {
			return nil, newBridgeError(ErrCodeStateRestoreFailed, "truncated state value header")
		}
		vl := binary.BigEndian.Uint32(body[:4])
		body = body[4:]
		if uint64(vl) > uint64(len(body)) {
			return nil, newBridgeError(ErrCodeStateRestoreFailed, "state value length overflows blob")
		}
		value := bytes.Clone(body[:vl])
		body = body[vl:]
		if _, exists := states[ipn.StateKey(key)]; exists {
			return nil, newBridgeError(ErrCodeStateRestoreFailed, "duplicate state key")
		}
		states[ipn.StateKey(key)] = value
	}
	return states, nil
}
