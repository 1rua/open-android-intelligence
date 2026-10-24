package tsnetbridge

import (
	"context"
	"errors"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"tailscale.com/tsnet"
)

const maxFrameBytes = 262_144

// Channel is the only wire surface exposed to Kotlin. Each binary WSS message
// carries exactly one canonical Open Android Intelligence envelope; no application payload is
// interpreted, re-signed, retried, or reordered here.
type Channel struct {
	mu     sync.Mutex
	conn   *websocket.Conn
	closed bool
}

func (c *Channel) Send(canonicalWire []byte) error {
	if len(canonicalWire) == 0 || len(canonicalWire) > maxFrameBytes {
		return newBridgeError(ErrCodeFrameInvalid, "control frame size is invalid")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return newBridgeError(ErrCodeChannelClosed, "Bridge channel is closed")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := c.conn.Write(ctx, websocket.MessageBinary, canonicalWire); err != nil {
		return newBridgeError(ErrCodeFrameInvalid, "control frame send failed")
	}
	return nil
}

func (c *Channel) Receive() ([]byte, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return nil, newBridgeError(ErrCodeChannelClosed, "Bridge channel is closed")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	typ, data, err := c.conn.Read(ctx)
	if err != nil {
		return nil, newBridgeError(ErrCodeFrameInvalid, "control frame receive failed")
	}
	if typ != websocket.MessageBinary || len(data) == 0 || len(data) > maxFrameBytes {
		return nil, newBridgeError(ErrCodeFrameInvalid, "invalid control frame")
	}
	return data, nil
}

func (c *Channel) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return nil
	}
	c.closed = true
	if c.conn != nil {
		err := c.conn.CloseNow()
		c.conn = nil
		if err != nil {
			return newBridgeError(ErrCodeChannelClosed, "Bridge channel close failed")
		}
	}
	return nil
}

func (n *Node) openChannel(bindingBytes []byte) (*Channel, error) {
	binding, err := decodeVerifiedBinding(bindingBytes)
	if err != nil {
		return nil, err
	}
	if err := n.matchBinding(binding); err != nil {
		return nil, err
	}
	n.mu.Lock()
	if n.closed {
		n.mu.Unlock()
		return nil, newBridgeError(ErrCodeChannelClosed, "node is closed")
	}
	if n.channel != nil {
		n.mu.Unlock()
		return nil, newBridgeError(ErrCodeInvalidBinding, "node already owns a Bridge channel")
	}
	server := n.server
	enroll := n.enrollment
	n.mu.Unlock()

	conn, err := n.dialWSS(server, enroll)
	if err != nil {
		return nil, err
	}
	ch := &Channel{conn: conn}
	n.mu.Lock()
	if n.closed || n.channel != nil {
		n.mu.Unlock()
		_ = ch.Close()
		return nil, newBridgeError(ErrCodeInvalidBinding, "node channel changed while opening")
	}
	n.channel = ch
	n.channelPath = "OFFLINE"
	n.mu.Unlock()
	return ch, nil
}

func (n *Node) dialWSS(server *tsnet.Server, enroll *enrollment) (*websocket.Conn, error) {
	if server == nil || enroll == nil {
		return nil, newBridgeError(ErrCodeInvalidBinding, "node is not started")
	}
	authority := net.JoinHostPort(enroll.MagicDNS, "443")
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			if addr != authority {
				return nil, errors.New("refusing non-pinned Bridge authority")
			}
			conn, err := nodeDial(ctx, server, addr)
			if err != nil {
				return nil, err
			}
			if !remoteIsPinned(conn.RemoteAddr(), enroll) {
				_ = conn.Close()
				return nil, errors.New("refusing unpinned Bridge peer address")
			}
			return conn, nil
		},
		TLSClientConfig: pinnedTLSConfig(enroll.MagicDNS),
	}
	client := &http.Client{
		Transport: transport,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return errors.New("redirect rejected")
		},
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(ctx, "wss://"+enroll.MagicDNS+"/v1/control", &websocket.DialOptions{HTTPClient: client})
	if err != nil {
		return nil, newBridgeError(ErrCodeControlUnreachable, "Bridge control channel unreachable")
	}
	conn.SetReadLimit(maxFrameBytes)
	return conn, nil
}

func remoteIsPinned(remote net.Addr, enroll *enrollment) bool {
	host := ""
	if remote != nil {
		if hostport, ok := remote.(*net.TCPAddr); ok {
			host = hostport.IP.String()
		} else {
			host = strings.Split(remote.String(), ":")[0]
		}
	}
	if host == "" {
		return false
	}
	return (enroll.PinnedIPv4 != "" && host == enroll.PinnedIPv4) ||
		(enroll.PinnedIPv6 != "" && host == enroll.PinnedIPv6)
}
