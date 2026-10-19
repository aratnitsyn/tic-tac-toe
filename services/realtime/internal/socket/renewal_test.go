package socket

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestRenewalConfirmsOnlyAcceptedLeaseAndStopsOnShutdown(t *testing.T) {
	seen := make(chan Lease, 2)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var input struct{ Connections []Lease }
		if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
			t.Error(err)
			return
		}
		for _, lease := range input.Connections {
			seen <- lease
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"connections":[]}`))
	}))
	defer server.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	batch := NewRenewal(ctx, Backend{Game: server.URL, Client: server.Client()})
	call, stop := context.WithTimeout(context.Background(), time.Second)
	defer stop()
	if err := batch.Renew(call, "user", "match", 12); err == nil {
		t.Fatal("unconfirmed lease advertised connected")
	}
	if v := <-seen; v.Generation != 12 || v.User != "user" {
		t.Fatal(v)
	}
	cancel()
	if err := batch.Renew(call, "user", "match", 12); err == nil {
		t.Fatal("renew accepted after shutdown")
	}
}
