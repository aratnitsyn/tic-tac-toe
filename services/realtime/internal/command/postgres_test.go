package command

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/aratnitsyn/tictactoe/services/realtime/internal/testutil"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

func testID(t *testing.T) string {
	t.Helper()
	var value [16]byte
	if _, err := rand.Read(value[:]); err != nil {
		t.Fatal(err)
	}
	value[6] = value[6]&0x0f | 0x40
	value[8] = value[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", value[:4], value[4:6], value[6:8], value[8:10], value[10:])
}

func database(t *testing.T) (*pgxpool.Pool, context.Context) {
	t.Helper()
	dsn := os.Getenv("PG_DSN")
	if dsn == "" {
		t.Skip("PG_DSN required for PostgreSQL integration test")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	t.Cleanup(cancel)
	pool := testutil.Database(t)
	if _, err := pool.Exec(ctx, "CREATE TABLE IF NOT EXISTS command_store_probe (id uuid PRIMARY KEY, amount integer NOT NULL)"); err != nil {
		t.Fatal(err)
	}
	return pool, ctx
}

func TestConcurrentRetriesCommitOnce(t *testing.T) {
	pool, ctx := database(t)
	store := Store{DB: pool}
	userID, commandID := testID(t), testID(t)
	var workers sync.WaitGroup
	failures := make(chan error, 8)
	start := make(chan struct{})
	for range 8 {
		workers.Add(1)
		go func() {
			defer workers.Done()
			<-start
			result, err := store.Execute(ctx, userID, "MOVE", commandID, json.RawMessage(`{"cell":1}`), func(tx pgx.Tx) (Response, error) {
				_, err := tx.Exec(ctx, "INSERT INTO command_store_probe VALUES ($1,1)", commandID)
				return Response{Status: 200, Body: json.RawMessage(`{"accepted":true}`)}, err
			})
			if err == nil {
				var body struct {
					Accepted bool `json:"accepted"`
				}
				if decodeErr := json.Unmarshal(result.Body, &body); decodeErr != nil || result.Status != 200 || !body.Accepted {
					err = fmt.Errorf("unexpected replay response: %d %s", result.Status, result.Body)
				}
			}
			failures <- err
		}()
	}
	close(start)
	workers.Wait()
	close(failures)
	for err := range failures {
		if err != nil {
			t.Fatal(err)
		}
	}
	var count int
	if err := pool.QueryRow(ctx, "SELECT COUNT(*) FROM command_store_probe WHERE id=$1", commandID).Scan(&count); err != nil || count != 1 {
		t.Fatalf("expected one effect, got %d (error %v)", count, err)
	}
	_, err := store.Execute(ctx, userID, "MOVE", commandID, json.RawMessage(`{"cell":2}`), func(pgx.Tx) (Response, error) {
		t.Error("conflicting effect must not execute")
		return Response{}, nil
	})
	if !errors.Is(err, ErrConflict) {
		t.Fatalf("expected conflict, got %v", err)
	}
}

func TestFailedEffectRollsBack(t *testing.T) {
	pool, ctx := database(t)
	store := Store{DB: pool}
	userID, commandID := testID(t), testID(t)
	injected := errors.New("injected failure")
	_, err := store.Execute(ctx, userID, "MOVE", commandID, json.RawMessage(`{}`), func(tx pgx.Tx) (Response, error) {
		if _, err := tx.Exec(ctx, "INSERT INTO command_store_probe VALUES ($1,1)", commandID); err != nil {
			return Response{}, err
		}
		return Response{}, injected
	})
	if !errors.Is(err, injected) {
		t.Fatal(err)
	}
	var count int
	if err := pool.QueryRow(ctx, "SELECT COUNT(*) FROM command_store_probe WHERE id=$1", commandID).Scan(&count); err != nil || count != 0 {
		t.Fatalf("rollback failed: count %d, error %v", count, err)
	}
	_, err = store.Execute(ctx, userID, "MOVE", commandID, json.RawMessage(`{}`), func(pgx.Tx) (Response, error) {
		return Response{Status: 200, Body: json.RawMessage(`{}`)}, nil
	})
	if err != nil {
		t.Fatal(err)
	}
}
