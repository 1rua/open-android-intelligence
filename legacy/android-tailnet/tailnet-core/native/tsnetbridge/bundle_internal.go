package tsnetbridge

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"net"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// NativeEnrollmentBundleV1 is a closed, immutable enrollment record. It is a
// binary container, never a generic endpoint configuration: every field is
// pinned at pairing time and the wrapper supplies only the fixed Bridge WSS
// connectivity derived from it.
const (
	enrollmentMagic   = "ALTSNET1"
	enrollmentVersion = 1
	enrollmentFields  = 13
	maxBundleBytes    = 1 << 20
)

// EnrollmentBundle is the parsed form of NativeEnrollmentBundleV1. AuthKey is
// the only mutable secret and is wiped by callers once the node starts; it is
// never logged or persisted.
type enrollmentBundle struct {
	hostname           string
	controlURL         string
	authKey            []byte
	pinnedIPv4         string
	pinnedIPv6         string
	magicDNS           string
	appKeyFP           string
	ticketDigest       string
	deviceID           string
	generation         uint64
	policyRevision     uint64
	policyDigest       string
	expiryEpochSeconds uint64
	warmStart          bool
}

func encodeBundle(b *enrollmentBundle) ([]byte, error) {
	if b == nil {
		return nil, newBridgeError(ErrCodeInvalidBundle, "enrollment bundle is empty")
	}
	if err := validateBundle(b, b.warmStart); err != nil {
		return nil, err
	}
	fields := [][]byte{
		[]byte(b.hostname),
		[]byte(b.controlURL),
		b.authKey,
		[]byte(b.pinnedIPv4),
		[]byte(b.pinnedIPv6),
		[]byte(b.magicDNS),
		[]byte(b.appKeyFP),
		[]byte(b.ticketDigest),
		[]byte(b.deviceID),
		[]byte(strconv.FormatUint(b.generation, 10)),
		[]byte(strconv.FormatUint(b.policyRevision, 10)),
		[]byte(b.policyDigest),
		[]byte(strconv.FormatUint(b.expiryEpochSeconds, 10)),
	}
	out := make([]byte, 0, 512)
	out = append(out, enrollmentMagic...)
	out = append(out, enrollmentVersion)
	var hdr [4]byte
	for _, f := range fields {
		if len(f) > 1<<20 {
			return nil, newBridgeError(ErrCodeInvalidBundle, "enrollment field too large")
		}
		binary.BigEndian.PutUint32(hdr[:], uint32(len(f)))
		out = append(out, hdr[:]...)
		out = append(out, f...)
	}
	if len(out) > maxBundleBytes {
		return nil, newBridgeError(ErrCodeInvalidBundle, "enrollment bundle too large")
	}
	return out, nil
}

func decodeBundle(blob []byte) (*enrollmentBundle, error) {
	if len(blob) == 0 {
		return nil, newBridgeError(ErrCodeInvalidBundle, "enrollment bundle is empty")
	}
	if len(blob) > maxBundleBytes {
		return nil, newBridgeError(ErrCodeInvalidBundle, "enrollment bundle too large")
	}
	if len(blob) < len(enrollmentMagic)+1 || !bytes.Equal(blob[:len(enrollmentMagic)], []byte(enrollmentMagic)) {
		return nil, newBridgeError(ErrCodeInvalidBundle, "invalid enrollment magic")
	}
	blob = blob[len(enrollmentMagic):]
	if blob[0] != enrollmentVersion {
		return nil, newBridgeError(ErrCodeInvalidBundle, "unsupported enrollment version")
	}
	blob = blob[1:]

	fields := make([][]byte, 0, enrollmentFields)
	for i := 0; i < enrollmentFields; i++ {
		if len(blob) < 4 {
			return nil, newBridgeError(ErrCodeInvalidBundle, "truncated enrollment field header")
		}
		n := binary.BigEndian.Uint32(blob[:4])
		blob = blob[4:]
		if uint64(n) > uint64(len(blob)) {
			return nil, newBridgeError(ErrCodeInvalidBundle, "enrollment field length overflows blob")
		}
		fields = append(fields, bytes.Clone(blob[:n]))
		blob = blob[n:]
	}
	if len(blob) != 0 {
		return nil, newBridgeError(ErrCodeInvalidBundle, "trailing enrollment bytes")
	}

	b := &enrollmentBundle{
		hostname:     string(fields[0]),
		controlURL:   string(fields[1]),
		authKey:      fields[2],
		pinnedIPv4:   string(fields[3]),
		pinnedIPv6:   string(fields[4]),
		magicDNS:     string(fields[5]),
		appKeyFP:     string(fields[6]),
		ticketDigest: string(fields[7]),
		deviceID:     string(fields[8]),
		policyDigest: string(fields[11]),
		warmStart:    len(fields[2]) == 0,
	}
	if err := parseUintField(fields[9], &b.generation); err != nil {
		return nil, err
	}
	if err := parseUintField(fields[10], &b.policyRevision); err != nil {
		return nil, err
	}
	if err := parseUintField(fields[12], &b.expiryEpochSeconds); err != nil {
		return nil, err
	}
	if err := validateBundle(b, b.warmStart); err != nil {
		return nil, err
	}
	if b.expiryEpochSeconds <= uint64(time.Now().Unix()) {
		return nil, newBridgeError(ErrCodeInvalidBundle, "attestation is expired")
	}
	return b, nil
}

