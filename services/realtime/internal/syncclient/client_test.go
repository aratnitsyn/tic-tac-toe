package syncclient

import (
	"context"
	"encoding/json"
	"github.com/gorilla/websocket"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestLostFinalEventAndForegroundReconcileWithoutRollback(t *testing.T) {
	key := Key{"MATCH", "00000000-0000-0000-0000-000000000001"}
	var mu sync.Mutex
	version := int64(1)
	fetched := make(chan int64, 16)
	poll := make(chan time.Time)
	foreground := make(chan struct{})
	ready := make(chan struct{})
	acks := make(chan string, 16)
	up := websocket.Upgrader{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := up.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer func() { _ = conn.Close() }()
		var f map[string]any
		if conn.ReadJSON(&f) != nil {
			return
		}
		_ = conn.WriteJSON(map[string]any{"type": "auth_ok"})
		if conn.ReadJSON(&f) != nil {
			return
		}
		_ = conn.WriteJSON(map[string]any{"type": "resync_required", "aggregateId": key.ID})
		close(ready)
		for {
			if conn.ReadJSON(&f) != nil {
				return
			}
			if f["type"] == "ack" {
				acks <- f["eventId"].(string)
			}
		}
	}))
	defer server.Close()
	c := &Client{Keys: []Key{key}, Poll: poll, Foreground: foreground, Fetch: func(context.Context, Key) (Snapshot, error) {
		mu.Lock()
		v := version
		mu.Unlock()
		fetched <- v
		return Snapshot{Version: v, Data: json.RawMessage(`{}`)}, nil
	}}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- c.Reconnect(ctx, "ws"+strings.TrimPrefix(server.URL, "http"), "fresh-ticket") }()
	<-ready
	wait := func(want int64) {
		t.Helper()
		select {
		case got := <-fetched:
			if got != want {
				t.Fatalf("snapshot %d want %d", got, want)
			}
		case <-time.After(2 * time.Second):
			t.Fatal("snapshot not fetched")
		}
	}
	wait(1)
	mu.Lock()
	version = 4
	mu.Unlock()
	poll <- time.Now()
	wait(4)
	// No event was sent for terminal version 4. The configured periodic poll found it.
	mu.Lock()
	version = 2
	mu.Unlock()
	foreground <- struct{}{}
	wait(2)
	// Wait for processing, using a second foreground observation as a barrier.
	foreground <- struct{}{}
	wait(2)
	if c.Current(key).Version != 4 {
		t.Fatal("older snapshot rolled state back")
	}
	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("client did not stop")
	}
}

func TestEventBufferedWhileSnapshotInFlightAndLateVersionCannotRollback(t *testing.T) {
	key := Key{"MATCH", "00000000-0000-0000-0000-000000000002"}
	fetchStarted := make(chan struct{})
	release := make(chan struct{})
	ack := make(chan struct{})
	eventSent := make(chan struct{})
	var fetchCount int
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := (&websocket.Upgrader{}).Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer func() { _ = conn.Close() }()
		var frame map[string]any
		if conn.ReadJSON(&frame) != nil {
			return
		}
		_ = conn.WriteJSON(map[string]any{"type": "auth_ok"})
		if conn.ReadJSON(&frame) != nil {
			return
		}
		_ = conn.WriteJSON(map[string]any{"type": "resync_required", "aggregateId": key.ID})
		<-fetchStarted
		_ = conn.WriteJSON(map[string]any{"type": "event", "event": map[string]any{"eventId": "00000000-0000-0000-0000-000000000003", "aggregateId": key.ID, "aggregateType": key.Kind, "aggregateVersion": 3}})
		close(eventSent)
		for {
			if conn.ReadJSON(&frame) != nil {
				return
			}
			if frame["type"] == "ack" {
				close(ack)
				return
			}
		}
	}))
	defer server.Close()
	c := &Client{Keys: []Key{key}, Fetch: func(context.Context, Key) (Snapshot, error) {
		fetchCount++
		if fetchCount == 1 {
			close(fetchStarted)
			<-release
			return Snapshot{Version: 1, Data: json.RawMessage(`{}`)}, nil
		}
		return Snapshot{Version: 3, Data: json.RawMessage(`{}`)}, nil
	}}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- c.Reconnect(ctx, "ws"+strings.TrimPrefix(server.URL, "http"), "ticket") }()
	<-eventSent
	close(release)
	select {
	case <-ack:
	case <-time.After(2 * time.Second):
		t.Fatal("buffered event not acknowledged")
	}
	if c.Current(key).Version != 3 {
		t.Fatal("buffer did not recover gap")
	}
	<-done
	c.Fetch = func(context.Context, Key) (Snapshot, error) { return Snapshot{Version: 2}, nil }
	if err := c.Reconcile(ctx); err != nil {
		t.Fatal(err)
	}
	if c.Current(key).Version != 3 {
		t.Fatal("late response rolled version back")
	}
}
