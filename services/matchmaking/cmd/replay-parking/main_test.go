package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/nats-io/nats.go"
)

// Exercise the operator's Python entry point with our own fixture, never existing parked events.
func TestReplayScriptPreservesPayloadAndDeduplicatesRetry(t *testing.T) {
	if os.Getenv("NATS_PASSWORD") == "" {
		t.Skip("NATS_PASSWORD required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	nc, err := nats.Connect(os.Getenv("NATS_URL"), nats.UserInfo(os.Getenv("NATS_USER"), os.Getenv("NATS_PASSWORD")))
	if err != nil {
		t.Fatal(err)
	}
	defer nc.Close()
	js, err := nc.JetStream()
	if err != nil {
		t.Fatal(err)
	}
	// UUID-format operation also makes a unique subject without a new dependency.
	operation := fmt.Sprintf("%08x-0000-4000-8000-%012x", uint32(time.Now().Unix()), uint64(time.Now().UnixNano())&0xffffffffffff)
	subject := "events.AuditReplay_" + strings.ReplaceAll(operation, "-", "")
	original := []byte(`{"eventId":"` + operation + `","type":"AuditReplay"}`)
	parking, err := json.Marshal(map[string]string{"consumer": "audit", "originalSubject": subject, "payloadBase64": base64.StdEncoding.EncodeToString(original)})
	if err != nil {
		t.Fatal(err)
	}
	parked, err := js.Publish("parking.audit_"+operation, parking)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := js.DeleteMsg("PARKING", parked.Sequence); err != nil {
			t.Error(err)
		}
	}()
	script, err := filepath.Abs("../../../../scripts/replay-parking.py")
	if err != nil {
		t.Fatal(err)
	}
	args := []string{script, fmt.Sprint(parked.Sequence), operation}
	denied := exec.CommandContext(ctx, "python3", args...)
	if output, err := denied.CombinedOutput(); err == nil || !strings.Contains(string(output), "--confirm is required") {
		t.Fatalf("unconfirmed replay was not rejected: %s (%v)", output, err)
	}
	for attempt := range 2 {
		command := exec.CommandContext(ctx, "python3", append(args, "--confirm")...)
		output, err := command.CombinedOutput()
		if err != nil {
			t.Fatalf("replay failed: %s (%v)", output, err)
		}
		want := fmt.Sprintf("duplicate=%t", attempt == 1)
		if !strings.Contains(string(output), want) {
			t.Fatalf("expected %s, got %s", want, output)
		}
		message, err := js.GetLastMsg("DOMAIN_EVENTS", subject)
		if err != nil {
			t.Fatal(err)
		}
		if attempt == 0 {
			defer func() {
				if err := js.DeleteMsg("DOMAIN_EVENTS", message.Sequence); err != nil {
					t.Error(err)
				}
			}()
		}
		if string(message.Data) != string(original) {
			t.Fatal("replay changed original bytes or event identity")
		}
	}
}
