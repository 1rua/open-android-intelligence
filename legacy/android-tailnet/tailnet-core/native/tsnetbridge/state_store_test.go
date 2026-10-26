package tsnetbridge

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"sort"
	"sync"
	"testing"

	"tailscale.com/ipn"
)

func sortedKeys(m map[ipn.StateKey][]byte) []ipn.StateKey {
	keys := make([]ipn.StateKey, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(a, b int) bool { return keys[a] < keys[b] })
	return keys
}

type discardingSink struct {
	mu    sync.Mutex
	blobs [][]byte
}

func (d *discardingSink) PersistState(b []byte) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.blobs = append(d.blobs, bytes.Clone(b))
	return nil
}

func (d *discardingSink) count() int {
	d.mu.Lock()
	defer d.mu.Unlock()
	return len(d.blobs)
}

func (d *discardingSink) last() []byte {
	d.mu.Lock()
	defer d.mu.Unlock()
	if len(d.blobs) == 0 {
		return nil
	}
	return bytes.Clone(d.blobs[len(d.blobs)-1])
}

// failingSink fails on the failOn-th PersistState call.
type failingSink struct {
	mu     sync.Mutex
	n      int
	failOn int
}

func (f *failingSink) PersistState(b []byte) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.n++
	if f.n == f.failOn {
		return errors.New("injected persist failure")
	}
	return nil
}

func (f *failingSink) calls() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.n
}

func TestStateStoreDeterministicOrdering(t *testing.T) {
	s := newMemoryStateStore(&discardingSink{})
	insert := []struct {
		k string
		v string
	}{
		{"m", "three"},
		{"a", "one"},
		{"_z", "zero"},
	}
	for _, in := range insert {
		if err := s.WriteState(ipn.StateKey(in.k), []byte(in.v)); err != nil {
			t.Fatalf("WriteState(%q): %v", in.k, err)
		}
	}

	snap := s.snapshotLocked()
	if len(snap) == 0 {
		t.Fatalf("snapshotLocked returned empty blob")
	}
	decoded, err := decodeStateSnapshot(snap)
	if err != nil {
		t.Fatalf("decodeStateSnapshot: %v", err)
	}
	keys := sortedKeys(decoded)
	if len(keys) != 3 || keys[0] != "_z" || keys[1] != "a" || keys[2] != "m" {
		t.Fatalf("entries not deterministically sorted: %v", keys)
	}

	snap2 := s.snapshotLocked()
	if !bytes.Equal(snap, snap2) {
		t.Fatalf("snapshot not deterministic across calls")
	}
}

func TestStateStoreCorruptedDigest(t *testing.T) {
	s := newMemoryStateStore(&discardingSink{})
	if err := s.WriteState(ipn.StateKey("k"), []byte("v1")); err != nil {
		t.Fatalf("write: %v", err)
	}
	snap := s.snapshotLocked()
	if len(snap) < 33 {
		t.Fatalf("snapshot too short to hold digest")
	}
	corrupt := bytes.Clone(snap)
	corrupt[len(corrupt)-1] ^= 0xff

	if err := s.RestoreFrom(corrupt); err == nil {
		t.Fatalf("RestoreFrom accepted corrupted digest")
	}
	if got, err := s.ReadState(ipn.StateKey("k")); err != nil || !bytes.Equal(got, []byte("v1")) {
		t.Fatalf("failed restore mutated committed state: got %q err %v", got, err)
	}
}

func TestStateStoreDuplicateKey(t *testing.T) {
	out := []byte("ALSTATE1")
	out = append(out, 1)
	mustWriteEntry := func(key, val string) {
		var hdr [4]byte
		binary.BigEndian.PutUint32(hdr[:], uint32(len(key)))
		out = append(out, hdr[:]...)
		out = append(out, key...)
		binary.BigEndian.PutUint32(hdr[:], uint32(len(val)))
		out = append(out, hdr[:]...)
		out = append(out, val...)
	}
	mustWriteEntry("k", "one")
	mustWriteEntry("k", "two")
	sum := sha256.Sum256(out)
	out = append(out, sum[:]...)

	s := newMemoryStateStore(nil)
	if err := s.RestoreFrom(out); err == nil {
		t.Fatalf("RestoreFrom accepted duplicate state key")
	}
}

func TestStateStoreTruncation(t *testing.T) {
	s := newMemoryStateStore(&discardingSink{})
	if err := s.WriteState(ipn.StateKey("k"), []byte("v")); err != nil {
		t.Fatalf("write: %v", err)
	}
	snap := s.snapshotLocked()
	for _, n := range []int{7, 8, 9, 20, len(snap) - 33} {
		if n < 0 || n >= len(snap) {
			continue
		}
		if err := s.RestoreFrom(snap[:n]); err == nil {
			t.Fatalf("RestoreFrom accepted truncated blob (%d bytes)", n)
		}
	}
}

