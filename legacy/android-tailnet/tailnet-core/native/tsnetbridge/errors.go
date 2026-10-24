package tsnetbridge

const (
	ErrCodeInvalidBundle      = "INVALID_BUNDLE"
	ErrCodeStateRestoreFailed = "STATE_RESTORE_FAILED"
	ErrCodeStatePersistFailed = "STATE_PERSIST_FAILED"
	ErrCodeApprovalRequired   = "APPROVAL_REQUIRED"
	ErrCodeControlUnreachable = "CONTROL_UNREACHABLE"
	ErrCodeNetworkBlocked     = "NETWORK_BLOCKED"
	ErrCodeInvalidBinding     = "INVALID_BINDING"
	ErrCodeStaleGeneration    = "STALE_GENERATION"
	ErrCodeFrameInvalid       = "FRAME_INVALID"
	ErrCodeChannelClosed      = "CHANNEL_CLOSED"
)

type BridgeError struct {
	Code    string
	Message string
}

func (e *BridgeError) Error() string {
	return e.Code + ": " + e.Message
}

func newBridgeError(code, message string) *BridgeError {
	return &BridgeError{Code: code, Message: message}
}
