// enrollment-bundle reads a JSON enrollment description and an auth key as two
// length-delimited records from standard input, emits only the binary bundle
// on standard output, sends diagnostics to standard error using closed error
// codes, and wipes its mutable auth-key buffers before exit.
//
// It is a test/provisioning tool for the tsnet Android AAR, not an APK API.
//
// Record framing: each record starts with a 4-byte unsigned big-endian length
// followed by that many bytes. Record 1 is the JSON description; record 2 is
// the raw auth key. Exit status is 0 on success and 1 on failure.
package main

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"os"

	bridge "openandroidintelligence/tsnetbridge/tsnetbridge"
)

const maxRecordBytes = 1 << 20

type description struct {
	Hostname           string `json:"hostname"`
	ControlURL         string `json:"controlUrl"`
	PinnedIPv4         string `json:"pinnedIpv4"`
	PinnedIPv6         string `json:"pinnedIpv6"`
	MagicDNS           string `json:"magicDns"`
	AppKeyFP           string `json:"appKeyFingerprint"`
	TicketDigest       string `json:"ticketDigest"`
	DeviceID           string `json:"deviceId"`
	Generation         uint64 `json:"generation"`
	PolicyRevision     uint64 `json:"policyRevision"`
	PolicyDigest       string `json:"policyDigest"`
	ExpiryEpochSeconds uint64 `json:"expiryEpochSeconds"`
}

func readLengthDelimited(r io.Reader) ([]byte, error) {
	var hdr [4]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(hdr[:])
	if n > maxRecordBytes {
		return nil, fmt.Errorf("record exceeds %d bytes", maxRecordBytes)
	}
	buf := make([]byte, n)
	if _, err := io.ReadFull(r, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

func main() {
	os.Exit(run())
}

func run() int {
	descData, err := readLengthDelimited(os.Stdin)
	if err != nil {
		return fail("INVALID_BUNDLE", "cannot read description record: "+err.Error())
	}

	authKey, err := readLengthDelimited(os.Stdin)
	if err != nil {
		return fail("INVALID_BUNDLE", "cannot read auth-key record: "+err.Error())
	}
	defer wipe(authKey) // wipe the mutable auth-key buffer on every exit path.

	var desc description
	if err := json.Unmarshal(descData, &desc); err != nil {
		return fail("INVALID_BUNDLE", "malformed JSON description: "+err.Error())
	}

	b := &bridge.EnrollmentBundle{
		Hostname:           desc.Hostname,
		ControlURL:         desc.ControlURL,
		AuthKey:            authKey,
		PinnedIPv4:         desc.PinnedIPv4,
		PinnedIPv6:         desc.PinnedIPv6,
		MagicDNS:           desc.MagicDNS,
		AppKeyFP:           desc.AppKeyFP,
		TicketDigest:       desc.TicketDigest,
		DeviceID:           desc.DeviceID,
		Generation:         desc.Generation,
		PolicyRevision:     desc.PolicyRevision,
		PolicyDigest:       desc.PolicyDigest,
		ExpiryEpochSeconds: desc.ExpiryEpochSeconds,
	}

	blob, err := bridge.EncodeBundle(b)
	if err != nil {
		return fail("INVALID_BUNDLE", "cannot encode bundle: "+err.Error())
	}
	// The bundle carries a copy of the auth key; wipe it too.
	defer wipe(blob)

	if _, err := os.Stdout.Write(blob); err != nil {
		return fail("INVALID_BUNDLE", "cannot write bundle: "+err.Error())
	}
	return 0
}

func wipe(buf []byte) {
	for i := range buf {
		buf[i] = 0
	}
}

func fail(code, message string) int {
	fmt.Fprintf(os.Stderr, "%s: %s\n", code, message)
	return 1
}
