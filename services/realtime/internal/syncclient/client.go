// Package syncclient is the executable reference algorithm for integration
// clients. Product clients will implement this protocol on their own platforms.
package syncclient

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/gorilla/websocket"
	"sync"
	"time"
)

type Snapshot struct {
	Version int64           `json:"version"`
	Data    json.RawMessage `json:"data"`
}
type Key struct{ Kind, ID string }
type Event struct {
	ID        string `json:"eventId"`
	Kind      string `json:"aggregateType"`
	Aggregate string `json:"aggregateId"`
	Version   int64  `json:"aggregateVersion"`
}
type Frame struct {
	Type      string          `json:"type"`
	Event     json.RawMessage `json:"event"`
	Aggregate string          `json:"aggregateId"`
}

// Reader always runs independently of HTTP snapshot fetches. Inbound buffering
// is bounded; overflow terminates this connection and requires a fresh snapshot.
type Client struct {
	Conn        *websocket.Conn
	Fetch       func(context.Context, Key) (Snapshot, error)
	mu          sync.Mutex
	State       map[Key]Snapshot
	Keys        []Key
	Foreground  chan struct{}
	Poll        <-chan time.Time
	OnHeartbeat func()
	write       sync.Mutex
}

func (c *Client) send(v any) error {
	c.write.Lock()
	defer c.write.Unlock()
	_ = c.Conn.SetWriteDeadline(time.Now().Add(2 * time.Second))
	return c.Conn.WriteJSON(v)
}
func (c *Client) Subscribe(match string) error {
	return c.send(map[string]any{"type": "subscribe", "matchId": match})
}
func (c *Client) Reconcile(ctx context.Context) error {
	for _, key := range c.keys() {
		v, err := c.Fetch(ctx, key)
		if err != nil {
			return err
		}
		c.mu.Lock()
		if c.State == nil {
			c.State = map[Key]Snapshot{}
		}
		old, ok := c.State[key]
		if !ok || v.Version >= old.Version {
			c.State[key] = v
		}
		c.mu.Unlock()
	}
	return nil
}
func (c *Client) Current(key Key) Snapshot { c.mu.Lock(); defer c.mu.Unlock(); return c.State[key] }
func (c *Client) Apply(ctx context.Context, event json.RawMessage) error {
	var e Event
	if err := json.Unmarshal(event, &e); err != nil {
		return err
	}
	key := Key{e.Kind, e.Aggregate}
	c.mu.Lock()
	old, known := c.State[key]
	c.mu.Unlock()
	// Fetch authority on new versions or gaps rather than assuming every event is
	// a complete snapshot. MATCH and GAME have independent version sequences.
	if !known || e.Version > old.Version {
		if err := c.Reconcile(ctx); err != nil {
			return err
		}
	}
	return c.send(map[string]any{"type": "ack", "eventId": e.ID, "aggregateId": e.Aggregate, "aggregateVersion": e.Version})
}
func (c *Client) Run(ctx context.Context) error {
	c.write.Lock()
	conn := c.Conn
	c.write.Unlock()
	frames := make(chan Frame, 64)
	failures := make(chan error, 1)
	done := make(chan struct{})
	defer close(done)
	go func() {
		for {
			var f Frame
			if err := conn.ReadJSON(&f); err != nil {
				select {
				case failures <- err:
				case <-done:
				}
				return
			}
			select {
			case frames <- f:
			case <-done:
				return
			default:
				select {
				case failures <- errors.New("resync required: inbound overflow"):
				case <-done:
				}
				return
			}
		}
	}()
	heartbeat := time.NewTicker(2 * time.Second)
	defer heartbeat.Stop()
	poll := time.NewTicker(5 * time.Second)
	defer poll.Stop()
	ticks := c.Poll
	if ticks == nil {
		ticks = poll.C
	}
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case err := <-failures:
			return err
		case <-heartbeat.C:
			if err := c.send(map[string]any{"type": "heartbeat", "requestId": randomID()}); err != nil {
				return err
			}
		case <-ticks:
			if err := c.Reconcile(ctx); err != nil {
				continue // Keep the last snapshot and retry after transient authority failure.
			}
		case <-c.Foreground:
			if err := c.Reconcile(ctx); err != nil {
				continue // Keep the last snapshot and retry after transient authority failure.
			}
		case f := <-frames:
			switch f.Type {
			case "heartbeat_ack":
				if c.OnHeartbeat != nil {
					c.OnHeartbeat()
				}
			case "event":
				if err := c.Apply(ctx, f.Event); err != nil {
					continue
				}
			case "resync_required":
				if err := c.Reconcile(ctx); err != nil {
					continue
				}
			}
		}
	}
}

func randomID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	b[6] = b[6]&15 | 64
	b[8] = b[8]&63 | 128
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:])
}

// Reconnect retains applied versions, authenticates using a fresh one-use ticket,
// and subscribes before reading any snapshot. Foreground signals trigger the
// same authority reconciliation as periodic polling.
func (c *Client) Reconnect(ctx context.Context, url, ticket string) error {
	conn, _, err := websocket.DefaultDialer.DialContext(ctx, url, nil)
	if err != nil {
		return err
	}
	defer func() { _ = conn.Close() }()
	c.write.Lock()
	c.Conn = conn
	c.write.Unlock()
	if err = c.send(map[string]any{"type": "auth", "ticket": ticket}); err != nil {
		return err
	}
	var response struct{ Type string }
	if err = conn.ReadJSON(&response); err != nil {
		return err
	}
	if response.Type != "auth_ok" {
		return errors.New("authentication rejected")
	}
	subscribed := false
	for _, key := range c.keys() {
		if key.Kind == "MATCH" {
			if err = c.Subscribe(key.ID); err != nil {
				return err
			}
			subscribed = true
		}
	}
	if !subscribed {
		if err = c.Reconcile(ctx); err != nil {
			return err
		}
	}
	return c.Run(ctx)
}

func (c *Client) SetKeys(keys []Key) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.Keys = append([]Key(nil), keys...)
}
func (c *Client) keys() []Key { c.mu.Lock(); defer c.mu.Unlock(); return append([]Key(nil), c.Keys...) }

// Serve obtains a new ticket after transport loss without logging in again.
func (c *Client) Serve(ctx context.Context, url string, ticket func(context.Context) (string, error)) error {
	for ctx.Err() == nil {
		value, err := ticket(ctx)
		if err == nil {
			_ = c.Reconnect(ctx, url, value)
		}
		timer := time.NewTimer(2 * time.Second)
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
	}
	return ctx.Err()
}
