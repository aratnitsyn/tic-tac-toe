package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"time"

	"github.com/aratnitsyn/tictactoe/services/matchmaking/internal/inbox"
	"github.com/nats-io/nats.go"
)

func main() {
	sequence := flag.Uint64("sequence", 0, "PARKING stream sequence")
	operation := flag.String("operation-id", "", "Stable replay command UUID")
	confirm := flag.Bool("confirm", false, "Explicitly publish the original event again")
	flag.Parse()
	if !*confirm || *sequence == 0 {
		log.Fatal("--sequence, --operation-id and --confirm required")
	}
	if err := run(*sequence, *operation); err != nil {
		log.Fatal(err)
	}
}

func run(sequence uint64, operation string) error {
	if os.Getenv("NATS_PASSWORD") == "" {
		return fmt.Errorf("NATS_PASSWORD required")
	}
	nc, err := nats.Connect(os.Getenv("NATS_URL"), nats.UserInfo(os.Getenv("NATS_USER"), os.Getenv("NATS_PASSWORD")))
	if err != nil {
		return err
	}
	defer nc.Close()
	js, err := nc.JetStream()
	if err != nil {
		return err
	}
	parked, err := js.GetMsg("PARKING", sequence)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ack, err := inbox.Replay(ctx, js, parked.Data, operation, "events.")
	if err != nil {
		return err
	}
	fmt.Printf("Replay accepted: stream=%s sequence=%d duplicate=%t\n", ack.Stream, ack.Sequence, ack.Duplicate)
	return nil
}
