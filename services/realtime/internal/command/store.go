package command

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

var ErrConflict = errors.New("IDEMPOTENCY_CONFLICT")

type Database interface {
	BeginTx(context.Context, pgx.TxOptions) (pgx.Tx, error)
}

type Store struct {
	DB Database
}

type Response struct {
	Status int
	Body   json.RawMessage
}

// Execute commits the domain effect and response in the same transaction.
// Results are retained until explicit cleanup; match commands must not expire early.
func (s Store) Execute(ctx context.Context, userID, operation, commandID string, payload json.RawMessage, effect func(pgx.Tx) (Response, error)) (Response, error) {
	if strings.TrimSpace(operation) == "" {
		return Response{}, errors.New("empty operation type")
	}
	canonical, err := normalize(payload)
	if err != nil {
		return Response{}, err
	}
	hash := sha256.Sum256(canonical)
	hashString := hex.EncodeToString(hash[:])
	tx, err := s.DB.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return Response{}, err
	}
	// Rollback after commit is harmless; rollback uses a fresh context on cancellation.
	defer func() {
		rollbackCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = tx.Rollback(rollbackCtx)
	}()
	if _, err := tx.Exec(ctx, "SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", userID+"/"+operation+"/"+commandID); err != nil {
		return Response{}, err
	}
	var storedHash string
	var response Response
	err = tx.QueryRow(ctx, "SELECT payload_hash, response_status, response_body FROM command_results WHERE user_id=$1 AND operation_type=$2 AND command_id=$3", userID, operation, commandID).Scan(&storedHash, &response.Status, &response.Body)
	if err == nil {
		if storedHash != hashString {
			return Response{}, ErrConflict
		}
	} else if errors.Is(err, pgx.ErrNoRows) {
		response, err = effect(tx)
		if err != nil {
			return Response{}, err
		}
		if response.Status < 100 || response.Status > 599 || !json.Valid(response.Body) {
			return Response{}, errors.New("invalid command response")
		}
		_, err = tx.Exec(ctx, "INSERT INTO command_results(user_id, operation_type, command_id, payload_hash, response_status, response_body) VALUES ($1,$2,$3,$4,$5,$6::jsonb)", userID, operation, commandID, hashString, response.Status, string(response.Body))
		if err != nil {
			return Response{}, err
		}
	} else {
		return Response{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return Response{}, err
	}
	return response, nil
}

func normalize(input json.RawMessage) ([]byte, error) {
	decoder := json.NewDecoder(bytes.NewReader(input))
	decoder.UseNumber()
	var value any
	if err := decoder.Decode(&value); err != nil {
		return nil, fmt.Errorf("invalid payload JSON: %w", err)
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return nil, errors.New("unexpected content after payload")
	}
	return json.Marshal(value)
}
