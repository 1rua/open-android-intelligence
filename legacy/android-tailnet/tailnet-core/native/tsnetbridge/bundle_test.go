package tsnetbridge

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"testing"
)

const goldenBundleHex = "414c54534e455431010000000b61706f6c6c6f2d70726f640000002268747470733a2f2f636f6e74726f6c706c616e652e7461696c7363616c652e636f6d0000003274736b65792d617574682d4b56416f41336e4136434e54524c2d303132333435363738396162636465666768696a6b6c6d6e0000000a3130302e36342e302e3700000011666437613a313135633a613165303a3a370000001b61706f6c6c6f2d70726f642e7461696c2d6e65742e74732e6e65740000002b41414543417751464267634943516f4c4441304f4478415245684d554652595847426b61477877644868380000002b7061576c7061576c7061576c7061576c7061576c7061576c7061576c7061576c7061576c7061576c7061550000000a6465762d6e393378717a000000013100000001300000002b7738504477385044773850447738504477385044773850447738504477385044773850447738504477384d0000000a34303730393038383030"

var (
	appKeyFP     = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
	ticketDigest = "paWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaU"
	policyDigest = "w8PDw8PDw8PDw8PDw8PDw8PDw8PDw8PDw8PDw8PDw8M"
)

const (
	fldHostname = iota
	fldControlURL
	fldAuthKey
	fldIPv4
	fldIPv6
	fldMagicDNS
	fldAppKeyFP
	fldTicketDigest
	fldDeviceID
	fldGeneration
	fldPolicyRevision
	fldPolicyDigest
	fldExpiry
	numFields
)

func testBundle() *EnrollmentBundle {
	return &EnrollmentBundle{
		Hostname:           "apollo-prod",
		ControlURL:         "https://controlplane.tailscale.com",
		AuthKey:            []byte("tskey-auth-KVAoA3nA6CNTRL-0123456789abcdefghijklmn"),
		PinnedIPv4:         "100.64.0.7",
		PinnedIPv6:         "fd7a:115c:a1e0::7",
		MagicDNS:           "apollo-prod.tail-net.ts.net",
		AppKeyFP:           appKeyFP,
		TicketDigest:       ticketDigest,
		DeviceID:           "dev-n93xqz",
		Generation:         1,
		PolicyRevision:     0,
		PolicyDigest:       policyDigest,
		ExpiryEpochSeconds: 4070908800,
	}
}

func goldenFields() [][]byte {
	return [][]byte{
		[]byte("apollo-prod"),
		[]byte("https://controlplane.tailscale.com"),
		[]byte("tskey-auth-KVAoA3nA6CNTRL-0123456789abcdefghijklmn"),
		[]byte("100.64.0.7"),
		[]byte("fd7a:115c:a1e0::7"),
		[]byte("apollo-prod.tail-net.ts.net"),
		[]byte(appKeyFP),
		[]byte(ticketDigest),
		[]byte("dev-n93xqz"),
		[]byte("1"),
		[]byte("0"),
		[]byte(policyDigest),
		[]byte("4070908800"),
	}
}

func buildBlobVer(ver byte, fields ...[]byte) []byte {
	out := append([]byte("ALTSNET1"), ver)
	var hdr [4]byte
	for _, f := range fields {
		binary.BigEndian.PutUint32(hdr[:], uint32(len(f)))
		out = append(out, hdr[:]...)
		out = append(out, f...)
	}
	return out
}

func buildBlob(fields [][]byte) []byte {
	return buildBlobVer(1, fields...)
}

// fieldCopy returns the golden fields with index i replaced by repl.
func fieldCopy(i int, repl []byte) [][]byte {
	orig := goldenFields()
	cp := make([][]byte, len(orig))
	for j := range orig {
		cp[j] = bytes.Clone(orig[j])
	}
	cp[i] = repl
	return cp
}

func mustDecode(t *testing.T, blob []byte) *EnrollmentBundle {
	t.Helper()
	b, err := DecodeBundle(blob)
	if err != nil {
		t.Fatalf("DecodeBundle(%x) failed: %v", blob, err)
	}
	return b
}

func mustDecodeFail(t *testing.T, blob []byte) {
	t.Helper()
	if _, err := DecodeBundle(blob); err == nil {
		t.Fatalf("DecodeBundle(%x) unexpectedly succeeded", blob)
	}
}

func TestEncodeBundleExactBytes(t *testing.T) {
	got, err := EncodeBundle(testBundle())
	if err != nil {
		t.Fatalf("EncodeBundle: %v", err)
	}
	if hex.EncodeToString(got) != goldenBundleHex {
		t.Fatalf("EncodeBundle bytes mismatch:\n got %s\nwant %s", hex.EncodeToString(got), goldenBundleHex)
	}
}

