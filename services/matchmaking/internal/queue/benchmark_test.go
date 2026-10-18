package queue

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"sync"
	"testing"
	"time"
)

// This report uses an isolated migrated schema and removes it afterwards.
// No claim about 100k capacity follows from this local selector microbenchmark.
func TestQueueControlBenchmark(t *testing.T) {
	s, _ := fixture(t)
	ctx := context.Background()
	const workers = 4
	const pairs = 200
	for i := 0; i < 2*pairs; i++ {
		if _, err := s.DB.Exec(ctx, `INSERT INTO queue_ticket(ticket_id,user_id,admission_sequence,original_sequence,participation_version,status) VALUES($1,$2,$3,$3,1,'AVAILABLE')`, UUID(), UUID(), i+1); err != nil {
			t.Fatal(err)
		}
	}
	var mu sync.Mutex
	var latency []float64
	var wg sync.WaitGroup
	errs := make(chan error, workers)
	stop := make(chan struct{})
	samples, lockSamples := 0, 0
	done := make(chan struct{})
	go func() {
		defer close(done)
		ticker := time.NewTicker(2 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ticker.C:
				var count int
				err := s.DB.QueryRow(ctx, `SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND application_name=$1 AND query LIKE '%queue_control%'`, s.DB.Config().ConnConfig.RuntimeParams["application_name"]).Scan(&count)
				if err == nil {
					samples++
					lockSamples += count
				}
			}
		}
	}()
	start := time.Now()
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < pairs/workers; i++ {
				at := time.Now()
				id, err := s.Select(ctx)
				elapsed := float64(time.Since(at).Microseconds()) / 1000
				if err != nil {
					errs <- err
					return
				}
				if id == "" {
					errs <- fmt.Errorf("missing pair")
					return
				}
				mu.Lock()
				latency = append(latency, elapsed)
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	elapsed := time.Since(start)
	close(stop)
	<-done
	close(errs)
	for err := range errs {
		t.Fatal(err)
	}
	sort.Float64s(latency)
	if scalar(t, s, "SELECT count(*) FROM queue_ticket WHERE status='RESERVED'") != 2*pairs || scalar(t, s, "SELECT count(DISTINCT pair_id) FROM queue_ticket") != pairs {
		t.Fatal("benchmark invariant failed")
	}
	if scalar(t, s, `SELECT count(*) FROM (SELECT pair_id FROM queue_ticket GROUP BY pair_id HAVING max(original_sequence)-min(original_sequence)=1 AND mod(min(original_sequence),2)=1) ordered`) != pairs {
		t.Fatal("benchmark FIFO violation")
	}
	report := map[string]any{"workers": workers, "pairs": pairs, "seconds": elapsed.Seconds(), "pairsPerSecond": float64(pairs) / elapsed.Seconds(), "selectionP50Ms": latency[len(latency)/2], "selectionP95Ms": latency[len(latency)*95/100], "selectionMaxMs": latency[len(latency)-1], "lockObservationSamples": samples, "lockWaiterSamples": lockSamples, "scope": "local PostgreSQL selection+reservation+outbox; excludes Game and network; latency includes lock waiting"}
	raw, _ := json.Marshal(report)
	t.Log("QUEUE_BENCHMARK " + string(raw))
}
