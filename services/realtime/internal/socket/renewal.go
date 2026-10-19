package socket

import (
	"context"
	"errors"
	"time"
)

type Lease struct {
	User       string `json:"userId"`
	Match      string `json:"matchId"`
	Generation int64  `json:"generation"`
}
type renewal struct {
	ctx    context.Context
	lease  Lease
	result chan error
}
type Renewal struct {
	requests chan renewal
	done     <-chan struct{}
}

func NewRenewal(ctx context.Context, backend Backend) *Renewal {
	r := &Renewal{requests: make(chan renewal, 1000), done: ctx.Done()}
	go r.run(ctx, backend)
	return r
}
func (r *Renewal) Renew(ctx context.Context, user, match string, generation int64) error {
	q := renewal{ctx, Lease{user, match, generation}, make(chan error, 1)}
	select {
	case r.requests <- q:
	case <-ctx.Done():
		return ctx.Err()
	case <-r.done:
		return context.Canceled
	}
	select {
	case err := <-q.result:
		return err
	case <-ctx.Done():
		return ctx.Err()
	case <-r.done:
		return context.Canceled
	}
}
func (r *Renewal) run(ctx context.Context, b Backend) {
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	batch := make([]renewal, 0, 1000)
	flush := func() {
		active := batch[:0]
		for _, q := range batch {
			if q.ctx.Err() == nil {
				active = append(active, q)
			}
		}
		batch = active
		if len(batch) == 0 {
			return
		}
		leases := make([]Lease, 0, len(batch))
		for _, q := range batch {
			leases = append(leases, q.lease)
		}
		var response struct{ Connections []Lease }
		err := b.call(ctx, false, "POST", "/internal/v1/connections/renew", "", map[string]any{"operationId": ID(), "connections": leases}, &response)
		accepted := map[Lease]bool{}
		for _, v := range response.Connections {
			accepted[v] = true
		}
		for _, q := range batch {
			result := err
			if result == nil && !accepted[q.lease] {
				result = errors.New("lease rejected")
			}
			q.result <- result
		}
		batch = batch[:0]
	}
	for {
		select {
		case <-ctx.Done():
			for _, q := range batch {
				q.result <- ctx.Err()
			}
			return
		case q := <-r.requests:
			batch = append(batch, q)
			if len(batch) == 1000 {
				flush()
			}
		case <-ticker.C:
			flush()
		}
	}
}
