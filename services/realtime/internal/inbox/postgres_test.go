package inbox

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/aratnitsyn/tictactoe/services/realtime/internal/testutil"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/nats-io/nats.go"
)

func setup(t *testing.T) (context.Context, *pgxpool.Pool, nats.JetStreamContext, *nats.Subscription, string, string, string) {
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
	consumer := "inbox_" + strings.ReplaceAll(eventID, "-", "")
	stream := strings.ToUpper(consumer)
	subject := "it." + consumer + ".event"
	if _, err := js.AddStream(&nats.StreamConfig{Name: stream, Subjects: []string{subject}, Storage: nats.FileStorage, MaxMsgs: 1000, MaxBytes: 1048576, MaxAge: time.Hour, Replicas: 1}); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := js.DeleteStream(stream); err != nil {
			t.Error(err)
		}
	})
	if err := Register(js, stream, consumer, subject); err != nil {
		t.Fatal(err)
	}
	sub, err := js.PullSubscribe(subject, consumer, nats.Bind(stream, consumer))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sub.Unsubscribe() })
	return ctx, pool, js, sub, consumer, subject, eventID
}

func TestInboxDuplicateAndRollback(t *testing.T) {
	ctx, pool, _, _, consumer, _, eventID := setup(t)
	store := Store{DB: pool}
	if _, err := pool.Exec(ctx, "CREATE TABLE inbox_probe (event_id uuid PRIMARY KEY)"); err != nil {
		t.Fatal(err)
	}
	effect := func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx, "INSERT INTO inbox_probe VALUES ($1)", eventID)
		return err
	}
	count := func(want int) {
		t.Helper()
		var got int
		if err := pool.QueryRow(ctx, "SELECT count(*) FROM inbox_probe").Scan(&got); err != nil || got != want {
			t.Fatalf("expected %d domain effects, got %d: %v", want, got, err)
		}
	}
	injected := errors.New("injected before commit")
	if _, err := store.Process(ctx, consumer, eventID, func(tx pgx.Tx) error {
		if err := effect(tx); err != nil {
			return err
		}
		return injected
	}); !errors.Is(err, injected) {
		t.Fatal(err)
	}
	count(0)
	if first, err := store.Process(ctx, consumer, eventID, effect); err != nil || !first {
		t.Fatalf("expected retry after rollback: %v", err)
	}
	if first, err := store.Process(ctx, consumer, eventID, func(pgx.Tx) error { t.Error("committed effect repeated"); return nil }); err != nil || first {
		t.Fatalf("duplicate not suppressed: %v", err)
	}
	count(1)
}

func TestRetryThenParking(t *testing.T) {
	ctx, pool, js, sub, consumer, subject, eventID := setup(t)
	if _, err := js.Publish(subject, []byte(`{"eventId":"`+eventID+`"}`)); err != nil {
		t.Fatal(err)
	}
	delivery := Delivery{Store: Store{DB: pool}, JS: js, Consumer: consumer, Effect: func(context.Context, pgx.Tx, []byte) error { return errors.New("injected handler failure") }, RetryDelays: []time.Duration{0}, MaxAttempts: 2}
	for range 2 {
		messages, err := sub.Fetch(1, nats.MaxWait(5*time.Second))
		if err != nil {
			t.Fatal(err)
		}
		if err := delivery.Handle(ctx, messages[0]); err != nil {
			t.Fatal(err)
		}
	}
	message, err := js.GetLastMsg("PARKING", "parking."+consumer)
	if err != nil || len(message.Data) == 0 {
		t.Fatalf("parking message missing: %v", err)
	}
}

type failingParking struct{ nats.JetStreamContext }

func (p failingParking) Publish(string, []byte, ...nats.PubOpt) (*nats.PubAck, error) {
	return nil, errors.New("injected parking outage")
}

func TestParkingFailureRetainsOriginal(t *testing.T) {
	ctx, pool, js, sub, consumer, subject, eventID := setup(t)
	if _, err := js.Publish(subject, []byte(`{"eventId":"`+eventID+`"}`)); err != nil {
		t.Fatal(err)
	}
	delivery := Delivery{Store: Store{DB: pool}, JS: failingParking{js}, Consumer: consumer, Effect: func(context.Context, pgx.Tx, []byte) error { return errors.New("handler failure") }, RetryDelays: []time.Duration{0}, MaxAttempts: 1}
	messages, err := sub.Fetch(1, nats.MaxWait(5*time.Second))
	if err != nil {
		t.Fatal(err)
	}
	if err := delivery.Handle(ctx, messages[0]); err == nil {
		t.Fatal("expected parking publish failure")
	}
	delivery.JS = js
	messages, err = sub.Fetch(1, nats.MaxWait(5*time.Second))
	if err != nil {
		t.Fatal("original message was lost:", err)
	}
	if err := delivery.Handle(ctx, messages[0]); err != nil {
		t.Fatal(err)
	}
}

func TestReplayPreservesEventIdentity(t *testing.T) {
	ctx, pool, js, sub, consumer, subject, eventID := setup(t)
	if _, err := js.Publish(subject, []byte(`{"eventId":"`+eventID+`"}`)); err != nil {
		t.Fatal(err)
	}
	delivery := Delivery{Store: Store{DB: pool}, JS: js, Consumer: consumer, Effect: func(context.Context, pgx.Tx, []byte) error { return errors.New("handler bug") }, RetryDelays: []time.Duration{0}, MaxAttempts: 1}
	messages, err := sub.Fetch(1, nats.MaxWait(5*time.Second))
	if err != nil {
		t.Fatal(err)
	}
	if err := delivery.Handle(ctx, messages[0]); err != nil {
		t.Fatal(err)
	}
	parked, err := js.GetLastMsg("PARKING", "parking."+consumer)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Replay(ctx, js, parked.Data, eventID, "it."); err != nil {
		t.Fatal(err)
	}
	calls := 0
	delivery.Effect = func(context.Context, pgx.Tx, []byte) error { calls++; return nil }
	messages, err = sub.Fetch(1, nats.MaxWait(5*time.Second))
	if err != nil {
		t.Fatal(err)
	}
	if err := delivery.Handle(ctx, messages[0]); err != nil {
		t.Fatal(err)
	}
	ack, err := Replay(ctx, js, parked.Data, eventID, "it.")
	if err != nil || !ack.Duplicate {
		t.Fatalf("operator retry was not deduplicated: %v", err)
	}
	otherOperation := "00000000-0000-4000-8000-000000000001"
	if _, err := Replay(ctx, js, parked.Data, otherOperation, "it."); err != nil {
		t.Fatal(err)
	}
	messages, err = sub.Fetch(1, nats.MaxWait(5*time.Second))
	if err != nil {
		t.Fatal(err)
	}
	if err := delivery.Handle(ctx, messages[0]); err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatalf("replay repeated domain effect %d times", calls)
	}
}
