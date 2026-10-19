package socket

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"github.com/gorilla/websocket"
	"github.com/nats-io/nats.go"
	"io"
	"net/http"
	"regexp"
	"sync"
	"time"
)

var uuidPattern = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

type Authority interface {
	Availability(context.Context, string, int64, bool) error
	Consume(context.Context, string) (Identity, error)
	Touch(context.Context, Identity) error
	Match(context.Context, string, string) (json.RawMessage, error)
	Participation(context.Context, string) (string, error)
	Observe(context.Context, string, string, int64, bool) error
	Renew(context.Context, string, string, int64) error
}
type OwnerStore interface {
	Claim(context.Context, Identity) (Owner, error)
	Renew(context.Context, string, Owner) error
	Release(context.Context, string, Owner) error
	Lookup(context.Context, string) (Owner, error)
}
type Server struct {
	Backend   Authority
	Ownership OwnerStore
	NATS      *nats.Conn
	Pod       string
	Origins   map[string]bool
	mu        sync.Mutex
	clients   map[string]*client
}
type client struct {
	id     Identity
	owner  Owner
	events chan Routed
	stop   chan struct{}
}
type Event struct {
	ID         string   `json:"eventId"`
	Aggregate  string   `json:"aggregateId"`
	Kind       string   `json:"aggregateType"`
	Version    int64    `json:"aggregateVersion"`
	Recipients []string `json:"recipientIds"`
}
type Routed struct {
	User       string          `json:"user"`
	Generation int64           `json:"generation"`
	Data       json.RawMessage `json:"data"`
}
type frame struct {
	Type      string `json:"type"`
	Ticket    string `json:"ticket,omitempty"`
	Match     string `json:"matchId,omitempty"`
	Request   string `json:"requestId,omitempty"`
	Event     string `json:"eventId,omitempty"`
	Aggregate string `json:"aggregateId,omitempty"`
	Version   int64  `json:"aggregateVersion,omitempty"`
}

