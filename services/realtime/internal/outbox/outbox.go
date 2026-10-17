package outbox

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/nats-io/nats.go"
)

type Event struct {
	ID               string
	AggregateID      string
	AggregateVersion int64
	Type             string
	Envelope         json.RawMessage
}

// Enqueue must run in the transaction that performs the domain change.
func Enqueue(ctx context.Context, tx pgx.Tx, event Event) error {
	if !json.Valid(event.Envelope) {
		return errors.New("invalid outbox JSON")
	}
	_, err := tx.Exec(ctx, "INSERT INTO outbox(event_id, aggregate_id, aggregate_version, event_type, envelope) VALUES ($1,$2,$3,$4,$5::jsonb)", event.ID, event.AggregateID, event.AggregateVersion, event.Type, string(event.Envelope))
	return err
}

type Database interface {
	QueryRow(context.Context, string, ...any) pgx.Row
	Exec(context.Context, string, ...any) (pgconn.CommandTag, error)
}

type Publisher interface {
	Publish(context.Context, Event) error
}

type JetStreamPublisher struct {
	JS            nats.JetStreamContext
	SubjectPrefix string
}

func (p JetStreamPublisher) Publish(ctx context.Context, event Event) error {
	prefix := p.SubjectPrefix
	if prefix == "" {
		prefix = "events"
	}
	_, err := p.JS.Publish(prefix+"."+event.Type, event.Envelope, nats.MsgId(event.ID), nats.Context(ctx))
	return err
}

type Worker struct {
	DB        Database
	Publisher Publisher
	Owner     string
	// AfterAck is a fault-injection boundary. Production leaves it nil.
	AfterAck func() error
}

// PublishOne claims one row; publication happens outside the database transaction.
// A crash before marking published leaves a lease that expires after 30 seconds.
func (w Worker) PublishOne(ctx context.Context) (bool, error) {
	if w.Owner == "" {
		return false, errors.New("outbox owner is required")
	}
	var event Event
	err := w.DB.QueryRow(ctx, `UPDATE outbox SET lease_owner=$1,
        lease_until=clock_timestamp()+interval '30 seconds', attempts=attempts+1
        WHERE event_id=(SELECT event_id FROM outbox
            WHERE published_at IS NULL AND (lease_until IS NULL OR lease_until <= clock_timestamp())
            ORDER BY created_at,event_id FOR UPDATE SKIP LOCKED LIMIT 1)
        RETURNING event_id,aggregate_id,aggregate_version,event_type,envelope`, w.Owner).
		Scan(&event.ID, &event.AggregateID, &event.AggregateVersion, &event.Type, &event.Envelope)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if err := w.Publisher.Publish(ctx, event); err != nil {
		return true, err
	}
	if w.AfterAck != nil {
		if err := w.AfterAck(); err != nil {
			return true, err
		}
	}
	tag, err := w.DB.Exec(ctx, "UPDATE outbox SET published_at=clock_timestamp(),lease_owner=NULL,lease_until=NULL WHERE event_id=$1 AND lease_owner=$2 AND published_at IS NULL", event.ID, w.Owner)
	if err != nil {
		return true, err
	}
	if tag.RowsAffected() != 1 {
		return true, fmt.Errorf("outbox lease lost for event %s", event.ID)
	}
	return true, nil
}
