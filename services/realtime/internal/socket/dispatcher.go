package socket

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/aratnitsyn/tictactoe/services/realtime/internal/inbox"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/nats-io/nats.go"
	"github.com/redis/go-redis/v9"
	"time"
)

func Dispatch(ctx context.Context, db *pgxpool.Pool, nc *nats.Conn, owner Ownership) error {
	return dispatch(ctx, db, nc, owner, "DOMAIN_EVENTS", "events.*", "realtime-dispatch-v1")
}
func dispatch(ctx context.Context, db *pgxpool.Pool, nc *nats.Conn, owner Ownership, stream, filter, consumer string) error {
	js, err := nc.JetStream()
	if err != nil {
		return err
	}
	sub, err := js.PullSubscribe(filter, consumer, nats.BindStream(stream), nats.ManualAck(), nats.AckExplicit())
	if err != nil {
		return err
	}
	defer func() { _ = sub.Unsubscribe() }()
	delivery := inbox.Delivery{Store: inbox.Store{DB: db}, JS: js, Consumer: consumer, Effect: func(ctx context.Context, _ pgx.Tx, data []byte) error {
		var e Event
		if err := json.Unmarshal(data, &e); err != nil {
			return err
		}
		if !uuidPattern.MatchString(e.ID) || !uuidPattern.MatchString(e.Aggregate) || e.Version < 0 {
			return errors.New("invalid event")
		}
		for _, user := range e.Recipients {
			v, err := owner.Lookup(ctx, user)
			if errors.Is(err, redis.Nil) {
				continue
			}
			if err != nil {
				return err
			}
			body, err := json.Marshal(Routed{User: user, Generation: v.Generation, Data: data})
			if err != nil {
				return err
			}
			if err = nc.Publish("realtime.pod."+v.Pod, body); err != nil {
				return err
			}
		}
		return nc.FlushTimeout(time.Second)
	}}
	for ctx.Err() == nil {
		messages, err := sub.Fetch(16, nats.MaxWait(time.Second))
		if errors.Is(err, nats.ErrTimeout) {
			continue
		}
		if err != nil {
			return err
		}
		for _, msg := range messages {
			if err = delivery.Handle(ctx, msg); err != nil {
				return err
			}
		}
	}
	return ctx.Err()
}
