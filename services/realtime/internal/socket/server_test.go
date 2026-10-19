package socket

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/gorilla/websocket"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type fakeAuthority struct {
	mu                   sync.Mutex
	used                 bool
	user, session, match string
	closed               chan int64
	participating        bool
}

func (f *fakeAuthority) Consume(_ context.Context, t string) (Identity, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if t != "valid" || f.used {
		return Identity{}, errors.New("invalid ticket")
	}
	f.used = true
	return Identity{true, f.user, f.session}, nil
}
func (f *fakeAuthority) Touch(context.Context, Identity) error { return nil }
func (f *fakeAuthority) Match(_ context.Context, user, match string) (json.RawMessage, error) {
	if match != f.match {
		return nil, errors.New("forbidden")
	}
	return json.RawMessage(`{}`), nil
}
func (f *fakeAuthority) Participation(context.Context, string) (string, error) {
	if f.participating {
		return f.match, nil
	}
	return "", nil
}
func (f *fakeAuthority) Observe(_ context.Context, _ string, _ string, g int64, connected bool) error {
	if f.closed != nil && !connected {
		f.closed <- g
	}
	return nil
}
func (f *fakeAuthority) Renew(context.Context, string, string, int64) error { return nil }

type fakeOwner struct {
	mu sync.Mutex
	v  Owner
}

