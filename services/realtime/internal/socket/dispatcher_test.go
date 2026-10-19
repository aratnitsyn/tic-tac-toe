package socket

import (
	"context"
	"encoding/json"
	"github.com/aratnitsyn/tictactoe/services/realtime/internal/testutil"
	"github.com/nats-io/nats.go"
	"github.com/redis/go-redis/v9"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func TestDispatcherAcrossPodsAndNATSOutage(t *testing.T) {
	db := testutil.Database(t)
	if os.Getenv("NATS_URL") == "" || os.Getenv("REDIS_PASSWORD") == "" {
		t.Skip("NATS/Redis required")
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	nc, err := nats.Connect(os.Getenv("NATS_URL"), nats.UserInfo(os.Getenv("NATS_USER"), os.Getenv("NATS_PASSWORD")))
	if err != nil {
		t.Fatal(err)
	}
	defer nc.Close()
	js, err := nc.JetStream()
	if err != nil {
		t.Fatal(err)
	}
	suffix := strings.ReplaceAll(ID(), "-", "")
	stream := "RT_IT_" + suffix
	subject := "rt_it." + suffix
	if _, err = js.AddStream(&nats.StreamConfig{Name: stream, Subjects: []string{subject}, Storage: nats.MemoryStorage, MaxMsgs: 10}); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = js.DeleteStream(stream) }()
	rc := redis.NewClient(&redis.Options{Addr: "127.0.0.1:6379", Password: os.Getenv("REDIS_PASSWORD")})
	defer func() { _ = rc.Close() }()
	match := ID()
	recipients := []string{ID(), ID()}
	connections := make([]interface {
		WriteJSON(any) error
		ReadJSON(any) error
	}, 0, 2)
	for i, user := range recipients {
		o := Ownership{DB: db, Redis: rc, Pod: "it" + suffix + string(rune('a'+i))}
		a := &fakeAuthority{user: user, session: ID(), match: match}
		s := &Server{Backend: a, Ownership: o, NATS: nc, Pod: o.Pod}
		sub, err := s.Subscribe()
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = sub.Unsubscribe() }()
		ts := httptest.NewServer(s.Handler())
		defer ts.Close()
		c := dial(t, "ws"+strings.TrimPrefix(ts.URL, "http"))
		defer func() { _ = c.Close() }()
		auth(t, c)
		_ = c.WriteJSON(map[string]any{"type": "subscribe", "matchId": match})
		var frame map[string]any
		if err = c.ReadJSON(&frame); err != nil {
			t.Fatal(err)
		}
		connections = append(connections, c)
		defer func() { _ = rc.Del(context.Background(), "rt:owner:"+user).Err() }()
	}
	if err = nc.FlushTimeout(time.Second); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- dispatch(ctx, db, nc, Ownership{Redis: rc}, stream, subject, "it_"+suffix) }()
	// Publish ack precedes delivery; both pods have independent Core subscriptions.
	event := Event{ID: ID(), Kind: "MATCH", Aggregate: match, Version: 3, Recipients: recipients}
	body, _ := json.Marshal(event)
	if _, err = js.Publish(subject, body); err != nil {
		t.Fatal(err)
	}
	for _, c := range connections {
		var v struct {
			Type  string
			Event Event
		}
		if err = c.ReadJSON(&v); err != nil || v.Type != "event" || v.Event.Version != 3 {
			t.Fatal("cross pod event", err, v)
		}
	}
	// Dispatcher failure cannot acknowledge a future event as delivered.
	nc.Close()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("dispatcher did not stop on NATS outage")
	}
}
