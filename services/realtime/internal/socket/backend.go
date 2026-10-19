// Package socket owns authenticated connections and bounded event delivery.
package socket

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"
)

type Identity struct {
	Active  bool   `json:"active"`
	User    string `json:"userId"`
	Session string `json:"sessionId"`
}
type Backend struct {
	Batch                                                           *Renewal
	Auth, Game, AuthToken, GameToken, Matchmaking, MatchmakingToken string
	Client                                                          *http.Client
}

func ID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	b[6] = b[6]&15 | 64
	b[8] = b[8]&63 | 128
	s := hex.EncodeToString(b[:])
	return s[:8] + "-" + s[8:12] + "-" + s[12:16] + "-" + s[16:20] + "-" + s[20:]
}
func (b Backend) call(ctx context.Context, auth bool, method, path, user string, body, target any) error {
	base, token := b.Game, b.GameToken
	if auth {
		base, token = b.Auth, b.AuthToken
	}
	data, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, method, base+path, bytes.NewReader(data))
	if err != nil {
		return err
	}
	req.Header.Set("X-Service-Token", token)
	req.Header.Set("X-User-Id", user)
	req.Header.Set("Content-Type", "application/json")
	if v, ok := body.(map[string]any); ok {
		if id, ok := v["operationId"].(string); ok {
			req.Header.Set("Idempotency-Key", id)
		}
	}
	resp, err := b.Client.Do(req)
	if err != nil {
		return errors.New("authority unavailable")
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("authority status %d", resp.StatusCode)
	}
	return json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(target)
}
func (b Backend) Consume(ctx context.Context, ticket string) (Identity, error) {
	var v Identity
	err := b.call(ctx, true, "POST", "/internal/v1/ws-tickets/consume", "", map[string]any{"ticket": ticket}, &v)
	if err == nil && !v.Active {
		err = errors.New("inactive ticket")
	}
	return v, err
}
func (b Backend) Touch(ctx context.Context, id Identity) error {
	var v Identity
	err := b.call(ctx, true, "POST", "/internal/v1/sessions/touch", "", map[string]any{"userId": id.User, "sessionId": id.Session}, &v)
	if err == nil && !v.Active {
		err = errors.New("inactive session")
	}
	return err
}
func (b Backend) Match(ctx context.Context, user, match string) (json.RawMessage, error) {
	var v json.RawMessage
	err := b.call(ctx, false, "GET", "/api/v1/matches/"+match, user, nil, &v)
	return v, err
}
func (b Backend) Participation(ctx context.Context, user string) (string, error) {
	var v struct {
		Data struct{ State, ResourceID string }
	}
	err := b.call(ctx, false, "GET", "/api/v1/participation", user, nil, &v)
	if v.Data.State != "IN_MATCH" {
		return "", err
	}
	return v.Data.ResourceID, err
}
func (b Backend) Observe(ctx context.Context, user, match string, generation int64, connected bool) error {
	var v json.RawMessage
	return b.call(ctx, false, "POST", "/internal/v1/connections/observe", user, map[string]any{"operationId": ID(), "userId": user, "matchId": match, "generation": generation, "connected": connected}, &v)
}
func (b Backend) Renew(ctx context.Context, user, match string, generation int64) error {
	if b.Batch != nil {
		return b.Batch.Renew(ctx, user, match, generation)
	}
	var v struct{ Connections []json.RawMessage }
	err := b.call(ctx, false, "POST", "/internal/v1/connections/renew", user, map[string]any{"operationId": ID(), "connections": []any{map[string]any{"userId": user, "matchId": match, "generation": generation}}}, &v)
	if err == nil && len(v.Connections) != 1 {
		err = errors.New("lease rejected")
	}
	return err
}
func stamp() string { return time.Now().UTC().Format(time.RFC3339Nano) }

func (b Backend) Availability(ctx context.Context, user string, generation int64, connected bool) error {
	adapter := b
	adapter.Game = b.Matchmaking
	adapter.GameToken = b.MatchmakingToken
	var v json.RawMessage
	return adapter.call(ctx, false, "POST", "/internal/v1/availability", user, map[string]any{"userId": user, "generation": generation, "connected": connected}, &v)
}
