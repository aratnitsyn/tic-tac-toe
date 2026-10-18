package queue

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
)

type HTTPGame struct {
	URL, Token string
	Client     *http.Client
}

func (g HTTPGame) request(ctx context.Context, method, path string, body []byte, op Operation, user string, target any) (int, error) {
	req, err := http.NewRequestWithContext(ctx, method, g.URL+path, bytes.NewReader(body))
	if err != nil {
		return 0, err
	}
	req.Header.Set("X-Service-Token", g.Token)
	req.Header.Set("Content-Type", "application/json")
	if op.ID != "" {
		req.Header.Set("Idempotency-Key", op.ID)
		req.Header.Set("X-Correlation-Id", op.Correlation)
	}
	if user != "" {
		req.Header.Set("X-User-Id", user)
	}
	resp, err := g.Client.Do(req)
	if err != nil {
		return 0, err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode >= 200 && resp.StatusCode < 300 && target != nil {
		err = json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(target)
	} else {
		_, err = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<20))
	}
	return resp.StatusCode, err
}
func (g HTTPGame) Resolve(ctx context.Context, op Operation) (Outcome, error) {
	var v Outcome
	status, err := g.request(ctx, "GET", "/internal/v1/operations/"+op.ID, nil, op, "", &v)
	if err != nil {
		return v, err
	}
	if status == 200 {
		return v, nil
	}
	if status != 404 {
		return v, fmt.Errorf("Game operation read status %d", status)
	}
	path := "/internal/v1/participation/claim"
	if op.Kind == "PAIR" {
		path = "/internal/v1/matches/assign-pair"
	}
	status, err = g.request(ctx, "POST", path, op.GamePayload, op, "", nil)
	if err != nil {
		return v, err
	}
	if status != 200 && status != 409 {
		return v, fmt.Errorf("Game command status %d", status)
	}
	status, err = g.request(ctx, "GET", "/internal/v1/operations/"+op.ID, nil, op, "", &v)
	if err == nil && status != 200 {
		err = fmt.Errorf("Game result status %d", status)
	}
	return v, err
}
func (g HTTPGame) Participation(ctx context.Context, user string) (Participation, error) {
	var v struct {
		Data Participation `json:"data"`
	}
	status, err := g.request(ctx, "GET", "/api/v1/participation", nil, Operation{}, user, &v)
	if err == nil && status != 200 {
		err = fmt.Errorf("Game snapshot status %d", status)
	}
	return v.Data, err
}
