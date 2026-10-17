package inbox

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"regexp"
	"strings"

	"github.com/nats-io/nats.go"
)

// Replay preserves the original eventId; a stable operationID also deduplicates the operator retry.
func Replay(ctx context.Context, js nats.JetStreamContext, parkingData []byte, operationID, allowedPrefix string) (*nats.PubAck, error) {
	if !regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`).MatchString(operationID) {
		return nil, errors.New("replay operation ID must be UUID")
	}
	var item struct {
		Consumer        string `json:"consumer"`
		OriginalSubject string `json:"originalSubject"`
		PayloadBase64   string `json:"payloadBase64"`
	}
	if err := json.Unmarshal(parkingData, &item); err != nil {
		return nil, err
	}
	if allowedPrefix == "" || !strings.HasPrefix(item.OriginalSubject, allowedPrefix) {
		return nil, errors.New("original subject outside allowed prefix")
	}
	body, err := base64.StdEncoding.DecodeString(item.PayloadBase64)
	if err != nil {
		return nil, err
	}
	if !json.Valid(body) {
		return nil, errors.New("malformed original event needs repair, not blind replay")
	}
	return js.Publish(item.OriginalSubject, body, nats.MsgId("parking-replay/"+operationID), nats.Context(ctx))
}
