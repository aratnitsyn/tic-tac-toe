package queue

import (
	"context"
	"encoding/json"
	"errors"

	"github.com/aratnitsyn/tictactoe/services/matchmaking/internal/command"
	"github.com/jackc/pgx/v5"
)

// Select serializes eligibility/order. It never skips a locked FIFO head.
func (s *Service) Select(ctx context.Context) (string, error) {
	id := ""
	err := s.transaction(ctx, func(tx pgx.Tx) error {
		rows, err := tx.Query(ctx, "SELECT ticket_id FROM queue_ticket WHERE status='AVAILABLE' AND connected AND availability_until>clock_timestamp() ORDER BY original_sequence,admission_sequence LIMIT 2 FOR UPDATE")
		if err != nil {
			return err
		}
		var ids []string
		for rows.Next() {
			var v string
			if err = rows.Scan(&v); err != nil {
				rows.Close()
				return err
			}
			ids = append(ids, v)
		}
		rows.Close()
		if err = rows.Err(); err != nil {
			return err
		}
		if len(ids) != 2 {
			return nil
		}
		id = UUID()
		players := make([]map[string]any, 0, 2)
		for _, ticket := range ids {
			t, err := readTicket(ctx, tx, ticket)
			if err != nil {
				return err
			}
			players = append(players, map[string]any{"userId": t.User, "ticketId": t.ID, "expectedVersion": t.ParticipationVersion, "originalSequence": t.Sequence})
		}
		game := encode(map[string]any{"operationId": id, "matchId": UUID(), "players": players})
		_, err = tx.Exec(ctx, `INSERT INTO queue_operation(operation_id,kind,user_id,command_id,payload,game_payload,correlation_id) VALUES($1,'PAIR',$1,$1,'{}',$2,$1)`, id, game)
		if err != nil {
			return err
		}
		for _, ticket := range ids {
			_, err = tx.Exec(ctx, "UPDATE queue_ticket SET status='RESERVED',pair_id=$2,version=version+1 WHERE ticket_id=$1", ticket, id)
			if err != nil {
				return err
			}
			t, err := readTicket(ctx, tx, ticket)
			if err != nil {
				return err
			}
			if err = emit(ctx, tx, t, id, id); err != nil {
				return err
			}
		}
		return nil
	})
	return id, err
}
func (s *Service) finishPair(ctx context.Context, op Operation, result Outcome) (command.Response, error) {
	var request struct {
		Players []struct {
			User    string `json:"userId"`
			Ticket  string `json:"ticketId"`
			Version int64  `json:"expectedVersion"`
		}
	}
	if err := json.Unmarshal(op.GamePayload, &request); err != nil {
		return command.Response{}, err
	}
	actual := map[string]Participation{}
	if result.Status == "REJECTED" {
		for _, p := range request.Players {
			v, err := s.Game.Participation(ctx, p.User)
			if err != nil {
				return command.Response{}, err
			}
			actual[p.Ticket] = v
		}
	}
	err := s.transaction(ctx, func(tx pgx.Tx) error {
		current, err := readOperation(ctx, tx, op.ID)
		if err != nil {
			return err
		}
		if current.Response.Status != 0 {
			op = current
			return nil
		}
		for _, p := range request.Players {
			t, err := readTicket(ctx, tx, p.Ticket)
			if err != nil {
				return err
			}
			if t.Pair != op.ID || t.Status != "RESERVED" {
				continue
			}
			status := "CONSUMED"
			version := t.ParticipationVersion
			if result.Status == "REJECTED" {
				v := actual[t.ID]
				status = "CANCELLED"
				if v.State == "QUEUED" && v.Resource == t.ID {
					status = "AVAILABLE"
					version = v.Version
				}
			}
			_, err = tx.Exec(ctx, "UPDATE queue_ticket SET status=$2,participation_version=$3,version=version+1 WHERE ticket_id=$1", t.ID, status, version)
			if err != nil {
				return err
			}
			t, err = readTicket(ctx, tx, t.ID)
			if err != nil {
				return err
			}
			if err = emit(ctx, tx, t, op.Correlation, op.ID); err != nil {
				return err
			}
		}
		op.Response = command.Response{Status: 200, Body: encode(result)}
		_, err = tx.Exec(ctx, "UPDATE queue_operation SET response_status=200,response_body=$2 WHERE operation_id=$1", op.ID, op.Response.Body)
		return err
	})
	return op.Response, err
}

// Reconcile retries durable requests; unknown results remain pending/reserved.
func (s *Service) Reconcile(ctx context.Context) error {
	rows, err := s.DB.Query(ctx, "SELECT operation_id FROM queue_operation WHERE response_status IS NULL ORDER BY created_at LIMIT 100")
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
	var failures []error
	for _, id := range ids {
		if _, err = s.Resume(ctx, id); err != nil {
			failures = append(failures, err)
		}
	}
	return errors.Join(failures...)
}
