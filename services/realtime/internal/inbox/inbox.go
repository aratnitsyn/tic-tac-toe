package inbox

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"regexp"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/nats-io/nats.go"
)

type Database interface {
	BeginTx(context.Context, pgx.TxOptions) (pgx.Tx, error)
}
type Store struct{ DB Database }

func (s Store) Process(ctx context.Context, consumer, eventID string, effect func(pgx.Tx) error) (bool, error) {
	if consumer == "" || eventID == "" {
		return false, errors.New("consumer and eventId required")
	}
	tx, err := s.DB.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return false, err
	}
	defer func() {
		rollbackCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = tx.Rollback(rollbackCtx)
	}()
	tag, err := tx.Exec(ctx, "INSERT INTO inbox(consumer_name,event_id) VALUES ($1,$2) ON CONFLICT DO NOTHING", consumer, eventID)
	if err != nil {
		return false, err
	}
	first := tag.RowsAffected() == 1
	if first {
		if err := effect(tx); err != nil {
			return false, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return false, err
	}
	return first, nil
}

type Delivery struct {
	Store       Store
	JS          nats.JetStreamContext
	Consumer    string
	Effect      func(context.Context, pgx.Tx, []byte) error
	RetryDelays []time.Duration
	MaxAttempts uint64
}

func (d Delivery) Handle(ctx context.Context, msg *nats.Msg) error {
	if !regexp.MustCompile(`^[A-Za-z0-9_-]+$`).MatchString(d.Consumer) {
		return errors.New("invalid consumer")
	}
	var envelope struct {
		EventID string `json:"eventId"`
	}
	parseErr := json.Unmarshal(msg.Data, &envelope)
	if parseErr == nil && envelope.EventID != "" {
		_, err := d.Store.Process(ctx, d.Consumer, envelope.EventID, func(tx pgx.Tx) error { return d.Effect(ctx, tx, msg.Data) })
		if err == nil {
			return msg.Ack()
		}
	}
	metadata, err := msg.Metadata()
	if err != nil {
		return err
	}
	delays := d.RetryDelays
	if len(delays) == 0 {
		delays = []time.Duration{time.Second, 5 * time.Second, 30 * time.Second}
	}
	maxAttempts := d.MaxAttempts
	if maxAttempts == 0 {
		maxAttempts = 5
	}
	index := int(metadata.NumDelivered) - 1
	if index < 0 {
		index = 0
	}
	if index >= len(delays) {
		index = len(delays) - 1
	}
	if metadata.NumDelivered < maxAttempts {
		return msg.NakWithDelay(delays[index])
	}
	hash := sha256.Sum256(msg.Data)
	body, err := json.Marshal(struct {
		Consumer        string `json:"consumer"`
		OriginalSubject string `json:"originalSubject"`
		PayloadBase64   string `json:"payloadBase64"`
	}{d.Consumer, msg.Subject, base64.StdEncoding.EncodeToString(msg.Data)})
	if err != nil {
		return err
	}
	_, err = d.JS.Publish("parking."+d.Consumer, body, nats.MsgId(d.Consumer+"/"+hex.EncodeToString(hash[:])), nats.Context(ctx))
	if err != nil {
		if nakErr := msg.NakWithDelay(delays[index]); nakErr != nil {
			return errors.Join(err, nakErr)
		}
		return err
	}
	return msg.Term()
}

// Register configures explicit acknowledgement and lets failed parking retry indefinitely.
func Register(js nats.JetStreamContext, stream, consumer, filter string) error {
	_, err := js.AddConsumer(stream, &nats.ConsumerConfig{Durable: consumer, FilterSubject: filter, AckPolicy: nats.AckExplicitPolicy, AckWait: 30 * time.Second, MaxDeliver: -1, MaxAckPending: 256})
	return err
}
