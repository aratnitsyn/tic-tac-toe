package queue

import (
	"context"
	"encoding/json"
	"errors"

	"github.com/aratnitsyn/tictactoe/services/matchmaking/internal/command"
	"github.com/jackc/pgx/v5"
)

// Begin persists the original Game request before performing any network I/O.
func (s *Service) Begin(ctx context.Context, user, kind, key, correlation string, payload json.RawMessage) (string, error) {
	if !validID(user) || !validID(key) || !validID(correlation) || (kind != "JOIN" && kind != "CANCEL") {
		return "", fail("VALIDATION_ERROR", 400)
	}
	version, err := versionPayload(payload)
	if err != nil {
		return "", err
	}
	canonical := encode(map[string]int64{"expectedVersion": version})
	id := ""
	err = s.transaction(ctx, func(tx pgx.Tx) error {
		var stored json.RawMessage
		err := tx.QueryRow(ctx, "SELECT operation_id,payload FROM queue_operation WHERE user_id=$1 AND kind=$2 AND command_id=$3", user, kind, key).Scan(&id, &stored)
		if err == nil {
			var old int64
			old, err = versionPayload(stored)
			if err != nil {
				return err
			}
			if old != version {
				return command.ErrConflict
			}
			return nil
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		id = UUID()
		resource := UUID()
		target := "QUEUED"
		if kind == "CANCEL" {
			target = "MENU"
			err = tx.QueryRow(ctx, "SELECT ticket_id FROM queue_ticket WHERE user_id=$1 ORDER BY admission_sequence DESC LIMIT 1", user).Scan(&resource)
			if err != nil {
				return unavailable(err)
			}
		}
		game := encode(map[string]any{"operationId": id, "userId": user, "expectedVersion": version, "target": target, "resourceId": resource})
		_, err = tx.Exec(ctx, `INSERT INTO queue_operation(operation_id,kind,user_id,command_id,payload,game_payload,correlation_id) VALUES($1,$2,$3,$4,$5,$6,$7)`, id, kind, user, key, canonical, game, correlation)
		return err
	})
	return id, err
}
func (s *Service) Execute(ctx context.Context, user, kind, key, correlation string, payload json.RawMessage) (command.Response, error) {
	id, err := s.Begin(ctx, user, kind, key, correlation, payload)
	if err != nil {
		return command.Response{}, err
	}
	return s.Resume(ctx, id)
}
func (s *Service) Resume(ctx context.Context, id string) (command.Response, error) {
	var op Operation
	err := s.read(ctx, func(tx pgx.Tx) error { var err error; op, err = readOperation(ctx, tx, id); return err })
	if err != nil {
		return command.Response{}, err
	}
	if op.Response.Status != 0 {
		return op.Response, nil
	}
	result, err := s.Game.Resolve(ctx, op)
	if err != nil {
		return command.Response{}, err
	}
	if result.Status == "PENDING" {
		return command.Response{}, fail("SERVICE_UNAVAILABLE", 503)
	}
	if result.Status != "APPLIED" && result.Status != "REJECTED" {
		return command.Response{}, fail("SERVICE_UNAVAILABLE", 503)
	}
	if s.AfterGame != nil {
		if err = s.AfterGame(); err != nil {
			return command.Response{}, err
		}
	}
	if op.Kind == "PAIR" {
		return s.finishPair(ctx, op, result)
	}
	ticket, err := ticketID(op)
	if err != nil {
		return command.Response{}, err
	}
	err = s.transaction(ctx, func(tx pgx.Tx) error {
		current, err := readOperation(ctx, tx, id)
		if err != nil {
			return err
		}
		if current.Response.Status != 0 {
			op = current
			return nil
		}
		if result.Status == "REJECTED" {
			code := result.ErrorCode
			if code == "" {
				code = "PARTICIPATION_CONFLICT"
			}
			op.Response, err = errorResponse(ctx, tx, code, op.Correlation)
		} else if op.Kind == "JOIN" {
			var sequence string
			err = tx.QueryRow(ctx, "UPDATE queue_control SET next_sequence=next_sequence+1 WHERE id RETURNING next_sequence::text").Scan(&sequence)
			if err != nil {
				return err
			}
			_, err = tx.Exec(ctx, `INSERT INTO queue_ticket(ticket_id,user_id,admission_sequence,original_sequence,participation_version,status) VALUES($1,$2,$3::numeric,$3::numeric,$4,'AVAILABLE')`, ticket, op.User, sequence, result.Version)
			if err != nil {
				return err
			}
			t, readErr := readTicket(ctx, tx, ticket)
			if readErr != nil {
				return readErr
			}
			op.Response, err = response(ctx, tx, 201, t.Version, t)
			if err == nil {
				err = emit(ctx, tx, t, op.Correlation, op.ID)
			}
		} else {
			// A stale cancel must never cancel a later admission or an assigned match.
			tag, updateErr := tx.Exec(ctx, "UPDATE queue_ticket SET status='CANCELLED',version=version+1 WHERE ticket_id=$1 AND status IN ('AVAILABLE','RESERVED')", ticket)
			err = updateErr
			if err != nil {
				return err
			}
			t, readErr := readTicket(ctx, tx, ticket)
			if readErr != nil {
				return readErr
			}
			if tag.RowsAffected() > 0 {
				if err = emit(ctx, tx, t, op.Correlation, op.ID); err != nil {
					return err
				}
			}
			op.Response, err = response(ctx, tx, 200, result.Version, Participation{User: op.User, State: "MENU", Version: result.Version})
		}
		if err != nil {
			return err
		}
		_, err = tx.Exec(ctx, "UPDATE queue_operation SET response_status=$2,response_body=$3 WHERE operation_id=$1", id, op.Response.Status, op.Response.Body)
		return err
	})
	return op.Response, err
}
func (s *Service) Snapshot(ctx context.Context, user string) (command.Response, error) {
	var result command.Response
	err := s.read(ctx, func(tx pgx.Tx) error {
		var id string
		if err := tx.QueryRow(ctx, "SELECT ticket_id FROM queue_ticket WHERE user_id=$1 ORDER BY admission_sequence DESC LIMIT 1", user).Scan(&id); err != nil {
			return unavailable(err)
		}
		t, err := readTicket(ctx, tx, id)
		if err != nil {
			return err
		}
		result, err = response(ctx, tx, 200, t.Version, t)
		return err
	})
	return result, err
}
