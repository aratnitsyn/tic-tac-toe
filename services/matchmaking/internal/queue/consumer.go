package queue

import (
	"context"
	"encoding/json"

	"github.com/aratnitsyn/tictactoe/services/matchmaking/internal/inbox"
	"github.com/jackc/pgx/v5"
	"github.com/nats-io/nats.go"
)

// Consume runs the HTTP lookup before taking queue_control. Inbox and local effects
// commit together; Game CAS remains the final fence if the snapshot changes later.
func (s *Service) Consume(ctx context.Context, tx pgx.Tx, raw []byte) error {
	var e struct {
		Type        string `json:"type"`
		ID          string `json:"eventId"`
		Correlation string `json:"correlationId"`
		Payload     struct {
			User string `json:"userId"`
		} `json:"payload"`
	}
	if err := json.Unmarshal(raw, &e); err != nil {
		return err
	}
	if !validID(e.Payload.User) {
		return fail("VALIDATION_ERROR", 400)
	}
	actual, err := s.Game.Participation(ctx, e.Payload.User)
	if err != nil {
		return err
	}
	if e.Type == "ReturnToQueue" {
		return s.Return(ctx, tx, raw, actual)
	}
	if e.Type != "ParticipationChanged" {
		return nil
	}
	if actual.State == "IN_MATCH" {
		return nil
	}
	if _, err = tx.Exec(ctx, "SELECT id FROM queue_control WHERE id FOR UPDATE"); err != nil {
		return err
	}
	rows, err := tx.Query(ctx, `UPDATE queue_ticket SET status='CANCELLED',version=version+1 WHERE user_id=$1
 AND status IN ('AVAILABLE','RESERVED') AND participation_version<$2 AND ticket_id::text<>$3 RETURNING ticket_id`, actual.User, actual.Version, actual.Resource)
	if err != nil {
		return err
	}
	var ids []string
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			rows.Close()
			return err
		}
		ids = append(ids, id)
	}
	rows.Close()
	if err = rows.Err(); err != nil {
		return err
	}
	for _, id := range ids {
		t, err := readTicket(ctx, tx, id)
		if err != nil {
			return err
		}
		if err = emit(ctx, tx, t, e.Correlation, e.ID); err != nil {
			return err
		}
	}
	return nil
}

func (s *Service) Subscribe(js nats.JetStreamContext) ([]*nats.Subscription, []inbox.Delivery, error) {
	var subscriptions []*nats.Subscription
	var deliveries []inbox.Delivery
	for _, kind := range []string{"ReturnToQueue", "ParticipationChanged"} {
		name := "matchmaking-" + kind + "-v1"
		subject := "events." + kind
		if err := inbox.Register(js, "DOMAIN_EVENTS", name, subject); err != nil {
			return nil, nil, err
		}
		sub, err := js.PullSubscribe(subject, name, nats.Bind("DOMAIN_EVENTS", name))
		if err != nil {
			return nil, nil, err
		}
		subscriptions = append(subscriptions, sub)
		deliveries = append(deliveries, inbox.Delivery{Store: inbox.Store{DB: s.DB}, JS: js, Consumer: name, Effect: s.Consume})
	}
	return subscriptions, deliveries, nil
}