func parseUintField(raw []byte, dst *uint64) error {
	if len(raw) == 0 {
		return newBridgeError(ErrCodeInvalidBundle, "empty unsigned field")
	}
	if raw[0] == '-' || raw[0] == '+' {
		return newBridgeError(ErrCodeInvalidBundle, "invalid unsigned field")
	}
	if len(raw) > 1 && raw[0] == '0' {
		return newBridgeError(ErrCodeInvalidBundle, "non-canonical unsigned field")
	}
	v, err := strconv.ParseUint(string(raw), 10, 64)
	if err != nil {
		return newBridgeError(ErrCodeInvalidBundle, "non-canonical unsigned field")
	}
	*dst = v
	return nil
}

func validateBundle(b *enrollmentBundle, warm bool) error {
	if !utf8.ValidString(b.hostname) || !validHost(b.hostname, false) {
		return newBridgeError(ErrCodeInvalidBundle, "invalid hostname")
	}
	if !utf8.ValidString(b.controlURL) || !validControlURL(b.controlURL) {
		return newBridgeError(ErrCodeInvalidBundle, "invalid control URL")
	}
	if len(b.authKey) == 0 && !warm {
		return newBridgeError(ErrCodeInvalidBundle, "cold start requires an auth key")
	}
	if len(b.authKey) != 0 && warm {
		return newBridgeError(ErrCodeInvalidBundle, "warm start must not carry an auth key")
	}
	if !utf8.ValidString(b.magicDNS) || !validHost(b.magicDNS, true) {
		return newBridgeError(ErrCodeInvalidBundle, "invalid MagicDNS name")
	}
	if (b.pinnedIPv4 == "" && b.pinnedIPv6 == "") || !validPinnedAddr(b.pinnedIPv4, b.pinnedIPv6) {
		return newBridgeError(ErrCodeInvalidBundle, "invalid pinned Bridge address")
	}
	if !validDigest(b.appKeyFP) || !validDigest(b.ticketDigest) || !validDigest(b.policyDigest) {
		return newBridgeError(ErrCodeInvalidBundle, "invalid digest field")
	}
	if !utf8.ValidString(b.deviceID) || b.deviceID == "" || strings.ContainsAny(b.deviceID, " \t\r\n/\\") {
		return newBridgeError(ErrCodeInvalidBundle, "invalid device id")
	}
	if b.generation == 0 {
		return newBridgeError(ErrCodeInvalidBundle, "pairing generation must be positive")
	}
	if b.expiryEpochSeconds == 0 {
		return newBridgeError(ErrCodeInvalidBundle, "attestation expiry must be positive")
	}
	return nil
}

func validControlURL(value string) bool {
	u, err := url.Parse(value)
	if err != nil || u == nil {
		return false
	}
	if u.Scheme != "https" || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return false
	}
	if u.Path != "" || u.Opaque != "" || u.Port() != "" {
		return false
	}
	return true
}

func validHost(value string, dns bool) bool {
	if value == "" || len(value) > 253 || net.ParseIP(value) != nil {
		return false
	}
	if dns && !strings.Contains(value, ".") {
		return false
	}
	for _, label := range strings.Split(value, ".") {
		if label == "" || len(label) > 63 {
			return false
		}
		if label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, r := range label {
			switch {
			case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '-':
			default:
				return false
			}
		}
	}
	return true
}

func validPinnedAddr(v4, v6 string) bool {
	if v4 != "" {
		addr, err := netip.ParseAddr(v4)
		if err != nil || !addr.Is4() || addr.String() != v4 {
			return false
		}
		if !netip.MustParsePrefix("100.64.0.0/10").Contains(addr) {
			return false
		}
	}
	if v6 != "" {
		addr, err := netip.ParseAddr(v6)
		if err != nil || !addr.Is6() || addr.String() != v6 {
			return false
		}
		if !netip.MustParsePrefix("fd7a:115c:a1e0::/48").Contains(addr) {
			return false
		}
	}
	return true
}

func validDigest(value string) bool {
	if value == "" {
		return false
	}
	raw, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil || len(raw) != 32 {
		return false
	}
	return base64.RawURLEncoding.EncodeToString(raw) == value
}

func wipe(b []byte) {
	for i := range b {
		b[i] = 0
	}
}