func (s *Server) Route(r Routed) {
	s.mu.Lock()
	defer s.mu.Unlock()
	c := s.clients[r.User]
	if c == nil || c.owner.Generation != r.Generation {
		return
	}
	select {
	case c.events <- r:
	default:
		select {
		case <-c.stop:
		default:
			close(c.stop)
		}
	}
}
func (s *Server) Subscribe() (*nats.Subscription, error) {
	return s.NATS.Subscribe("realtime.pod."+s.Pod, func(m *nats.Msg) {
		var r Routed
		if json.Unmarshal(m.Data, &r) == nil {
			s.Route(r)
		}
	})
}
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte(`{"status":"UP"}`)) })
	mux.HandleFunc("GET /wss", s.connect)
	return mux
}
func (s *Server) connect(w http.ResponseWriter, r *http.Request) {
	if r.URL.RawQuery != "" || r.Header.Get("Authorization") != "" {
		http.Error(w, "invalid transport credentials", http.StatusBadRequest)
		return
	}
	origin := r.Header.Get("Origin")
	if origin != "" && !s.Origins[origin] {
		http.Error(w, "forbidden origin", http.StatusForbidden)
		return
	}
	up := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }, ReadBufferSize: 4096, WriteBufferSize: 4096}
	conn, err := up.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer func() { _ = conn.Close() }()
	conn.SetReadLimit(4096)
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	var first frame
	if err = read(conn, &first); err != nil || first.Type != "auth" || first.Ticket == "" || first.Match != "" || first.Request != "" || first.Event != "" {
		return
	}
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	id, err := s.Backend.Consume(ctx, first.Ticket)
	if err != nil || !id.Active || !uuidPattern.MatchString(id.User) || !uuidPattern.MatchString(id.Session) {
		return
	}
	owner, err := s.Ownership.Claim(ctx, id)
	if err != nil {
		return
	}
	c := &client{id: id, owner: owner, events: make(chan Routed, 64), stop: make(chan struct{})}
	s.mu.Lock()
	if s.clients == nil {
		s.clients = map[string]*client{}
	}
	old := s.clients[id.User]
	if old != nil {
		select {
		case <-old.stop:
		default:
			close(old.stop)
		}
	}
	s.clients[id.User] = c
	s.mu.Unlock()
	current := ""
	defer func() {
		s.mu.Lock()
		if s.clients[id.User] == c {
			delete(s.clients, id.User)
		}
		s.mu.Unlock()
		cleanup, stop := context.WithTimeout(context.Background(), 3*time.Second)
		defer stop()
		replacement, lookupErr := s.Ownership.Lookup(cleanup, id.User)
		superseded := lookupErr == nil && replacement.Generation > owner.Generation
		if !superseded {
			_ = s.Backend.Availability(cleanup, id.User, owner.Generation, false)
			if current != "" {
				_ = s.Backend.Observe(cleanup, id.User, current, owner.Generation, false)
			}
		}

		_ = s.Ownership.Release(cleanup, id.User, owner)
	}()
	refresh := func() error {
		if err := s.Backend.Touch(ctx, id); err != nil {
			return err
		}
		if err := s.Ownership.Renew(ctx, id.User, owner); err != nil {
			return err
		}
		if err := s.Backend.Availability(ctx, id.User, owner.Generation, true); err != nil {
			return err
		}
		match, err := s.Backend.Participation(ctx, id.User)
		if err != nil {
			return err
		}
		if match != "" {
			if match != current {
				if err = s.Backend.Observe(ctx, id.User, match, owner.Generation, true); err != nil {
					return err
				}
			}
			if err = s.Backend.Renew(ctx, id.User, match, owner.Generation); err != nil {
				return err
			}
		}
		// Keep renewing a cancelled match until this socket moves to another one:
		// its late disconnect may still revoke an unconsumed automatic return.
		if match == "" && current != "" {
			snapshot, readErr := s.Backend.Match(ctx, id.User, current)
			if readErr != nil {
				return readErr
			}
			var terminal struct{ Data struct{ State string } }
			if err = json.Unmarshal(snapshot, &terminal); err != nil {
				return err
			}
			if terminal.Data.State == "FINISHED" {
				current = ""
			} else if err = s.Backend.Renew(ctx, id.User, current, owner.Generation); err != nil {
				return err
			}
		}

		if match != "" {
			current = match
		}
		return nil
	}
	send := func(value any) error {
		_ = conn.SetWriteDeadline(time.Now().Add(2 * time.Second))
		return conn.WriteJSON(value)
	}
	if refresh() != nil {
		return
	}
	if send(map[string]any{"type": "auth_ok", "connectionGeneration": owner.Generation, "serverTime": stamp()}) != nil {
		return
	}
	_ = conn.SetReadDeadline(time.Now().Add(6 * time.Second))
	incoming := make(chan frame)
	failure := make(chan error, 1)
	go func() {
		for {
			var f frame
			if err := read(conn, &f); err != nil {
				failure <- err
				return
			}
			select {
			case incoming <- f:
			case <-ctx.Done():
				return
			}
		}
	}()
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	subscribed := map[string]bool{}
	pending := map[string]struct {
		aggregate string
		version   int64
		sent      time.Time
	}{}
	resync := func(aggregate string) error {
		return send(map[string]any{"type": "resync_required", "aggregateId": aggregate, "serverTime": stamp()})
	}
	for {
		select {
		case <-c.stop:
			return
		case <-failure:
			return
		case <-ctx.Done():
			return
		case f := <-incoming:
			if f.Type != "heartbeat" && s.Backend.Touch(ctx, id) != nil {
				return
			}
			switch f.Type {
			case "heartbeat":
				if !uuidPattern.MatchString(f.Request) || refresh() != nil {
					return
				}
				_ = conn.SetReadDeadline(time.Now().Add(6 * time.Second))
				if send(map[string]any{"type": "heartbeat_ack", "requestId": f.Request, "serverTime": stamp()}) != nil {
					return
				}
			case "subscribe":
				if !uuidPattern.MatchString(f.Match) {
					return
				}
				if _, err := s.Backend.Match(ctx, id.User, f.Match); err != nil {
					return
				}
				subscribed[f.Match] = true
				if resync(f.Match) != nil {
					return
				}
			case "ack":
				p, ok := pending[f.Event]
				if !uuidPattern.MatchString(f.Event) || !uuidPattern.MatchString(f.Aggregate) || f.Version < 0 {
					return
				}
				if !ok {
					continue
				}
				if p.aggregate == f.Aggregate && p.version == f.Version {
					delete(pending, f.Event)
				} else {
					return
				}
			default:
				return
			}
		case routed := <-c.events:
			if s.Backend.Touch(ctx, id) != nil {
				return
			}
			var e Event
			if json.Unmarshal(routed.Data, &e) != nil {
				return
			}
			allowed := false
			for _, u := range e.Recipients {
				if u == id.User {
					allowed = true
				}
			}
			if !allowed {
				continue
			}
			if (e.Kind == "MATCH" || e.Kind == "GAME") && !subscribed[e.Aggregate] {
				continue
			}
			if len(pending) >= 64 {
				if resync(e.Aggregate) != nil {
					return
				}
				clear(pending)
				continue
			}
			pending[e.ID] = struct {
				aggregate string
				version   int64
				sent      time.Time
			}{e.Aggregate, e.Version, time.Now()}
			if send(map[string]any{"type": "event", "event": routed.Data}) != nil {
				return
			}
		case now := <-ticker.C:
			if s.Ownership.Renew(ctx, id.User, owner) != nil {
				return
			}
			for key, p := range pending {
				if now.Sub(p.sent) >= 5*time.Second {
					if resync(p.aggregate) != nil {
						return
					}
					delete(pending, key)
				}
			}
		}
	}
}
func read(conn *websocket.Conn, target *frame) error {
	kind, data, err := conn.ReadMessage()
	if err != nil {
		return err
	}
	if kind != websocket.TextMessage {
		return errors.New("text frame required")
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return err
	}
	if decoder.Decode(new(any)) != io.EOF {
		return errors.New("one frame required")
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(data, &fields); err != nil {
		return err
	}
	required := map[string][]string{"auth": {"type", "ticket"}, "subscribe": {"type", "matchId"}, "heartbeat": {"type", "requestId"}, "ack": {"type", "eventId", "aggregateId", "aggregateVersion"}}[target.Type]
	if len(required) == 0 || len(fields) != len(required) {
		return errors.New("invalid frame fields")
	}
	for _, name := range required {
		if _, ok := fields[name]; !ok {
			return errors.New("missing frame field")
		}
	}
	return nil
}
