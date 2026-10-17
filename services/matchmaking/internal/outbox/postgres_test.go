package outbox

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/aratnitsyn/tictactoe/services/matchmaking/internal/testutil"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/nats-io/nats.go"
)

func fixture(t *testing.T) (context.Context, *pgxpool.Pool, nats.JetStreamContext, string, string, Event) {
	t.Helper()
	if os.Getenv("PG_DSN") == "" || os.Getenv("NATS_PASSWORD") == "" {
		t.Skip("PG_DSN and NATS_PASSWORD required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	t.Cleanup(cancel)
	pool := testutil.Database(t)
	nc, err := nats.Connect(os.Getenv("NATS_URL"), nats.UserInfo(os.Getenv("NATS_USER"), os.Getenv("NATS_PASSWORD")))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(nc.Close)
	js, err := nc.JetStream()
	if err != nil {
		t.Fatal(err)
	}
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		t.Fatal(err)
	}
	eventID := fmt.Sprintf("%x-%x-%x-%x-%x", id[:4], id[4:6], id[6:8], id[8:10], id[10:])
	stream := "IT_" + strings.ReplaceAll(eventID, "-", "")
	prefix := "it." + eventID
	_, err = js.AddStream(&nats.StreamConfig{Name: stream, Subjects: []string{prefix + ".>"}, Storage: nats.FileStorage, Retention: nats.LimitsPolicy, Discard: nats.DiscardNew, Replicas: 1, MaxMsgs: 1000, MaxBytes: 1048576, MaxAge: time.Hour})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := js.DeleteStream(stream); err != nil {
			t.Error(err)
		}
	})
	event := Event{ID: eventID, AggregateID: eventID, AggregateVersion: 1, Type: "AccountRegistered", Envelope: json.RawMessage(`{"type":"AccountRegistered","payload":{}}`)}
	return ctx, pool, js, stream, prefix, event
}

func TestCrashAfterAckRetriesSameEvent(t *testing.T) {
	ctx, pool, js, stream, prefix, event := fixture(t)
	tx, err := pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if err := Enqueue(ctx, tx, event); err != nil {
		t.Fatal(err)
	}
	if err := tx.Commit(ctx); err != nil {
		t.Fatal(err)
	}
	injected := errors.New("crash after broker ack")
	worker := Worker{DB: pool, Publisher: JetStreamPublisher{JS: js, SubjectPrefix: prefix}, Owner: event.ID, AfterAck: func() error { return injected }}
	if worked, err := worker.PublishOne(ctx); !worked || !errors.Is(err, injected) {
		t.Fatalf("expected injected failure, got %v", err)
	}
	var pending bool
	if err := pool.QueryRow(ctx, "SELECT published_at IS NULL FROM outbox WHERE event_id=$1", event.ID).Scan(&pending); err != nil || !pending {
		t.Fatalf("event prematurely marked: %v", err)
	}
	if _, err := pool.Exec(ctx, "UPDATE outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE event_id=$1", event.ID); err != nil {
		t.Fatal(err)
	}
	worker.Owner = event.ID + "-restart"
	worker.AfterAck = nil
	if worked, err := worker.PublishOne(ctx); !worked || err != nil {
		t.Fatalf("retry failed: %v", err)
	}
	info, err := js.StreamInfo(stream)
	if err != nil || info.State.Msgs != 1 {
		t.Fatalf("expected one deduplicated broker message: %v", err)
	}
	if worked, err := worker.PublishOne(ctx); worked || err != nil {
		t.Fatalf("expected empty outbox: %v", err)
	}
}

func TestRolledBackEnqueueIsNotPublished(t *testing.T) {
	ctx, pool, _, _, _, event := fixture(t)
	tx, err := pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if err := Enqueue(ctx, tx, event); err != nil {
		t.Fatal(err)
	}
	if err := tx.Rollback(ctx); err != nil {
		t.Fatal(err)
	}
	var count int
	if err := pool.QueryRow(ctx, "SELECT COUNT(*) FROM outbox WHERE event_id=$1", event.ID).Scan(&count); err != nil || count != 0 {
		t.Fatalf("rolled-back event survived: %v", err)
	}
}
