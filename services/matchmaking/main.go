package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/aratnitsyn/tictactoe/services/matchmaking/internal/outbox"
	"github.com/aratnitsyn/tictactoe/services/matchmaking/internal/queue"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/nats-io/nats.go"
)

func main() {
	port := required("PORT")
	pool, err := pgxpool.New(context.Background(), required("PG_DSN"))
	if err != nil {
		panic("invalid PostgreSQL configuration")
	}
	defer pool.Close()
	if err = pool.Ping(context.Background()); err != nil {
		panic("PostgreSQL unavailable")
	}
	token := required("MATCHMAKING_SERVICE_TOKEN")
	if len(token) < 32 {
		panic("service token too short")
	}
	service := &queue.Service{DB: pool, Game: queue.HTTPGame{URL: required("GAME_URL"), Token: required("GAME_SERVICE_TOKEN"), Client: &http.Client{Timeout: 5 * time.Second}}}
	nc, err := nats.Connect(required("NATS_URL"), nats.UserInfo(required("NATS_USER"), required("NATS_PASSWORD")))
	if err != nil {
		panic("NATS unavailable")
	}
	defer nc.Close()
	js, err := nc.JetStream()
	if err != nil {
		panic("JetStream unavailable")
	}
	subscriptions, deliveries, err := service.Subscribe(js)
	if err != nil {
		panic("queue consumers unavailable")
	}

	publisher := outbox.Worker{DB: pool, Publisher: outbox.JetStreamPublisher{JS: js}, Owner: queue.UUID()}
	delay, err := strconv.Atoi(required("MATCHMAKING_RETRY_MS"))
	if err != nil || delay < 1 {
		panic("invalid retry interval")
	}
	server := &http.Server{
		Addr:              ":" + port,
		Handler:           service.Handler(token),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		ticker := time.NewTicker(time.Duration(delay) * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if err := service.Reconcile(ctx); err != nil {
					slog.Warn("queue reconciliation pending")
				}
				id, err := service.Select(ctx)
				if err != nil {
					slog.Warn("queue selection failed")
				} else if id != "" {
					if _, err = service.Resume(ctx, id); err != nil {
						slog.Warn("pair handoff pending", "operationId", id)
					}
				}
				for i := 0; i < 100; i++ {
					ok, err := publisher.PublishOne(ctx)
					if err != nil {
						slog.Warn("queue outbox pending")
						break
					}
					if !ok {
						break
					}
				}
			}
		}
	}()
	for index, subscription := range subscriptions {
		go func() {
			for ctx.Err() == nil {
				messages, fetchErr := subscription.Fetch(16, nats.MaxWait(time.Second))
				if fetchErr != nil && !errors.Is(fetchErr, nats.ErrTimeout) {
					slog.Warn("queue event fetch failed")
					select {
					case <-ctx.Done():
						return
					case <-time.After(250 * time.Millisecond):
					}
					continue
				}
				for _, msg := range messages {
					if err := deliveries[index].Handle(ctx, msg); err != nil {
						slog.Warn("queue event delivery pending")
					}
				}
			}
		}()
	}

	failures := make(chan error, 1)
	go func() { failures <- server.ListenAndServe() }()
	select {
	case err := <-failures:
		if !errors.Is(err, http.ErrServerClosed) {
			slog.Error("server failed", "error", err)
			os.Exit(1)
		}
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdownCtx); err != nil {
			slog.Error("shutdown failed", "error", err)
			os.Exit(1)
		}
	}
}

func required(key string) string {
	value := os.Getenv(key)
	if value == "" {
		panic(fmt.Sprintf("%s is required", key))
	}
	return value
}