func TestDecodeBundleGoldenValues(t *testing.T) {
	blob, err := hex.DecodeString(goldenBundleHex)
	if err != nil {
		t.Fatalf("bad golden hex: %v", err)
	}
	b := mustDecode(t, blob)
	want := testBundle()
	if b.Hostname != want.Hostname || b.ControlURL != want.ControlURL ||
		b.MagicDNS != want.MagicDNS || b.DeviceID != want.DeviceID ||
		b.PinnedIPv4 != want.PinnedIPv4 || b.PinnedIPv6 != want.PinnedIPv6 ||
		b.AppKeyFP != want.AppKeyFP || b.TicketDigest != want.TicketDigest ||
		b.PolicyDigest != want.PolicyDigest || b.Generation != want.Generation ||
		b.PolicyRevision != want.PolicyRevision || b.ExpiryEpochSeconds != want.ExpiryEpochSeconds {
		t.Fatalf("decoded bundle mismatch: got %+v want %+v", b, want)
	}
	if !bytes.Equal(b.AuthKey, want.AuthKey) {
		t.Fatalf("auth key mismatch: got %q want %q", b.AuthKey, want.AuthKey)
	}
	if b.WarmStart {
		t.Fatalf("cold bundle decoded as WarmStart")
	}
}

func TestEncodeDecodeRoundTrip(t *testing.T) {
	blob, err := EncodeBundle(testBundle())
	if err != nil {
		t.Fatalf("EncodeBundle: %v", err)
	}
	b := mustDecode(t, blob)
	again, err := EncodeBundle(b)
	if err != nil {
		t.Fatalf("re-encode: %v", err)
	}
	if !bytes.Equal(again, blob) {
		t.Fatalf("round trip changed bytes:\n got %x\nwant %x", again, blob)
	}
}

func TestDecodeWarmStartEmptyAuthKey(t *testing.T) {
	blob := buildBlob(fieldCopy(fldAuthKey, []byte{}))
	b := mustDecode(t, blob)
	if !b.WarmStart {
		t.Fatalf("empty auth key must decode as WarmStart")
	}
	if len(b.AuthKey) != 0 {
		t.Fatalf("auth key not empty: %q", b.AuthKey)
	}
	again, err := EncodeBundle(b)
	if err != nil {
		t.Fatalf("re-encode warm-start bundle: %v", err)
	}
	if !bytes.Equal(again, blob) {
		t.Fatalf("warm-start round trip changed bytes")
	}
}

func TestDecodeZeroLengthIPAllowedWithOtherFamily(t *testing.T) {
	fields := goldenFields()
	fields[fldIPv4] = []byte{}
	blob := buildBlob(fields)
	b := mustDecode(t, blob)
	if b.PinnedIPv4 != "" || b.PinnedIPv6 != "fd7a:115c:a1e0::7" {
		t.Fatalf("unexpected addresses: %q %q", b.PinnedIPv4, b.PinnedIPv6)
	}
}

