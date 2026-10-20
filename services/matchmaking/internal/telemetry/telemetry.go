package telemetry

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
)

// Setup instruments outgoing clients too; local profiles may omit an exporter.
func Setup(service string) (func(context.Context) error, error) {
	otel.SetTextMapPropagator(propagation.TraceContext{})
	baseTransport := http.DefaultTransport
	http.DefaultTransport = otelhttp.NewTransport(baseTransport)
	endpoint := os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
	if endpoint == "" {
		return func(context.Context) error { return nil }, nil
	}
	exporter, err := otlptracehttp.New(context.Background(), otlptracehttp.WithEndpointURL(endpoint),
		otlptracehttp.WithHTTPClient(&http.Client{Transport: baseTransport, Timeout: 5 * time.Second}))
	if err != nil {
		return nil, err
	}
	if configured := os.Getenv("OTEL_SERVICE_NAME"); configured != "" {
		service = configured
	}
	provider := sdktrace.NewTracerProvider(sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(resource.NewSchemaless(attribute.String("service.name", service))))
	otel.SetTracerProvider(provider)
	return provider.Shutdown, nil
}

func Shutdown(close func(context.Context) error) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := close(ctx); err != nil {
		slog.Error("trace flush failed", "error", err)
	}
}

type domainMetric struct {
	pool        *pgxpool.Pool
	query       map[string]string
	descriptor  map[string]*prometheus.Desc
	healthy     *prometheus.Desc
	active      func() float64
	connections *prometheus.Desc
}

func (d *domainMetric) Describe(ch chan<- *prometheus.Desc) {
	for _, desc := range d.descriptor {
		ch <- desc
	}
	ch <- d.healthy
	if d.active != nil {
		ch <- d.connections
	}
}
func (d *domainMetric) Collect(ch chan<- prometheus.Metric) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	healthy := 1.0
	for name, query := range d.query {
		var value float64
		if err := d.pool.QueryRow(ctx, query).Scan(&value); err != nil {
			healthy = 0
			continue
		}
		ch <- prometheus.MustNewConstMetric(d.descriptor[name], prometheus.GaugeValue, value)
	}
	ch <- prometheus.MustNewConstMetric(d.healthy, prometheus.GaugeValue, healthy)
	if d.active != nil {
		ch <- prometheus.MustNewConstMetric(d.connections, prometheus.GaugeValue, d.active())
	}
}

// Handler exposes aggregate metrics only: raw paths, users and tokens are never labels.
func Handler(service string, pool *pgxpool.Pool, active func() float64, next http.Handler) http.Handler {
	registry := prometheus.NewRegistry()
	registry.MustRegister(collectors.NewGoCollector(), collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}))
	query := map[string]string{
		"ttt_outbox_pending":     "SELECT count(*) FROM outbox WHERE published_at IS NULL",
		"ttt_outbox_lag_seconds": "SELECT coalesce(extract(epoch FROM clock_timestamp()-min(created_at)),0) FROM outbox WHERE published_at IS NULL",
	}
	if service == "matchmaking" {
		query["ttt_queue_available"] = "SELECT count(*) FROM queue_ticket WHERE status='AVAILABLE' AND connected"
		query["ttt_queue_reserved"] = "SELECT count(*) FROM queue_ticket WHERE status='RESERVED'"
		query["ttt_queue_disconnected"] = "SELECT count(*) FROM queue_ticket WHERE status='AVAILABLE' AND NOT connected"
	}
	metric := &domainMetric{pool: pool, query: query, descriptor: map[string]*prometheus.Desc{}, active: active}
	labels := prometheus.Labels{"service": service}
	metric.healthy = prometheus.NewDesc("ttt_observer_up", "Domain metric query availability", nil, labels)
	metric.connections = prometheus.NewDesc("ttt_ws_connections", "Live socket connections on this pod", nil, labels)
	for name := range query {
		metric.descriptor[name] = prometheus.NewDesc(name, "Current aggregate domain state", nil, labels)
	}
	registry.MustRegister(metric)
	return measured(registry, service, next)
}

func measured(registry *prometheus.Registry, service string, next http.Handler) http.Handler {
	count := prometheus.NewCounterVec(prometheus.CounterOpts{Name: "ttt_http_requests_total", Help: "HTTP requests by bounded route and status"}, []string{"method", "route", "code"})
	latency := prometheus.NewHistogramVec(prometheus.HistogramOpts{Name: "ttt_http_request_duration_seconds", Help: "HTTP handler duration", Buckets: prometheus.DefBuckets}, []string{"method", "route"})
	registry.MustRegister(count, latency)
	metrics := promhttp.HandlerFor(registry, promhttp.HandlerOpts{})
	measured := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		record := &response{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(record, r)
		route := r.Pattern
		if route == "" {
			route = "unmatched"
		}
		method := r.Method
		switch method {
		case "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS":
		default:
			method = "OTHER"
		}
		count.WithLabelValues(method, route, strconv.Itoa(record.status)).Inc()
		latency.WithLabelValues(method, route).Observe(time.Since(started).Seconds())
	})
	traced := otelhttp.NewHandler(measured, service+" HTTP")
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/metrics" {
			metrics.ServeHTTP(w, r)
			return
		}
		traced.ServeHTTP(w, r)
	})
}

type response struct {
	http.ResponseWriter
	status int
}

func (w *response) WriteHeader(status int)      { w.status = status; w.ResponseWriter.WriteHeader(status) }
func (w *response) Unwrap() http.ResponseWriter { return w.ResponseWriter }
func (w *response) Flush() {
	if f, ok := w.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}
func (w *response) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	h, ok := w.ResponseWriter.(http.Hijacker)
	if !ok {
		return nil, nil, errors.New("connection hijacking unavailable")
	}
	conn, buffer, err := h.Hijack()
	if err != nil {
		return nil, nil, fmt.Errorf("hijack: %w", err)
	}
	w.status = http.StatusSwitchingProtocols
	return conn, buffer, nil
}