func TestStateStoreDefensiveCopies(t *testing.T) {
	s := newMemoryStateStore(&discardingSink{})

	val := []byte("secret-material-abc")
	if err := s.WriteState(ipn.StateKey("k"), val); err != nil {
		t.Fatalf("write: %v", err)
	}
	val[0] = 'X' // mutate the caller's buffer

	if got, _ := s.ReadState(ipn.StateKey("k")); !bytes.Equal(got, []byte("secret-material-abc")) {
		t.Fatalf("store retained caller-owned buffer: %q", got)
	}

	got, err := s.ReadState(ipn.StateKey("k"))
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	got[0] = 'Y' // mutate the returned buffer
	if again, _ := s.ReadState(ipn.StateKey("k")); !bytes.Equal(again, []byte("secret-material-abc")) {
		t.Fatalf("store mutated through returned copy: %q", again)
	}

	last := s.snapshotLocked()
	last[9] ^= 0xff // mutate the blob handed to the sink / returned by snapshot
	if again, _ := s.ReadState(ipn.StateKey("k")); !bytes.Equal(again, []byte("secret-material-abc")) {
		t.Fatalf("store mutated through persisted blob: %q", again)
	}
}

func TestStateStoreSynchronousCallback(t *testing.T) {
	sink := &discardingSink{}
	s := newMemoryStateStore(sink)
	for i := 0; i < 5; i++ {
		k := fmt.Sprintf("key%d", i)
		if err := s.WriteState(ipn.StateKey(k), []byte(k)); err != nil {
			t.Fatalf("write %d: %v", i, err)
		}
		if want := i + 1; sink.count() != want {
			t.Fatalf("write %d: PersistState not synchronous, calls=%d want=%d", i, sink.count(), want)
		}
	}
	last := sink.last()
	decoded, err := decodeStateSnapshot(last)
	if err != nil {
		t.Fatalf("persisted blob not decodable: %v", err)
	}
	if len(decoded) != 5 {
		t.Fatalf("persisted blob missing entries: %v", sortedKeys(decoded))
	}
	for i := 0; i < 5; i++ {
		k := fmt.Sprintf("key%d", i)
		if !bytes.Equal(decoded[ipn.StateKey(k)], []byte(k)) {
			t.Fatalf("persisted blob entry %s wrong", k)
		}
	}
}

func TestStateStoreConcurrentWrites(t *testing.T) {
	sink := &discardingSink{}
	s := newMemoryStateStore(sink)

	const goroutines = 8
	const perGoroutine = 64
	var wg sync.WaitGroup
	for g := 0; g < goroutines; g++ {
		wg.Add(1)
		go func(g int) {
			defer wg.Done()
			for i := 0; i < perGoroutine; i++ {
				k := fmt.Sprintf("g%d-key-%d", g, i)
				v := fmt.Sprintf("value-%d-%d", g, i)
				if err := s.WriteState(ipn.StateKey(k), []byte(v)); err != nil {
					t.Errorf("concurrent write: %v", err)
					return
				}
			}
		}(g)
	}
	wg.Wait()

	for g := 0; g < goroutines; g++ {
		for i := 0; i < perGoroutine; i++ {
			k := fmt.Sprintf("g%d-key-%d", g, i)
			want := fmt.Sprintf("value-%d-%d", g, i)
			got, err := s.ReadState(ipn.StateKey(k))
			if err != nil {
				t.Fatalf("read %s: %v", k, err)
			}
			if !bytes.Equal(got, []byte(want)) {
				t.Fatalf("key %s torn/trace state: got %q want %q", k, got, want)
			}
		}
	}

	if _, err := decodeStateSnapshot(s.snapshotLocked()); err != nil {
		t.Fatalf("final snapshot not decodable: %v", err)
	}
}

func TestStateStoreCallbackFailureKeepsMemory(t *testing.T) {
	fail := &failingSink{failOn: 2}
	s := newMemoryStateStore(fail)

	if err := s.WriteState(ipn.StateKey("k"), []byte("v1")); err != nil {
		t.Fatalf("first write: %v", err)
	}
	err := s.WriteState(ipn.StateKey("k"), []byte("v2"))
	if err == nil {
		t.Fatalf("second write must propagate the sink's error")
	}
	var berr *BridgeError
	if !errors.As(err, &berr) || berr.Code != ErrCodeStatePersistFailed {
		t.Fatalf("error is not a STATE_PERSIST_FAILED bridge error: %v", err)
	}
	if got, _ := s.ReadState(ipn.StateKey("k")); !bytes.Equal(got, []byte("v1")) {
		t.Fatalf("failed callback mutated committed state: got %q want %q", got, "v1")
	}
	failCalls := fail.calls()
	if failCalls != 2 {
		t.Fatalf("sink call count = %d, want 2", failCalls)
	}
	if err := s.WriteState(ipn.StateKey("k"), []byte("v3")); err != nil {
		t.Fatalf("write after failure: %v", err)
	}
	if got, _ := s.ReadState(ipn.StateKey("k")); !bytes.Equal(got, []byte("v3")) {
		t.Fatalf("recovery write did not persist: %q", got)
	}
}

