// Replays one persisted request; it never creates a new pair or changes its key.
package main

import (
	"context"
	"flag"
	"log/slog"
	"net/http"
	"os"
	"time"

	"github.com/aratnitsyn/tictactoe/services/matchmaking/internal/queue"
	"github.com/jackc/pgx/v5/pgxpool"
)

func main() {
	id := flag.String("operation", "", "persisted operation UUID")
	flag.Parse()
	if *id == "" || os.Getenv("PG_DSN") == "" || os.Getenv("GAME_URL") == "" || len(os.Getenv("GAME_SERVICE_TOKEN")) < 32 {
		slog.Error("operation and environment are required")
		os.Exit(1)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	pool, err := pgxpool.New(ctx, os.Getenv("PG_DSN"))
	if err != nil {
		slog.Error("invalid database configuration")
		os.Exit(1)
	}
	defer pool.Close()
	service := queue.Service{DB: pool, Game: queue.HTTPGame{URL: os.Getenv("GAME_URL"), Token: os.Getenv("GAME_SERVICE_TOKEN"), Client: &http.Client{Timeout: 5 * time.Second}}}
	response, err := service.Resume(ctx, *id)
	if err != nil {
		slog.Error("operation remains pending", "operationId", *id)
		os.Exit(1)
	}
	slog.Info("persisted operation resolved", "operationId", *id, "status", response.Status)
}