func (f *fakeOwner) Claim(_ context.Context, id Identity) (Owner, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.v = Owner{"test", id.Session, f.v.Generation + 1}
	return f.v, nil
}
func (f *fakeOwner) Renew(_ context.Context, _ string, v Owner) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.v != v {
		return errors.New("fenced")
	}
	return nil
}
func (f *fakeOwner) Release(context.Context, string, Owner) error { return nil }
func (f *fakeOwner) Lookup(context.Context, string) (Owner, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.v, nil
}
func fixture(t *testing.T) (*Server, *fakeAuthority, string) {
	t.Helper()
	a := &fakeAuthority{user: ID(), session: ID(), match: ID()}
	s := &Server{Backend: a, Ownership: &fakeOwner{}, Origins: map[string]bool{"https://127.0.0.1": true}}
	ts := httptest.NewServer(s.Handler())
	t.Cleanup(ts.Close)
	return s, a, "ws" + strings.TrimPrefix(ts.URL, "http")
}
func dial(t *testing.T, url string) *websocket.Conn {
	t.Helper()
	c, _, err := websocket.DefaultDialer.Dial(url+"/wss", nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	return c
}
func auth(t *testing.T, c *websocket.Conn) {
	t.Helper()
	if err := c.WriteJSON(map[string]any{"type": "auth", "ticket": "valid"}); err != nil {
		t.Fatal(err)
	}
	var v map[string]any
	if err := c.ReadJSON(&v); err != nil || v["type"] != "auth_ok" {
		t.Fatalf("auth: %v %v", v, err)
	}
}
func TestAuthBoundary(t *testing.T) {
	for _, frame := range []map[string]any{{"type": "subscribe", "matchId": ID()}, {"type": "auth", "ticket": "expired"}, {"type": "auth", "ticket": "valid", "token": "secret"}} {
		t.Run(frame["type"].(string)+ID(), func(t *testing.T) {
			_, _, url := fixture(t)
			c := dial(t, url)
			_ = c.WriteJSON(frame)
			if _, _, err := c.ReadMessage(); err == nil {
				t.Fatal("unauthenticated socket received data")
			}
		})
	}
	_, _, url := fixture(t)
	c := dial(t, url)
	auth(t, c)
	second := dial(t, url)
	_ = second.WriteJSON(map[string]any{"type": "auth", "ticket": "valid"})
	if _, _, err := second.ReadMessage(); err == nil {
		t.Fatal("reused ticket accepted")
	}
}
func TestOriginAndRoute(t *testing.T) {
	_, _, url := fixture(t)
	for _, path := range []string{"/realtime", "/wss?ticket=secret"} {
		c, r, err := websocket.DefaultDialer.Dial(url+path, nil)
		if c != nil {
			_ = c.Close()
		}
		if err == nil || r == nil || r.StatusCode < 400 {
			t.Fatal("invalid path accepted")
		}
	}
	h := map[string][]string{"Origin": {"https://attacker.invalid"}}
	c, r, err := websocket.DefaultDialer.Dial(url+"/wss", h)
	if c != nil {
		_ = c.Close()
	}
	if err == nil || r.StatusCode != 403 {
		t.Fatal("origin accepted")
	}
}
func TestSubscriptionAndRecipientBoundary(t *testing.T) {
	s, a, url := fixture(t)
	c := dial(t, url)
	auth(t, c)
	_ = c.WriteJSON(map[string]any{"type": "subscribe", "matchId": a.match})
	var v map[string]any
	if err := c.ReadJSON(&v); err != nil || v["type"] != "resync_required" {
		t.Fatal(v, err)
	}
	event := Event{ID: ID(), Aggregate: a.match, Kind: "MATCH", Version: 1, Recipients: []string{a.user}}
	data, _ := json.Marshal(event)
	s.Route(Routed{User: a.user, Generation: 1, Data: data})
	if err := c.ReadJSON(&v); err != nil || v["type"] != "event" {
		t.Fatal(v, err)
	}
	_ = c.WriteJSON(map[string]any{"type": "ack", "eventId": event.ID, "aggregateId": event.Aggregate, "aggregateVersion": 1})
	_ = c.WriteJSON(map[string]any{"type": "subscribe", "matchId": ID()})
	if _, _, err := c.ReadMessage(); err == nil {
		t.Fatal("foreign match accepted")
	}
}
func TestNewSocketFencesOld(t *testing.T) {
	s, a, url := fixture(t)
	a.participating = true
	a.closed = make(chan int64, 4)
	old := dial(t, url)
	auth(t, old)
	a.mu.Lock()
	a.used = false
	a.mu.Unlock()
	current := dial(t, url)
	auth(t, current)
	if _, _, err := old.ReadMessage(); err == nil {
		t.Fatal("old socket alive")
	}
	select {
	case g := <-a.closed:
		t.Fatalf("replaced socket emitted disconnect %d", g)
	default:
	}
	s.Route(Routed{User: a.user, Generation: 1, Data: json.RawMessage(`{}`)})
	id := ID()
	_ = current.WriteJSON(map[string]any{"type": "heartbeat", "requestId": id})
	var v map[string]any
	if err := current.ReadJSON(&v); err != nil || v["requestId"] != id {
		t.Fatal("old generation affected new socket", err, v)
	}
}

func (f *fakeAuthority) Availability(context.Context, string, int64, bool) error { return nil }

func TestBoundedQueueStopsSlowSocketWithoutBlockingOtherRecipient(t *testing.T) {
	slow := &client{owner: Owner{Generation: 1}, events: make(chan Routed, 1), stop: make(chan struct{})}
	fast := &client{owner: Owner{Generation: 2}, events: make(chan Routed, 1), stop: make(chan struct{})}
	s := &Server{clients: map[string]*client{"slow": slow, "fast": fast}}
	s.Route(Routed{User: "slow", Generation: 1})
	s.Route(Routed{User: "slow", Generation: 1})
	select {
	case <-slow.stop:
	default:
		t.Fatal("overflow did not stop slow client")
	}
	s.Route(Routed{User: "fast", Generation: 2})
	select {
	case <-fast.events:
	default:
		t.Fatal("slow client blocked unrelated recipient")
	}
}

type finishedAuthority struct {
	fakeAuthority
	calls  atomic.Int64
	renews atomic.Int64
}

func (f *finishedAuthority) Participation(context.Context, string) (string, error) {
	if f.calls.Add(1) == 1 {
		return f.match, nil
	}
	return "", nil
}
func (f *finishedAuthority) Match(context.Context, string, string) (json.RawMessage, error) {
	return json.RawMessage(`{"data":{"state":"FINISHED"}}`), nil
}
func (f *finishedAuthority) Renew(context.Context, string, string, int64) error {
	f.renews.Add(1)
	return nil
}
func TestMenuStopsRenewingFinishedMatch(t *testing.T) {
	a := &finishedAuthority{fakeAuthority: fakeAuthority{user: ID(), session: ID(), match: ID()}}
	server := httptest.NewServer((&Server{Backend: a, Ownership: &fakeOwner{}}).Handler())
	defer server.Close()
	c := dial(t, "ws"+strings.TrimPrefix(server.URL, "http"))
	defer func() { _ = c.Close() }()
	auth(t, c)
	for i := 0; i < 2; i++ {
		id := ID()
		_ = c.WriteJSON(map[string]any{"type": "heartbeat", "requestId": id})
		var v map[string]any
		if err := c.ReadJSON(&v); err != nil || v["requestId"] != id {
			t.Fatal(err, v)
		}
	}
	if a.renews.Load() != 1 {
		t.Fatal("MENU heartbeat renewed finished game")
	}
}
