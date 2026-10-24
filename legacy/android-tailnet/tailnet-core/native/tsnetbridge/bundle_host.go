//go:build !android

package tsnetbridge

// EnrollmentBundle is the host/provisioning view of NativeEnrollmentBundleV1.
// It is intentionally absent from the Android AAR: on device, Start consumes
// the opaque bootstrap bytes and never exposes the enrollment record or auth
// key to Kotlin.
type EnrollmentBundle struct {
	Hostname           string
	ControlURL         string
	AuthKey            []byte
	PinnedIPv4         string
	PinnedIPv6         string
	MagicDNS           string
	AppKeyFP           string
	TicketDigest       string
	DeviceID           string
	Generation         uint64
	PolicyRevision     uint64
	PolicyDigest       string
	ExpiryEpochSeconds uint64
	WarmStart          bool
}

// EncodeBundle serializes the host enrollment record for the provisioning CLI
// and unit tests only.
func EncodeBundle(b *EnrollmentBundle) ([]byte, error) {
	if b == nil {
		return nil, newBridgeError(ErrCodeInvalidBundle, "enrollment bundle is empty")
	}
	internal := &enrollmentBundle{
		hostname:           b.Hostname,
		controlURL:         b.ControlURL,
		authKey:            b.AuthKey,
		pinnedIPv4:         b.PinnedIPv4,
		pinnedIPv6:         b.PinnedIPv6,
		magicDNS:           b.MagicDNS,
		appKeyFP:           b.AppKeyFP,
		ticketDigest:       b.TicketDigest,
		deviceID:           b.DeviceID,
		generation:         b.Generation,
		policyRevision:     b.PolicyRevision,
		policyDigest:       b.PolicyDigest,
		expiryEpochSeconds: b.ExpiryEpochSeconds,
		warmStart:          b.WarmStart,
	}
	return encodeBundle(internal)
}

// DecodeBundle parses a host enrollment record for the provisioning CLI and
// unit tests only.
func DecodeBundle(blob []byte) (*EnrollmentBundle, error) {
	internal, err := decodeBundle(blob)
	if err != nil {
		return nil, err
	}
	return &EnrollmentBundle{
		Hostname:           internal.hostname,
		ControlURL:         internal.controlURL,
		AuthKey:            internal.authKey,
		PinnedIPv4:         internal.pinnedIPv4,
		PinnedIPv6:         internal.pinnedIPv6,
		MagicDNS:           internal.magicDNS,
		AppKeyFP:           internal.appKeyFP,
		TicketDigest:       internal.ticketDigest,
		DeviceID:           internal.deviceID,
		Generation:         internal.generation,
		PolicyRevision:     internal.policyRevision,
		PolicyDigest:       internal.policyDigest,
		ExpiryEpochSeconds: internal.expiryEpochSeconds,
		WarmStart:          internal.warmStart,
	}, nil
}
