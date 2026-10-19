package queue

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/aratnitsyn/tictactoe/services/matchmaking/internal/command"
)

func (s *Service) Handler(token string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"UP"}`))
	})
	queue := func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Service-Token")), []byte(token)) != 1 {
			writeError(w, fail("UNAUTHENTICATED", 401))
			return
		}
		user := strings.ToLower(r.Header.Get("X-User-Id"))
		if !validID(user) {
			writeError(w, fail("UNAUTHENTICATED", 401))
			return
		}
		var response command.Response
		var err error
		if r.Method == "GET" {
			response, err = s.Snapshot(r.Context(), user)
		} else {
			key := strings.ToLower(r.Header.Get("Idempotency-Key"))
			correlation := strings.ToLower(r.Header.Get("X-Correlation-Id"))
			if correlation == "" {
				correlation = key
			}
			body, readErr := io.ReadAll(http.MaxBytesReader(w, r.Body, 4096))
			if readErr != nil {
				writeError(w, fail("VALIDATION_ERROR", 400))
				return
			}
			kind := "JOIN"
			if strings.HasSuffix(r.URL.Path, "/cancel") {
				kind = "CANCEL"
			}
			response, err = s.Execute(r.Context(), user, kind, key, correlation, body)
		}
		if err != nil {
			writeError(w, err)
			return
		}
		w.WriteHeader(response.Status)
		_, _ = w.Write(response.Body)
	}
	mux.HandleFunc("POST /internal/v1/availability", func(w http.ResponseWriter, r *http.Request) {
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Service-Token")), []byte(token)) != 1 {
			writeError(w, fail("UNAUTHENTICATED", 401))
			return
		}
		var v struct {
			User       string `json:"userId"`
			Generation *int64 `json:"generation"`
			Connected  *bool  `json:"connected"`
		}
		decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096))
		decoder.DisallowUnknownFields()
		if decoder.Decode(&v) != nil || v.Generation == nil || v.Connected == nil || decoder.Decode(new(any)) != io.EOF {
			writeError(w, fail("VALIDATION_ERROR", 400))
			return
		}
		if err := s.ObserveAvailability(r.Context(), v.User, *v.Generation, *v.Connected); err != nil {
			writeError(w, err)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"APPLIED"}`))
	})
	mux.HandleFunc("GET /api/v1/matchmaking/queue", queue)
	mux.HandleFunc("POST /api/v1/matchmaking/queue", queue)
	mux.HandleFunc("POST /api/v1/matchmaking/queue/cancel", queue)
	return mux
}
func writeError(w http.ResponseWriter, err error) {
	status := 503
	code := "SERVICE_UNAVAILABLE"
	var f *Failure
	if errors.As(err, &f) {
		status = f.Status
		code = f.Code
	} else if errors.Is(err, command.ErrConflict) {
		status = 409
		code = "IDEMPOTENCY_CONFLICT"
	}
	w.WriteHeader(status)
	_, _ = w.Write(encode(map[string]any{"code": code, "message": code, "correlationId": UUID(), "serverTime": time.Now().UTC().Format(time.RFC3339Nano)}))
}
