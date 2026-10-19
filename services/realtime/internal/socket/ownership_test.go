package socket

import (
	"context"
	"github.com/aratnitsyn/tictactoe/services/realtime/internal/testutil"
	"github.com/redis/go-redis/v9"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func TestOwnershipRedisLossAndFencing(t *testing.T) {
	db := testutil.Database(t)
	if os.Getenv("REDIS_PASSWORD") == "" {
		t.Skip("REDIS_PASSWORD required")
	}
	rc := redis.NewClient(&redis.Options{Addr: "127.0.0.1:6379", Password: os.Getenv("REDIS_PASSWORD")})
	defer func() { _ = rc.Close() }()
	ctx := context.Background()
	o := Ownership{DB: db, Redis: rc, Pod: "test"}
	id := Identity{true, ID(), ID()}
	defer func() { _ = rc.Del(ctx, "rt:owner:"+id.User).Err() }()
	first, err := o.Claim(ctx, id)
	if err != nil {
		t.Fatal(err)
	}
	other := o
	other.Pod = "other"
	second, err := other.Claim(ctx, id)
	if err != nil {
		t.Fatal(err)
	}
	if second.Generation <= first.Generation {
		t.Fatal("generation not increasing")
	}
	if err = o.Renew(ctx, id.User, first); err == nil {
		t.Fatal("old generation renewed")
	}
	if err = o.Release(ctx, id.User, first); err != nil {
		t.Fatal(err)
	}
	current, err := o.Lookup(ctx, id.User)
	if err != nil || current != second {
		t.Fatal("old release removed current", err)
	}
	if err = rc.Del(ctx, "rt:owner:"+id.User).Err(); err != nil {
		t.Fatal(err)
	}
	third, err := o.Claim(ctx, id)
	if err != nil || third.Generation <= second.Generation {
		t.Fatal("Redis loss reused generation", err)
	}
	_ = rc.Close()
	if o.Renew(ctx, id.User, third) == nil {
		t.Fatal("Redis outage accepted ownership")
	}
}

func TestRedisConnectionOutageRejectsSocketActivity(t *testing.T) {
	db := testutil.Database(t)
	if os.Getenv("REDIS_PASSWORD") == "" {
		t.Skip("REDIS_PASSWORD required")
	}
	rc := redis.NewClient(&redis.Options{Addr: "127.0.0.1:6379", Password: os.Getenv("REDIS_PASSWORD")})
	ctx := context.Background()
	id := ID()
	defer func() {
		cleanup := redis.NewClient(&redis.Options{Addr: "127.0.0.1:6379", Password: os.Getenv("REDIS_PASSWORD")})
		defer func() { _ = cleanup.Close() }()
		_ = cleanup.Del(ctx, "rt:owner:"+id).Err()
	}()
	a := &fakeAuthority{user: id, session: ID(), match: ID(), participating: true, closed: make(chan int64, 2)}
	s := &Server{Backend: a, Ownership: Ownership{DB: db, Redis: rc, Pod: "outage"}}
	ts := httptest.NewServer(s.Handler())
	defer ts.Close()
	c := dial(t, "ws"+strings.TrimPrefix(ts.URL, "http"))
	defer func() { _ = c.Close() }()
	auth(t, c)
	_ = rc.Close()
	_ = c.WriteJSON(map[string]any{"type": "heartbeat", "requestId": ID()})
	if _, _, err := c.ReadMessage(); err == nil {
		t.Fatal("activity accepted without ownership authority")
	}
	select {
	case generation := <-a.closed:
		if generation <= 0 {
			t.Fatal("disconnect missing generation")
		}
	case <-time.After(time.Second):
		t.Fatal("disconnect not observed")
	}
}
