// Command check-realtime exercises local internal WS behind the future ingress.
// It logs no credentials and only kills the socket process supplied by its launcher.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/aratnitsyn/tictactoe/services/realtime/internal/socket"
	"github.com/aratnitsyn/tictactoe/services/realtime/internal/syncclient"
	"io"
	"net/http"
	"os"
	"strconv"
	"time"
)

type Player struct{ User, Token string }
type probe struct {
	base string
	http *http.Client
}

func (p probe) api(player Player, path string, body any) (syncclient.Snapshot, error) {
	var out syncclient.Snapshot
	data, err := json.Marshal(body)
	if err != nil {
		return out, err
	}
	method := "GET"
	if body != nil {
		method = "POST"
	}
	req, err := http.NewRequest(method, p.base+"/api/v1/"+path, bytes.NewReader(data))
	if err != nil {
		return out, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Client-Type", "NATIVE")
	req.Header.Set("Idempotency-Key", socket.ID())
	if player.Token != "" {
		req.Header.Set("Authorization", "Bearer "+player.Token)
	}
	response, err := p.http.Do(req)
	if err != nil {
		return out, err
	}
	defer func() { _ = response.Body.Close() }()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return out, fmt.Errorf("%s status %d", path, response.StatusCode)
	}
	err = json.NewDecoder(io.LimitReader(response.Body, 1<<20)).Decode(&out)
	return out, err
}
func must(err error) {
	if err != nil {
		panic(err)
	}
}
func fields(v syncclient.Snapshot) map[string]any {
	var data map[string]any
	must(json.Unmarshal(v.Data, &data))
	return data
}
func wait(check func() bool) {
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		if check() {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	panic("runtime condition not reached")
}
func main() {
	p := probe{os.Getenv("PROBE_GATEWAY"), &http.Client{Timeout: 3 * time.Second}}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	players := []Player{}
	for i := 0; i < 2; i++ {
		credentials := map[string]any{"email": "realtime-" + socket.ID() + "@example.com", "password": "Aa1!" + socket.ID()[:8], "clientType": "NATIVE"}
		account, err := p.api(Player{}, "auth/register", credentials)
		must(err)
		login, err := p.api(Player{}, "auth/login", credentials)
		must(err)
		players = append(players, Player{fields(account)["userId"].(string), fields(login)["token"].(string)})
	}
	clients := []*syncclient.Client{}
	beat := make(chan struct{}, 1)
	match := ""
	for i, player := range players {
		player := player
		c := &syncclient.Client{Keys: []syncclient.Key{{Kind: "PARTICIPATION", ID: player.User}}, Foreground: make(chan struct{}, 1)}
		if i == 0 {
			c.OnHeartbeat = func() {
				select {
				case beat <- struct{}{}:
				default:
				}
			}
		}
		c.Fetch = func(_ context.Context, key syncclient.Key) (syncclient.Snapshot, error) {
			path := "participation"
			if key.Kind == "MATCH" {
				path = "matches/" + key.ID
			}
			if key.Kind == "GAME" {
				path = "games/" + key.ID
			}
			snapshot, err := p.api(player, path, nil)

			return snapshot, err
		}
		clients = append(clients, c)
		url := os.Getenv("PROBE_SOCKET_" + strconv.Itoa(i))
		go func() {
			_ = c.Serve(ctx, url, func(context.Context) (string, error) {
				ticket, err := p.api(player, "auth/ws-ticket", map[string]any{})
				if err != nil {
					return "", err
				}
				return fields(ticket)["ticket"].(string), nil
			})
		}()

		wait(func() bool { return c.Current(syncclient.Key{Kind: "PARTICIPATION", ID: player.User}).Data != nil })
	}
	state := func(player Player) syncclient.Snapshot {
		v, err := p.api(player, "participation", nil)
		must(err)
		return v
	}
	room, err := p.api(players[0], "lobbies", map[string]any{"expectedVersion": state(players[0]).Version})
	must(err)
	joined, err := p.api(players[1], "lobbies/join", map[string]any{"expectedVersion": state(players[1]).Version, "code": fields(room)["code"]})
	must(err)
	match = fields(joined)["resourceId"].(string)
	for i, c := range clients {
		c.SetKeys([]syncclient.Key{{Kind: "PARTICIPATION", ID: players[i].User}, {Kind: "MATCH", ID: match}, {Kind: "GAME", ID: match}})
		must(c.Subscribe(match))
	}
	for _, player := range players {
		snapshot, err := p.api(player, "matches/"+match, nil)
		must(err)
		_, err = p.api(player, "matches/"+match+"/ready", map[string]any{"expectedVersion": snapshot.Version})
		must(err)
	}
	wait(func() bool {
		v, err := p.api(players[0], "games/"+match, nil)
		return err == nil && fields(v)["state"] == "X_TURN"
	})
	// Both clients on different pods must have converged through subscription/snapshot.
	for _, c := range clients {
		c.Foreground <- struct{}{}
	}
	wait(func() bool {
		for _, c := range clients {
			v := c.Current(syncclient.Key{Kind: "GAME", ID: match})
			if v.Data == nil || fields(v)["state"] != "X_TURN" {
				return false
			}
		}
		return true
	})
	// Age the lease before a confirmed move so its 6s silence deadline
	// precedes the newly started 5s turn deadline after SIGKILL.
	select {
	case <-beat:
	default:
	}
	<-beat
	time.Sleep(1500 * time.Millisecond)
	game, err := p.api(players[0], "games/"+match, nil)
	must(err)
	mover := players[0]
	if fields(game)["xPlayerId"] != mover.User {
		mover = players[1]
	}
	_, err = p.api(mover, "games/"+match+"/moves", map[string]any{"expectedVersion": game.Version, "cell": 0})
	must(err)
	pid, err := strconv.Atoi(os.Getenv("PROBE_KILL_SOCKET_PID"))
	must(err)
	process, err := os.FindProcess(pid)
	must(err)
	must(process.Kill())
	wait(func() bool {
		v, err := p.api(players[1], "games/"+match, nil)
		return err == nil && fields(v)["state"] == "PAUSED"
	})
	// Persisted Game lease detected SIGKILL without a close frame.
	paused, err := p.api(players[1], "games/"+match, nil)
	must(err)
	must(json.NewEncoder(os.Stdout).Encode(map[string]any{"phase": "paused", "matchId": match}))
	// Launcher restarts Game, then permits the persisted-state check.
	var signal string
	_, err = fmt.Fscanln(os.Stdin, &signal)
	must(err)
	if signal != "continue" {
		must(errors.New("invalid launcher signal"))
	}
	restored, err := p.api(players[1], "games/"+match, nil)
	must(err)
	if fields(restored)["state"] != "PAUSED" || restored.Version != paused.Version {
		panic("Game restart changed pause")
	}
	must(json.NewEncoder(os.Stdout).Encode(map[string]any{"phase": "restored"}))
	_, err = fmt.Fscanln(os.Stdin, &signal)
	must(err)
	if signal != "continue" {
		panic("invalid launcher signal")
	}
	// The launcher starts a replacement pod on the same ingress upstream URL.
	// Reconnect restores the saved turn; its timeout ends the game normally.
	wait(func() bool {
		for _, c := range clients {
			v := c.Current(syncclient.Key{Kind: "GAME", ID: match})
			if v.Data == nil || fields(v)["state"] != "FINISHED" {
				return false
			}
		}
		return true
	})

	for _, player := range players {
		if fields(state(player))["state"] != "MENU" {
			panic("participation not released")
		}
	}
	fmt.Println("Realtime runtime passed: two pods, synchronization, SIGKILL lease, Game restart, replacement pod, terminal resync")
}
