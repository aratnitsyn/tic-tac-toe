package queue

import (
	"context"
	"encoding/json"
	"time"

	"github.com/aratnitsyn/tictactoe/services/matchmaking/internal/outbox"
	"github.com/jackc/pgx/v5"
)

func emit(ctx context.Context, tx pgx.Tx, t Ticket, correlation, causation string) error {
	var now time.Time
	if err := tx.QueryRow(ctx, "SELECT clock_timestamp()").Scan(&now); err != nil {
		return err
	}
	id := UUID()
	return outbox.Enqueue(ctx, tx, outbox.Event{ID: id, AggregateID: t.ID, AggregateVersion: t.Version, Type: "QueueChanged", Envelope: encode(map[string]any{"eventId": id, "type": "QueueChanged", "schemaVersion": 1, "aggregateType": "QUEUE", "aggregateId": t.ID, "aggregateVersion": t.Version, "occurredAt": now.UTC().Format(time.RFC3339Nano), "correlationId": correlation, "causationId": causation, "recipientIds": []string{t.User}, "payload": t})})
}

// Return is prepared for stage 7. Its caller commits inbox and ticket together.
// Game participation is checked outside the transaction; the version/resource fence
// makes stale returns harmless, and AssignPair still validates the authoritative CAS.
func (s *Service) Return(ctx context.Context, tx pgx.Tx, raw []byte, actual Participation) error {
	var e struct {
		ID          string `json:"eventId"`
		Type        string `json:"type"`
		Version     int64  `json:"aggregateVersion"`
		Correlation string `json:"correlationId"`
		Payload     struct {
			User     string `json:"userId"`
			Ticket   string `json:"ticketId"`
			Sequence string `json:"originalSequence"`
			Version  int64  `json:"participationVersion"`
		} `json:"payload"`
	}
	if err := json.Unmarshal(raw, &e); err != nil {
		return err
	}
	p := e.Payload
	if e.Type != "ReturnToQueue" || !validID(p.User) || !validID(p.Ticket) || p.Version != e.Version {
		return fail("VALIDATION_ERROR", 400)
	}
	if _, err := tx.Exec(ctx, "SELECT id FROM queue_control WHERE id FOR UPDATE"); err != nil {
		return err
	}
	t, err := readTicket(ctx, tx, p.Ticket)
	if err != nil {
		return err
	}
	if t.User != p.User || t.Sequence != p.Sequence {
		return fail("VALIDATION_ERROR", 400)
	}
	if actual.User != p.User || actual.State != "QUEUED" || actual.Resource != t.ID || actual.Version != p.Version || p.Version <= t.ParticipationVersion {
		return nil
	}
	if t.Status == "RESERVED" {
		return fail("SERVICE_UNAVAILABLE", 503)
	}
	if t.Status != "CONSUMED" {
		return nil
	} // An early event is retried until pair finalization.
	_, err = tx.Exec(ctx, "UPDATE queue_ticket SET status='AVAILABLE',participation_version=$2,version=version+1 WHERE ticket_id=$1", t.ID, p.Version)
	if err != nil {
		return err
	}
	t, err = readTicket(ctx, tx, t.ID)
	if err != nil {
		return err
	}
	return emit(ctx, tx, t, e.Correlation, e.ID)
}
