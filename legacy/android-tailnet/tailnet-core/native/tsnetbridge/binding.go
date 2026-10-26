package tsnetbridge

import (
	"bytes"
	"crypto/subtle"
	"encoding/binary"
	"strconv"
)

const (
	bindingMagic   = "ALBIND1"
	bindingVersion = 1
	bindingFields  = 6
)

// verifiedBinding is the internal representation of the ticket-bound Bridge
// identity. Kotlin encodes the ALBIND1 bytes from already-verified pairing
// material; native compares it to the enrollment without accepting endpoints.
type verifiedBinding struct {
	DeviceID       string
	BridgeIdentity string
	TicketDigest   string
	Generation     uint64
	PolicyRevision uint64
	PolicyDigest   string
}

func decodeVerifiedBinding(blob []byte) (*verifiedBinding, error) {
	if len(blob) == 0 || len(blob) > maxBundleBytes {
		return nil, newBridgeError(ErrCodeInvalidBinding, "invalid Bridge binding")
	}
	if len(blob) < len(bindingMagic)+1 || !bytes.Equal(blob[:len(bindingMagic)], []byte(bindingMagic)) {
		return nil, newBridgeError(ErrCodeInvalidBinding, "invalid Bridge binding magic")
	}
	body := blob[len(bindingMagic):]
	if body[0] != bindingVersion {
		return nil, newBridgeError(ErrCodeInvalidBinding, "unsupported Bridge binding version")
	}
	body = body[1:]
	fields := make([][]byte, 0, bindingFields)
	for i := 0; i < bindingFields; i++ {
		if len(body) < 4 {
			return nil, newBridgeError(ErrCodeInvalidBinding, "truncated Bridge binding field")
		}
		n := binary.BigEndian.Uint32(body[:4])
		body = body[4:]
		if uint64(n) > uint64(len(body)) {
			return nil, newBridgeError(ErrCodeInvalidBinding, "Bridge binding length overflows blob")
		}
		fields = append(fields, bytes.Clone(body[:n]))
		body = body[n:]
	}
	if len(body) != 0 {
		return nil, newBridgeError(ErrCodeInvalidBinding, "trailing Bridge binding bytes")
	}
	b := &verifiedBinding{
		DeviceID:       string(fields[0]),
		BridgeIdentity: string(fields[1]),
		TicketDigest:   string(fields[2]),
	}
	if err := parseUintField(fields[3], &b.Generation); err != nil {
		return nil, newBridgeError(ErrCodeInvalidBinding, "invalid Bridge binding generation")
	}
	if err := parseUintField(fields[4], &b.PolicyRevision); err != nil {
		return nil, newBridgeError(ErrCodeInvalidBinding, "invalid Bridge binding policy revision")
	}
	b.PolicyDigest = string(fields[5])
	if b.DeviceID == "" || b.BridgeIdentity == "" || !validDigest(b.TicketDigest) || !validDigest(b.PolicyDigest) {
		return nil, newBridgeError(ErrCodeInvalidBinding, "invalid Bridge binding field")
	}
	if b.Generation == 0 {
		return nil, newBridgeError(ErrCodeInvalidBinding, "Bridge binding generation must be positive")
	}
	return b, nil
}

func (n *Node) matchBinding(b *verifiedBinding) error {
	n.mu.Lock()
	enroll := n.enrollment
	n.mu.Unlock()
	if enroll == nil {
		return newBridgeError(ErrCodeInvalidBinding, "node has no enrollment")
	}
	if !constantTimeEqual([]byte(enroll.DeviceID), []byte(b.DeviceID)) ||
		!constantTimeEqual([]byte(enroll.AppKeyFP), []byte(b.BridgeIdentity)) ||
		!constantTimeEqual([]byte(enroll.TicketDigest), []byte(b.TicketDigest)) ||
		!constantTimeEqual([]byte(strconv.FormatUint(enroll.Generation, 10)), []byte(strconv.FormatUint(b.Generation, 10))) ||
		!constantTimeEqual([]byte(strconv.FormatUint(enroll.PolicyRevision, 10)), []byte(strconv.FormatUint(b.PolicyRevision, 10))) ||
		!constantTimeEqual([]byte(enroll.PolicyDigest), []byte(b.PolicyDigest)) {
		return newBridgeError(ErrCodeInvalidBinding, "Bridge binding mismatch")
	}
	return nil
}

func constantTimeEqual(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	return subtle.ConstantTimeCompare(a, b) == 1
}
