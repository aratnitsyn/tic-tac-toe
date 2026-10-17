package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"

	"github.com/nats-io/nats.go"
)

func main() {
	path := flag.String("config", "../../infra/local/jetstream.json", "JetStream stream configuration")
	flag.Parse()
	if err := run(*path); err != nil {
		log.Fatal(err)
	}
}

func run(path string) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer func() { _ = file.Close() }()
	var configs []nats.StreamConfig
	decoder := json.NewDecoder(file)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&configs); err != nil {
		return err
	}
	password := os.Getenv("NATS_PASSWORD")
	if password == "" {
		return fmt.Errorf("NATS_PASSWORD is required")
	}
	nc, err := nats.Connect(os.Getenv("NATS_URL"), nats.UserInfo(os.Getenv("NATS_USER"), password))
	if err != nil {
		return err
	}
	defer nc.Close()
	js, err := nc.JetStream()
	if err != nil {
		return err
	}
	for _, config := range configs {
		if _, err := js.AddStream(&config); err != nil {
			return err
		}
		fmt.Println("Stream ready:", config.Name)
	}
	return nil
}
