package queue

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http/httptest"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/aratnitsyn/tictactoe/services/matchmaking/internal/inbox"
	"github.com/aratnitsyn/tictactoe/services/matchmaking/internal/testutil"
	"github.com/jackc/pgx/v5"
)

// The fake is a serializable, idempotent Game boundary. Real Game is exercised by
// check-matchmaking-runtime.py; these tests isolate Matchmaking crash boundaries.
type fakeGame struct {
	mu        sync.Mutex
	people    map[string]Participation
	results   map[string]Outcome
	pairCount int
	pending   bool
	lose      bool
}

func fixture(t *testing.T) (*Service, *fakeGame) {
	t.Helper()
	g := &fakeGame{people: map[string]Participation{}, results: map[string]Outcome{}}
	return &Service{DB: testutil.Database(t), Game: g}, g
}
func (g *fakeGame) Participation(_ context.Context, u string) (Participation, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.person(u), nil
}
func (g *fakeGame) person(u string) Participation {
	p, ok := g.people[u]
	if !ok {
		p = Participation{User: u, State: "MENU"}
	}
	return p
}
func (g *fakeGame) Resolve(_ context.Context, op Operation) (Outcome, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.pending {
		return Outcome{Status: "PENDING"}, nil
	}
	if r, ok := g.results[op.ID]; ok {
		return r, nil
	}
	var v struct {
		User     string `json:"userId"`
		Target   string `json:"target"`
		Resource string `json:"resourceId"`
		Version  int64  `json:"expectedVersion"`
		Match    string `json:"matchId"`
		Players  []struct {
			User    string `json:"userId"`
			Ticket  string `json:"ticketId"`
			Version int64  `json:"expectedVersion"`
		} `json:"players"`
	}
	if err := json.Unmarshal(op.GamePayload, &v); err != nil {
		return Outcome{}, err
	}
	r := Outcome{Status: "APPLIED"}
	if op.Kind == "PAIR" {
		for _, x := range v.Players {
			p := g.person(x.User)
			if p.State != "QUEUED" || p.Resource != x.Ticket || p.Version != x.Version {
				r = Outcome{Status: "REJECTED", ErrorCode: "PARTICIPATION_CONFLICT"}
			}
		}
		if r.Status == "APPLIED" {
			g.pairCount++
			r.ResourceID = v.Match
			for _, x := range v.Players {
				p := g.person(x.User)
				p.State = "IN_MATCH"
				p.Resource = v.Match
				p.Version++
				g.people[x.User] = p
			}
		}
	} else {
		p := g.person(v.User)
		if p.Version != v.Version {
			r = Outcome{Status: "REJECTED", ErrorCode: "VERSION_CONFLICT"}
		} else if (v.Target == "QUEUED" && p.State != "MENU") || (v.Target == "MENU" && (p.State != "QUEUED" || p.Resource != v.Resource)) {
			r = Outcome{Status: "REJECTED", ErrorCode: "PARTICIPATION_CONFLICT"}
		} else {
			p.State = v.Target
			p.Resource = v.Resource
			if p.State == "MENU" {
				p.Resource = ""
			}
			p.Version++
			g.people[v.User] = p
			r.Version = p.Version
			r.ResourceID = p.Resource
		}
	}
	g.results[op.ID] = r
	if g.lose {
		g.lose = false
		return Outcome{}, errors.New("lost response after commit")
	}
	return r, nil
}
func join(t *testing.T, s *Service, u string) Ticket {
	t.Helper()
	r, err := s.Execute(context.Background(), u, "JOIN", UUID(), UUID(), json.RawMessage(`{"expectedVersion":0}`))
	if err != nil {
		t.Fatal(err)
	}
	if r.Status != 201 {
		t.Fatalf("join status %d: %s", r.Status, r.Body)
	}
	var v struct{ Data Ticket }
	if err = json.Unmarshal(r.Body, &v); err != nil {
		t.Fatal(err)
	}
	return v.Data
}
func read(t *testing.T, s *Service, id string) Ticket {
	t.Helper()
	var v Ticket
	if err := s.transaction(context.Background(), func(tx pgx.Tx) error { var err error; v, err = readTicket(context.Background(), tx, id); return err }); err != nil {
		t.Fatal(err)
	}
	return v
}
func scalar(t *testing.T, s *Service, sql string, args ...any) int {
	t.Helper()
	var v int
	if err := s.DB.QueryRow(context.Background(), sql, args...).Scan(&v); err != nil {
		t.Fatal(err)
	}
	return v
}
func TestAdmissionReplayAndPayloadConflict(t *testing.T) {
	s, _ := fixture(t)
	ctx := context.Background()
	u, key := UUID(), UUID()
	raw := json.RawMessage(`{"expectedVersion":0}`)
	a, err := s.Execute(ctx, u, "JOIN", key, key, raw)
	if err != nil {
		t.Fatal(err)
	}
	b, err := s.Execute(ctx, u, "JOIN", key, key, json.RawMessage(`{ "expectedVersion" : 0 }`))
	var aj, bj any
	_ = json.Unmarshal(a.Body, &aj)
	_ = json.Unmarshal(b.Body, &bj)
	if err != nil || !reflect.DeepEqual(aj, bj) || a.Status != b.Status {
		t.Fatal("unstable replay", err)
	}
	_, err = s.Execute(ctx, u, "JOIN", key, key, json.RawMessage(`{"expectedVersion":1}`))
	if err == nil {
		t.Fatal("changed payload accepted")
	}
	if scalar(t, s, "SELECT count(*) FROM queue_ticket") != 1 || scalar(t, s, "SELECT count(*) FROM outbox") != 1 {
		t.Fatal("duplicate effects")
	}
}
func TestParallelAdmissionTotalOrder(t *testing.T) {
	s, _ := fixture(t)
	var wg sync.WaitGroup
	errs := make(chan error, 20)
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			r, err := s.Execute(context.Background(), UUID(), "JOIN", UUID(), UUID(), json.RawMessage(`{"expectedVersion":0}`))
			if err != nil {
				errs <- err
			} else if r.Status != 201 {
				errs <- fmt.Errorf("status %d", r.Status)
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Error(err)
	}
	if scalar(t, s, "SELECT count(DISTINCT admission_sequence) FROM queue_ticket") != 20 || scalar(t, s, "SELECT next_sequence::int FROM queue_control") != 20 {
		t.Fatal("admission order not total")
	}
}
func TestAdmissionUnknownResultRestart(t *testing.T) {
	s, g := fixture(t)
	ctx := context.Background()
	u, key := UUID(), UUID()
	g.lose = true
	if _, err := s.Execute(ctx, u, "JOIN", key, key, json.RawMessage(`{"expectedVersion":0}`)); err == nil {
		t.Fatal("fault not injected")
	}
	if scalar(t, s, "SELECT count(*) FROM queue_ticket") != 0 {
		t.Fatal("admitted before known")
	}
	s = &Service{DB: s.DB, Game: g}
	if err := s.Reconcile(ctx); err != nil {
		t.Fatal(err)
	}
	if scalar(t, s, "SELECT count(*) FROM queue_ticket") != 1 {
		t.Fatal("admission lost")
	}
	if _, err := s.Execute(ctx, u, "JOIN", key, key, json.RawMessage(`{"expectedVersion":0}`)); err != nil {
		t.Fatal(err)
	}
}
func TestConcurrentSelectorsFIFO(t *testing.T) {
	s, g := fixture(t)
	a := join(t, s, UUID())
	b := join(t, s, UUID())
	c := join(t, s, UUID())
	d := join(t, s, UUID())
	var wg sync.WaitGroup
	ids := make(chan string, 8)
	errs := make(chan error, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			id, err := s.Select(context.Background())
			if err != nil {
				errs <- err
			}
			if id != "" {
				ids <- id
			}
		}()
	}
	wg.Wait()
	close(ids)
	close(errs)
	for err := range errs {
		t.Error(err)
	}
	if read(t, s, a.ID).Pair != read(t, s, b.ID).Pair || read(t, s, c.ID).Pair != read(t, s, d.ID).Pair || read(t, s, a.ID).Pair == read(t, s, c.ID).Pair {
		t.Fatal("FIFO violated")
	}
	for id := range ids {
		if _, err := s.Resume(context.Background(), id); err != nil {
			t.Fatal(err)
		}
	}
	if g.pairCount != 2 {
		t.Fatal("wrong match count")
	}
}
func TestLockedHeadIsNotSkipped(t *testing.T) {
	s, _ := fixture(t)
	a := join(t, s, UUID())
	b := join(t, s, UUID())
	join(t, s, UUID())
	ctx := context.Background()
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err = tx.Exec(ctx, "SELECT ticket_id FROM queue_ticket WHERE ticket_id=$1 FOR UPDATE", a.ID); err != nil {
		t.Fatal(err)
	}
	short, cancel := context.WithTimeout(ctx, 80*time.Millisecond)
	defer cancel()
	if id, err := s.Select(short); err == nil || id != "" {
		t.Fatal("locked head bypassed")
	}
	if err = tx.Rollback(ctx); err != nil {
		t.Fatal(err)
	}
	id, err := s.Select(ctx)
	if err != nil || id == "" {
		t.Fatal(err)
	}
	if read(t, s, a.ID).Pair != read(t, s, b.ID).Pair {
		t.Fatal("head not selected")
	}
}
func TestPairCrashBeforeAndAfterGame(t *testing.T) {
	for _, after := range []bool{false, true} {
		t.Run(fmt.Sprint(after), func(t *testing.T) {
			s, g := fixture(t)
			a := join(t, s, UUID())
			b := join(t, s, UUID())
			id, err := s.Select(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			if after {
				g.lose = true
				if _, err = s.Resume(context.Background(), id); err == nil {
					t.Fatal("lost response missing")
				}
			}
			if read(t, s, a.ID).Status != "RESERVED" {
				t.Fatal("unknown pair released")
			}
			s = &Service{DB: s.DB, Game: g}
			if err = s.Reconcile(context.Background()); err != nil {
				t.Fatal(err)
			}
			if _, err = s.Resume(context.Background(), id); err != nil {
				t.Fatal(err)
			}
			if g.pairCount != 1 || read(t, s, a.ID).Status != "CONSUMED" || read(t, s, b.ID).Status != "CONSUMED" {
				t.Fatal("pair recovery duplicated/lost")
			}
		})
	}
}
func TestCancelPairRace(t *testing.T) {
	for _, cancelFirst := range []bool{false, true} {
		t.Run(fmt.Sprint(cancelFirst), func(t *testing.T) {
			s, g := fixture(t)
			u, v := UUID(), UUID()
			a := join(t, s, u)
			b := join(t, s, v)
			id, err := s.Select(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			cancel := func() {
				r, err := s.Execute(context.Background(), u, "CANCEL", UUID(), UUID(), json.RawMessage(`{"expectedVersion":1}`))
				if err != nil {
					t.Fatal(err)
				}
				if cancelFirst && r.Status != 200 || !cancelFirst && r.Status != 409 {
					t.Fatalf("cancel status %d", r.Status)
				}
			}
			if cancelFirst {
				cancel()
			}
			if _, err = s.Resume(context.Background(), id); err != nil {
				t.Fatal(err)
			}
			if !cancelFirst {
				cancel()
			}
			p, _ := g.Participation(context.Background(), u)
			if cancelFirst {
				if p.State != "MENU" || read(t, s, a.ID).Status != "CANCELLED" || read(t, s, b.ID).Status != "AVAILABLE" || g.pairCount != 0 {
					t.Fatal("cancel winner corrupted")
				}
			} else if p.State != "IN_MATCH" || g.pairCount != 1 {
				t.Fatal("assigned player released")
			}
		})
	}
}
func TestPendingPairCannotBeReused(t *testing.T) {
	s, g := fixture(t)
	a := join(t, s, UUID())
	join(t, s, UUID())
	g.pending = true
	id, _ := s.Select(context.Background())
	if _, err := s.Resume(context.Background(), id); err == nil {
		t.Fatal("pending reported success")
	}
	if id, err := s.Select(context.Background()); err != nil || id != "" {
		t.Fatal("unknown pair reused")
	}
	if read(t, s, a.ID).Status != "RESERVED" {
		t.Fatal("released pending")
	}
}
func TestReturnOriginalPriorityAndInbox(t *testing.T) {
	s, g := fixture(t)
	ctx := context.Background()
	a := join(t, s, UUID())
	join(t, s, UUID())
	pair, _ := s.Select(ctx)
	if _, err := s.Resume(ctx, pair); err != nil {
		t.Fatal(err)
	}
	c := join(t, s, UUID())
	d := join(t, s, UUID())
	e := join(t, s, UUID())
	fixed, _ := s.Select(ctx)
	if read(t, s, c.ID).Pair != fixed || read(t, s, d.ID).Pair != fixed {
		t.Fatal("fixture pair")
	}
	g.mu.Lock()
	p := g.people[a.User]
	p.State = "QUEUED"
	p.Resource = a.ID
	p.Version++
	g.people[a.User] = p
	g.mu.Unlock()
	event := encode(map[string]any{"eventId": UUID(), "type": "ReturnToQueue", "aggregateVersion": p.Version, "correlationId": UUID(), "payload": map[string]any{"userId": a.User, "ticketId": a.ID, "originalSequence": a.Sequence, "participationVersion": p.Version}})
	store := inbox.Store{DB: s.DB}
	for i := 0; i < 2; i++ {
		first, err := store.Process(ctx, "queue-return-test", pair, func(tx pgx.Tx) error { return s.Consume(ctx, tx, event) })
		if err != nil || first != (i == 0) {
			t.Fatal("return inbox", err)
		}
	}
	next, err := s.Select(ctx)
	if err != nil || next == "" {
		t.Fatal(err)
	}
	if read(t, s, a.ID).Pair != next || read(t, s, e.ID).Pair != next || read(t, s, c.ID).Pair != fixed {
		t.Fatal("return priority displaced reserved pair")
	}
}
func TestHTTPBoundaryAndValidation(t *testing.T) {
	s, _ := fixture(t)
	token := strings.Repeat("x", 32)
	handler := s.Handler(token)
	cases := []struct {
		method, path, body, user, key, credential string
		status                                    int
	}{{"GET", "/api/v1/matchmaking/queue", "", UUID(), UUID(), "wrong", 401}, {"POST", "/api/v1/matchmaking/queue", `{"expectedVersion":0}`, UUID(), "bad", token, 400}, {"POST", "/api/v1/matchmaking/queue", `{"expectedVersion":null}`, UUID(), UUID(), token, 400}, {"POST", "/api/v1/matchmaking/queue", `{"expectedVersion":0,"extra":1}`, UUID(), UUID(), token, 400}, {"POST", "/api/v1/matchmaking/queue", `{"expectedVersion":1.2}`, UUID(), UUID(), token, 400}, {"GET", "/api/v1/matchmaking/queue", "", UUID(), UUID(), token, 404}}
	for _, c := range cases {
		r := httptest.NewRequest(c.method, c.path, strings.NewReader(c.body))
		r.Header.Set("X-Service-Token", c.credential)
		r.Header.Set("X-User-Id", c.user)
		r.Header.Set("Idempotency-Key", c.key)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if w.Code != c.status {
			t.Errorf("%s expected %d got %d: %s", c.body, c.status, w.Code, w.Body.String())
		}
	}
}

func TestDisconnectedTicketAndGenerationFence(t *testing.T) {
	s, _ := fixture(t)
	ctx := context.Background()
	a := join(t, s, UUID())
	b := join(t, s, UUID())
	c := join(t, s, UUID())
	if err := s.Availability(ctx, a.ID, 2, false); err != nil {
		t.Fatal(err)
	}
	id, err := s.Select(ctx)
	if err != nil || id == "" {
		t.Fatal(err)
	}
	if read(t, s, b.ID).Pair != id || read(t, s, c.ID).Pair != id || read(t, s, a.ID).Status != "AVAILABLE" {
		t.Fatal("disconnected selected")
	}
	if err = s.Availability(ctx, a.ID, 3, true); err != nil {
		t.Fatal(err)
	}
	if err = s.Availability(ctx, a.ID, 2, false); err != nil {
		t.Fatal(err)
	}
	d := join(t, s, UUID())
	next, err := s.Select(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if read(t, s, a.ID).Pair != next || read(t, s, d.ID).Pair != next || !read(t, s, a.ID).Connected {
		t.Fatal("stale disconnect changed eligibility")
	}
}
func TestDisconnectCurrentGeneration(t *testing.T) {
	s, _ := fixture(t)
	a := join(t, s, UUID())
	ctx := context.Background()
	if err := s.Availability(ctx, a.ID, 5, true); err != nil {
		t.Fatal(err)
	}
	if err := s.Availability(ctx, a.ID, 5, false); err != nil {
		t.Fatal(err)
	}
	if read(t, s, a.ID).Connected {
		t.Fatal("current generation disconnect ignored")
	}
	if err := s.Availability(ctx, a.ID, 5, true); err != nil {
		t.Fatal(err)
	}
	if read(t, s, a.ID).Connected {
		t.Fatal("same generation resurrected disconnected socket")
	}
	if err := s.Availability(ctx, a.ID, 6, true); err != nil {
		t.Fatal(err)
	}
	if !read(t, s, a.ID).Connected {
		t.Fatal("new generation did not reconnect")
	}
}

func TestAfterGameBeforeLocalCommit(t *testing.T) {
	s, g := fixture(t)
	u, key := UUID(), UUID()
	s.AfterGame = func() error { return errors.New("crash before local commit") }
	if _, err := s.Execute(context.Background(), u, "JOIN", key, key, json.RawMessage(`{"expectedVersion":0}`)); err == nil {
		t.Fatal("fault missing")
	}
	if scalar(t, s, "SELECT count(*) FROM queue_ticket") != 0 {
		t.Fatal("partial local commit")
	}
	s.AfterGame = nil
	if err := s.Reconcile(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(g.results) != 1 || scalar(t, s, "SELECT count(*) FROM queue_ticket") != 1 {
		t.Fatal("repeated Game command")
	}
}
func TestActualConcurrentCancelAndAssign(t *testing.T) {
	for i := 0; i < 8; i++ {
		s, g := fixture(t)
		u := UUID()
		a := join(t, s, u)
		b := join(t, s, UUID())
		id, err := s.Select(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		var wg sync.WaitGroup
		var cancelStatus int
		errs := make(chan error, 2)
		wg.Add(2)
		go func() {
			defer wg.Done()
			r, err := s.Execute(context.Background(), u, "CANCEL", UUID(), UUID(), json.RawMessage(`{"expectedVersion":1}`))
			cancelStatus = r.Status
			if err != nil {
				errs <- err
			}
		}()
		go func() {
			defer wg.Done()
			_, err := s.Resume(context.Background(), id)
			if err != nil {
				errs <- err
			}
		}()
		wg.Wait()
		close(errs)
		for err := range errs {
			t.Fatal(err)
		}
		p, _ := g.Participation(context.Background(), u)
		if p.State == "MENU" {
			if cancelStatus != 200 || g.pairCount != 0 || read(t, s, b.ID).Status != "AVAILABLE" {
				t.Fatal("cancel race inconsistent")
			}
		} else if p.State != "IN_MATCH" || cancelStatus != 409 || g.pairCount != 1 || read(t, s, a.ID).Status != "CONSUMED" {
			t.Fatal("assignment race inconsistent")
		}
	}
}

func TestParticipationCleanupCannotCancelNewTicket(t *testing.T) {
	s, g := fixture(t)
	ctx := context.Background()
	a := join(t, s, UUID())
	g.mu.Lock()
	p := g.people[a.User]
	p.State = "MENU"
	p.Resource = ""
	p.Version++
	g.people[a.User] = p
	g.mu.Unlock()
	raw := encode(map[string]any{"type": "ParticipationChanged", "eventId": UUID(), "correlationId": UUID(), "payload": p})
	apply := func() {
		t.Helper()
		if err := s.read(ctx, func(tx pgx.Tx) error { return s.Consume(ctx, tx, raw) }); err != nil {
			t.Fatal(err)
		}
	}
	apply()
	if read(t, s, a.ID).Status != "CANCELLED" {
		t.Fatal("old availability retained")
	}
	r, err := s.Execute(ctx, a.User, "JOIN", UUID(), UUID(), encode(map[string]any{"expectedVersion": p.Version}))
	if err != nil || r.Status != 201 {
		t.Fatal("new search rejected", err)
	}
	var response struct {
		Data Ticket `json:"data"`
	}
	if err = json.Unmarshal(r.Body, &response); err != nil {
		t.Fatal(err)
	}
	apply()
	if read(t, s, response.Data.ID).Status != "AVAILABLE" {
		t.Fatal("old cleanup cancelled new ticket")
	}
}

func TestEarlyReturnRetriesInboxUntilConsumed(t *testing.T) {
	s, g := fixture(t)
	ctx := context.Background()
	a := join(t, s, UUID())
	join(t, s, UUID())
	pair, err := s.Select(ctx)
	if err != nil {
		t.Fatal(err)
	}
	s.AfterGame = func() error { return errors.New("crash after Game commit") }
	if _, err = s.Resume(ctx, pair); err == nil {
		t.Fatal("missing crash")
	}
	g.mu.Lock()
	p := g.people[a.User]
	p.State = "QUEUED"
	p.Resource = a.ID
	p.Version++
	g.people[a.User] = p
	g.mu.Unlock()
	id := UUID()
	raw := encode(map[string]any{"type": "ReturnToQueue", "eventId": id, "correlationId": UUID(), "aggregateVersion": p.Version, "payload": map[string]any{"userId": p.User, "ticketId": a.ID, "originalSequence": a.Sequence, "participationVersion": p.Version}})
	store := inbox.Store{DB: s.DB}
	apply := func() (bool, error) {
		return store.Process(ctx, "early-return-test", id, func(tx pgx.Tx) error { return s.Consume(ctx, tx, raw) })
	}
	if _, err = apply(); err == nil {
		t.Fatal("early event acknowledged")
	}
	if read(t, s, a.ID).Status != "RESERVED" {
		t.Fatal("unknown pair released")
	}
	s.AfterGame = nil
	if _, err = s.Resume(ctx, pair); err != nil {
		t.Fatal(err)
	}
	if first, err := apply(); err != nil || !first {
		t.Fatal("return did not retry", err)
	}
	if read(t, s, a.ID).Status != "AVAILABLE" {
		t.Fatal("return lost")
	}
	if first, err := apply(); err != nil || first {
		t.Fatal("inbox duplicate", err)
	}
}