func TestStateStoreRestoredMultiKeyReads(t *testing.T) {
	sink := &discardingSink{}
	s := newMemoryStateStore(sink)
	entries := map[string]string{"profiles": "{}", "_machinekey": "\"MKEY\"", "_current-profile": "p1"}
	for k, v := range entries {
		if err := s.WriteState(ipn.StateKey(k), []byte(v)); err != nil {
			t.Fatalf("write %s: %v", k, err)
		}
	}
	blob := sink.last()

	restored := newMemoryStateStore(nil)
	if err := restored.RestoreFrom(blob); err != nil {
		t.Fatalf("RestoreFrom: %v", err)
	}
	for k, v := range entries {
		got, err := restored.ReadState(ipn.StateKey(k))
		if err != nil {
			t.Fatalf("restored read %s: %v", k, err)
		}
		if !bytes.Equal(got, []byte(v)) {
			t.Fatalf("restored %s = %q, want %q", k, got, v)
		}
	}
}

func TestStateStoreErrStateNotExist(t *testing.T) {
	s := newMemoryStateStore(nil)
	if _, err := s.ReadState(ipn.StateKey("absent")); !errors.Is(err, ipn.ErrStateNotExist) {
		t.Fatalf("absent key: want %v, got %v", ipn.ErrStateNotExist, err)
	}
	// Deleting a present key must also surface ErrStateNotExist afterwards.
	if err := s.WriteState(ipn.StateKey("gone"), []byte("x")); err != nil {
		t.Fatalf("write: %v", err)
	}
	if err := s.WriteState(ipn.StateKey("gone"), nil); err != nil {
		t.Fatalf("delete: %v", err)
	}
	if _, err := s.ReadState(ipn.StateKey("gone")); !errors.Is(err, ipn.ErrStateNotExist) {
		t.Fatalf("deleted key: want %v, got %v", ipn.ErrStateNotExist, err)
	}
}

func TestStateStoreZeroizationOnClose(t *testing.T) {
	s := newMemoryStateStore(&discardingSink{})
	if err := s.WriteState(ipn.StateKey("k"), []byte("super-secret-node-key-bytes")); err != nil {
		t.Fatalf("write: %v", err)
	}
	if _, err := s.ReadState(ipn.StateKey("k")); err != nil {
		t.Fatalf("read before close: %v", err)
	}
	s.Close()
	s.Close() // idempotent

	for k, v := range s.states {
		for i, b := range v {
			if b != 0 {
				t.Fatalf("value for key %q not zeroized at byte %d (0x%02x)", k, i, b)
			}
		}
	}
	if _, err := s.ReadState(ipn.StateKey("k")); err == nil {
		t.Fatalf("ReadState succeeded after Close")
	}
	if err := s.WriteState(ipn.StateKey("k"), []byte("x")); err == nil {
		t.Fatalf("WriteState succeeded after Close")
	}
	if err := s.RestoreFrom(s.snapshotLocked()); err == nil {
		t.Fatalf("RestoreFrom succeeded after Close")
	}
}

func TestStateStoreOversizedBlob(t *testing.T) {
	sink := &discardingSink{}
	s := newMemoryStateStore(sink)

	tooBig := make([]byte, maxStateBlob)
	if err := s.WriteState(ipn.StateKey("k"), tooBig); err == nil {
		t.Fatalf("WriteState accepted an oversized snapshot")
	}
	if sink.count() != 0 {
		t.Fatalf("sink must not be called for an oversized write")
	}
	if _, err := s.ReadState(ipn.StateKey("k")); !errors.Is(err, ipn.ErrStateNotExist) {
		t.Fatalf("oversized write mutated the store: %v", err)
	}

	blob := make([]byte, maxStateBlob+1)
	if err := s.RestoreFrom(blob); err == nil {
		t.Fatalf("RestoreFrom accepted an oversized blob")
	}

	// A blob just under the limit round-trips cleanly.
	ok := make([]byte, 4_000_000)
	for i := range ok {
		ok[i] = 0x42
	}
	if err := s.WriteState(ipn.StateKey("big"), ok); err != nil {
		t.Fatalf("write near-limit value: %v", err)
	}
	got, err := s.ReadState(ipn.StateKey("big"))
	if err != nil {
		t.Fatalf("read near-limit value: %v", err)
	}
	if !bytes.Equal(got, ok) {
		t.Fatalf("near-limit value corrupted on round trip")
	}
}
