// Package queue owns durable FIFO admission and pair handoff. Game owns participation.
package queue

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"time"

	"github.com/aratnitsyn/tictactoe/services/matchmaking/internal/command"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Failure struct {
	Code   string
	Status int
}

func (e *Failure) Error() string         { return e.Code }
func fail(code string, status int) error { return &Failure{code, status} }

var uuidPattern = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

func UUID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	h := hex.EncodeToString(b[:])
	return h[:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:]
}
func validID(s string) bool { return uuidPattern.MatchString(s) }

type Ticket struct {
	ID                   string `json:"ticketId"`
	User                 string `json:"userId"`
	Sequence             string `json:"originalSequence"`
	Status               string `json:"status"`
	Version              int64  `json:"version"`
	ParticipationVersion int64  `json:"-"`
	Pair                 string `json:"-"`
	Connected            bool   `json:"-"`
}
type Operation struct {
	ID, Kind, User, Command, Correlation string
	Payload, GamePayload                 json.RawMessage
	Response                             command.Response
}
type Outcome struct {
	Status     string `json:"status"`
	Version    int64  `json:"version"`
	ErrorCode  string `json:"errorCode"`
	ResourceID string `json:"resourceId,omitempty"`
}
type Participation struct {
	User     string `json:"userId"`
	State    string `json:"state"`
	Version  int64  `json:"version"`
	Resource string `json:"resourceId,omitempty"`
}
type Game interface {
	Resolve(context.Context, Operation) (Outcome, error)
	Participation(context.Context, string) (Participation, error)
}
type Service struct {
	DB        *pgxpool.Pool
	Game      Game
	AfterGame func() error
}

func (s *Service) transaction(ctx context.Context, f func(pgx.Tx) error) error {
	return s.read(ctx, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, "SELECT id FROM queue_control WHERE id FOR UPDATE"); err != nil {
			return err
		}
		return f(tx)
	})
}
func (s *Service) read(ctx context.Context, f func(pgx.Tx) error) error {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() {
		c, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = tx.Rollback(c)
	}()
	if _, err = tx.Exec(ctx, "SET LOCAL lock_timeout='3s'"); err != nil {
		return err
	}
	if err = f(tx); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
func readOperation(ctx context.Context, tx pgx.Tx, id string) (Operation, error) {
	var op Operation
	var status *int
	err := tx.QueryRow(ctx, `SELECT operation_id,kind,user_id,command_id,payload,game_payload,correlation_id,response_status,response_body FROM queue_operation WHERE operation_id=$1`, id).Scan(&op.ID, &op.Kind, &op.User, &op.Command, &op.Payload, &op.GamePayload, &op.Correlation, &status, &op.Response.Body)
	if status != nil {
		op.Response.Status = *status
	}
	return op, err
}
func readTicket(ctx context.Context, tx pgx.Tx, id string) (Ticket, error) {
	var t Ticket
	err := tx.QueryRow(ctx, `SELECT ticket_id,user_id,original_sequence::text,status,version,participation_version,COALESCE(pair_id::text,''),connected FROM queue_ticket WHERE ticket_id=$1`, id).Scan(&t.ID, &t.User, &t.Sequence, &t.Status, &t.Version, &t.ParticipationVersion, &t.Pair, &t.Connected)
	return t, err
}
func encode(v any) json.RawMessage {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return b
}
func response(ctx context.Context, tx pgx.Tx, status int, version int64, data any) (command.Response, error) {
	var now time.Time
	if err := tx.QueryRow(ctx, "SELECT clock_timestamp()").Scan(&now); err != nil {
		return command.Response{}, err
	}
	return command.Response{Status: status, Body: encode(map[string]any{"serverTime": now.UTC().Format(time.RFC3339Nano), "version": version, "data": data})}, nil
}
func errorResponse(ctx context.Context, tx pgx.Tx, code string, correlation string) (command.Response, error) {
	var now time.Time
	err := tx.QueryRow(ctx, "SELECT clock_timestamp()").Scan(&now)
	return command.Response{Status: 409, Body: encode(map[string]any{"code": code, "message": code, "correlationId": correlation, "serverTime": now.UTC().Format(time.RFC3339Nano)})}, err
}
func ticketID(op Operation) (string, error) {
	var p struct {
		Resource string `json:"resourceId,omitempty"`
	}
	if err := json.Unmarshal(op.GamePayload, &p); err != nil {
		return "", err
	}
	if !validID(p.Resource) {
		return "", errors.New("invalid persisted ticket resource")
	}
	return p.Resource, nil
}
func versionPayload(raw json.RawMessage) (int64, error) {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(raw, &fields); err != nil || len(fields) != 1 {
		return 0, fail("VALIDATION_ERROR", 400)
	}
	var v int64
	if err := json.Unmarshal(fields["expectedVersion"], &v); err != nil || v < 0 || string(fields["expectedVersion"]) == "null" {
		return 0, fail("VALIDATION_ERROR", 400)
	}
	return v, nil
}
func unavailable(err error) error {
	if errors.Is(err, pgx.ErrNoRows) {
		return fail("NOT_FOUND", 404)
	}
	return fmt.Errorf("queue storage: %w", err)
}
