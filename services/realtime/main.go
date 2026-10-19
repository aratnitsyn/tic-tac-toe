package main

import (
	"context"
	"errors"
	"github.com/aratnitsyn/tictactoe/services/realtime/internal/socket"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/nats-io/nats.go"
	"github.com/redis/go-redis/v9"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"regexp"
	"strings"
	"syscall"
	"time"
)

func main() {
	port := required("PORT")
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	pool, err := pgxpool.New(ctx, required("PG_DSN"))
	if err != nil {
		panic("invalid database configuration")
	}
	defer pool.Close()
	if err = pool.Ping(ctx); err != nil {
		panic("database unavailable")
	}
	rc := redis.NewClient(&redis.Options{Addr: required("REDIS_ADDRESS"), Password: required("REDIS_PASSWORD")})
	defer func() { _ = rc.Close() }()
	nc, err := nats.Connect(required("NATS_URL"), nats.UserInfo(required("NATS_USER"), required("NATS_PASSWORD")))
	if err != nil {
		panic("NATS unavailable")
	}
	defer nc.Close()
	owner := socket.Ownership{DB: pool, Redis: rc, Pod: required("REALTIME_POD_ID")}
	if !regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`).MatchString(owner.Pod) {
		panic("invalid pod identity")
	}
	if err = owner.Ready(ctx); err != nil {
		panic("Redis unavailable")
	}
	origins := map[string]bool{}
	for _, origin := range strings.Split(required("WEB_ORIGINS"), ",") {
		origins[origin] = true
	}
	realtime := &socket.Server{Backend: socket.Backend{Matchmaking: required("MATCHMAKING_URL"), MatchmakingToken: required("MATCHMAKING_SERVICE_TOKEN"), Auth: required("AUTH_URL"), Game: required("GAME_URL"), AuthToken: required("AUTH_SERVICE_TOKEN"), GameToken: required("GAME_SERVICE_TOKEN"), Client: &http.Client{Timeout: 3 * time.Second}}, Ownership: owner, NATS: nc, Pod: owner.Pod, Origins: origins}
	backend := realtime.Backend.(socket.Backend)
	backend.Batch = socket.NewRenewal(ctx, backend)
	realtime.Backend = backend
	role := required("REALTIME_ROLE")
	if role != "socket" && role != "dispatcher" && role != "all" {
		panic("invalid realtime role")
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte(`{"status":"UP"}`)) })
	if role != "dispatcher" {
		sub, subscribeErr := realtime.Subscribe()
		if subscribeErr != nil {
			panic("pod subscription unavailable")
		}
		defer func() { _ = sub.Unsubscribe() }()
		mux.Handle("/", realtime.Handler())
	}
	if role != "socket" {
		go func() {
			if err := socket.Dispatch(ctx, pool, nc, owner); err != nil && ctx.Err() == nil {
				slog.Error("dispatcher stopped")
				stop()
			}
		}()
	}
	server := &http.Server{
		Addr:              ":" + port,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       60 * time.Second,
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

func required(name string) string {
	value := os.Getenv(name)
	if value == "" {
		panic("missing " + name)
	}
	return value
}