func TestDecodeRejects(t *testing.T) {
	blob, err := hex.DecodeString(goldenBundleHex)
	if err != nil {
		t.Fatalf("bad golden hex: %v", err)
	}

	for _, n := range []int{0, 8, 9, 20, len(blob) / 2, len(blob) - 1} {
		mustDecodeFail(t, blob[:n])
	}

	tests := map[string][]byte{
		"trailing bytes":               append(bytes.Clone(blob), 'X'),
		"unknown version":              buildBlobVer(2, goldenFields()...),
		"overlong field length":        append([]byte("ALTSNET1"), 1, 0xff, 0xff, 0xff, 0xff, 'x'),
		"missing expiry field":         buildBlob(goldenFields()[:numFields-1]),
		"invalid utf8 in hostname":     buildBlob(fieldCopy(fldHostname, []byte{0xff, 0xfe, 0x00})),
		"invalid utf8 in magicdns":     buildBlob(fieldCopy(fldMagicDNS, []byte{'a', 0xff})),
		"non-https control url":        buildBlob(fieldCopy(fldControlURL, []byte("http://controlplane.tailscale.com"))),
		"control url userinfo":         buildBlob(fieldCopy(fldControlURL, []byte("https://user:pass@controlplane.tailscale.com"))),
		"control url query":            buildBlob(fieldCopy(fldControlURL, []byte("https://controlplane.tailscale.com?x=1"))),
		"control url fragment":         buildBlob(fieldCopy(fldControlURL, []byte("https://controlplane.tailscale.com#frag"))),
		"control url with port":        buildBlob(fieldCopy(fldControlURL, []byte("https://controlplane.tailscale.com:8443"))),
		"control url with path":        buildBlob(fieldCopy(fldControlURL, []byte("https://controlplane.tailscale.com/v1"))),
		"proxy-like endpoint":          buildBlob(fieldCopy(fldControlURL, []byte("http://proxy.example.com:8080"))),
		"expired attestation":          buildBlob(fieldCopy(fldExpiry, []byte("1"))),
		"hostname with space":          buildBlob(fieldCopy(fldHostname, []byte("apollo prod"))),
		"route-like hostname ip":       buildBlob(fieldCopy(fldHostname, []byte("100.64.0.7"))),
		"sni with space":               buildBlob(fieldCopy(fldMagicDNS, []byte("node name.ts.net"))),
		"sni route-like ip":            buildBlob(fieldCopy(fldMagicDNS, []byte("127.0.0.1"))),
		"non-tailnet ipv4":             buildBlob(fieldCopy(fldIPv4, []byte("8.8.8.8"))),
		"non-tailscale ula ipv6":       buildBlob(fieldCopy(fldIPv6, []byte("fd00::1"))),
		"non-canonical ipv6":           buildBlob(fieldCopy(fldIPv6, []byte("fd7a:115c:a1e0:0:0:0:0:7"))),
		"non-canonical ipv4 leading 0": buildBlob(fieldCopy(fldIPv4, []byte("100.064.0.7"))),
		"malformed digest chars":       buildBlob(fieldCopy(fldAppKeyFP, []byte("!!!!not-a-digest!!!!"))),
		"digest too short":             buildBlob(fieldCopy(fldAppKeyFP, []byte(base64.RawURLEncoding.EncodeToString(make([]byte, 31))))),
		"digest with padding":          buildBlob(fieldCopy(fldTicketDigest, []byte(ticketDigest+"="))),
		"zero generation":              buildBlob(fieldCopy(fldGeneration, []byte("0"))),
		"leading zero generation":      buildBlob(fieldCopy(fldGeneration, []byte("01"))),
		"negative revision":            buildBlob(fieldCopy(fldPolicyRevision, []byte("-1"))),
		"overflow revision":            buildBlob(fieldCopy(fldPolicyRevision, []byte("18446744073709551616"))),
		"empty hostname":               buildBlob(fieldCopy(fldHostname, []byte{})),
		"empty device id":              buildBlob(fieldCopy(fldDeviceID, []byte{})),
	}
	tests["both addresses empty"] = func() []byte { f := goldenFields(); f[fldIPv4] = []byte{}; f[fldIPv6] = []byte{}; return buildBlob(f) }()

	for name, mutated := range tests {
		t.Run(name, func(t *testing.T) {
			mustDecodeFail(t, mutated)
		})
	}
}

func TestEncodeRejects(t *testing.T) {
	tests := map[string]func(*EnrollmentBundle){
		"missing auth key on cold start": func(b *EnrollmentBundle) {
			b.AuthKey = nil
			b.WarmStart = false
		},
		"zero generation": func(b *EnrollmentBundle) {
			b.Generation = 0
		},
		"both addresses empty": func(b *EnrollmentBundle) {
			b.PinnedIPv4 = ""
			b.PinnedIPv6 = ""
		},
		"empty hostname": func(b *EnrollmentBundle) {
			b.Hostname = ""
		},
		"non-https control url": func(b *EnrollmentBundle) {
			b.ControlURL = "http://example.com"
		},
		"non-tailnet ipv4": func(b *EnrollmentBundle) {
			b.PinnedIPv4 = "8.8.8.8"
		},
		"non-tailscale ipv6": func(b *EnrollmentBundle) {
			b.PinnedIPv6 = "fd00::1"
		},
		"malformed digest": func(b *EnrollmentBundle) {
			b.AppKeyFP = "!!!"
		},
	}

	for name, mutate := range tests {
		t.Run(name, func(t *testing.T) {
			b := testBundle()
			mutate(b)
			if _, err := EncodeBundle(b); err == nil {
				t.Fatalf("EncodeBundle unexpectedly accepted %+v", b)
			}
		})
	}

	t.Run("expired attestation is encodable but not decodable", func(t *testing.T) {
		b := testBundle()
		b.ExpiryEpochSeconds = 1
		blob, err := EncodeBundle(b)
		if err != nil {
			t.Fatalf("encode must be a pure codec: %v", err)
		}
		mustDecodeFail(t, blob)
	})

	t.Run("warm start with empty auth key", func(t *testing.T) {
		b := testBundle()
		b.AuthKey = []byte{}
		b.WarmStart = true
		blob, err := EncodeBundle(b)
		if err != nil {
			t.Fatalf("EncodeBundle warm start: %v", err)
		}
		again := mustDecode(t, blob)
		if !again.WarmStart || len(again.AuthKey) != 0 {
			t.Fatalf("warm start round trip mangled: %+v", again)
		}
	})
}
