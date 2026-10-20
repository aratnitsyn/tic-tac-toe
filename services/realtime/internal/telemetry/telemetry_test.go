package telemetry

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/gorilla/websocket"
	"github.com/prometheus/client_golang/prometheus"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
)

func TestMetricRouteDoesNotContainUserPathOrQuery(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /player/{id}", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(204) })
	handler := measured(prometheus.NewRegistry(), "test", mux)
	for _, id := range []string{"secret-user-a", "secret-user-b"} {
		request := httptest.NewRequest("GET", "/player/"+id+"?token=secret-token", nil)
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, request)
		if recorder.Code != 204 {
			t.Fatal(recorder.Code)
		}
	}
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, httptest.NewRequest("GET", "/metrics", nil))
	body := recorder.Body.String()
	if strings.Contains(body, "secret-") || !strings.Contains(body, `route="GET /player/{id}"`) {
		t.Fatal("metric route leaked input or lost template")
	}
	if !strings.Contains(body, `code="204",method="GET",route="GET /player/{id}"} 2`) {
		t.Fatal("normalized counter did not aggregate both requests")
	}
}

func TestWrapperPreservesWebsocketUpgrade(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /wss", func(w http.ResponseWriter, r *http.Request) {
		upgrader := websocket.Upgrader{}
		connection, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			t.Error(err)
			return
		}
		defer func() { _ = connection.Close() }()
		if err = connection.WriteMessage(websocket.TextMessage, []byte("authenticated")); err != nil {
			t.Error(err)
		}
	})
	server := httptest.NewServer(measured(prometheus.NewRegistry(), "test", mux))
	defer server.Close()
	connection, response, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http")+"/wss", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = connection.Close() }()
	if response.StatusCode != 101 {
		t.Fatal(response.StatusCode)
	}
	_, body, err := connection.ReadMessage()
	if err != nil || string(body) != "authenticated" {
		t.Fatal("websocket was not preserved", err)
	}
}

func TestTracePropagatesAcrossRealHTTPHop(t *testing.T) {
	exporter := tracetest.NewInMemoryExporter()
	provider := sdktrace.NewTracerProvider(sdktrace.WithSyncer(exporter))
	previous := otel.GetTracerProvider()
	propagator := otel.GetTextMapPropagator()
	otel.SetTracerProvider(provider)
	otel.SetTextMapPropagator(propagation.TraceContext{})
	t.Cleanup(func() {
		otel.SetTracerProvider(previous)
		otel.SetTextMapPropagator(propagator)
		_ = provider.Shutdown(context.Background())
	})
	child := httptest.NewServer(otelhttp.NewHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.Header.Get("traceparent"), "00-0123456789abcdef0123456789abcdef-") {
			t.Error("trace context not propagated")
		}
		w.WriteHeader(204)
	}), "child"))
	defer child.Close()
	client := http.Client{Transport: otelhttp.NewTransport(http.DefaultTransport)}
	parent := httptest.NewServer(measured(prometheus.NewRegistry(), "parent", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		request, err := http.NewRequestWithContext(r.Context(), "GET", child.URL, nil)
		if err != nil {
			t.Error(err)
			return
		}
		response, err := client.Do(request)
		if err != nil {
			t.Error(err)
			return
		}
		_, err = io.Copy(io.Discard, response.Body)
		closeErr := response.Body.Close()
		if err != nil || closeErr != nil {
			t.Error("response cleanup failed")
		}
		w.WriteHeader(204)
	})))
	defer parent.Close()
	request, err := http.NewRequest("GET", parent.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	if err = response.Body.Close(); err != nil {
		t.Fatal(err)
	}
	spans := exporter.GetSpans()
	if len(spans) < 3 {
		t.Fatal("HTTP chain did not create parent/client/child spans")
	}
	for _, span := range spans {
		if span.SpanContext.TraceID().String() != "0123456789abcdef0123456789abcdef" {
			t.Fatal("trace split across hop")
		}
	}
}

func TestExporterDoesNotTraceItsOwnHTTPRequests(t *testing.T) {
	var requests atomic.Int64
	collector := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		if r.Header.Get("traceparent") != "" {
			t.Error("exporter was recursively instrumented")
		}
		_, err := io.Copy(io.Discard, r.Body)
		if err != nil {
			t.Error(err)
		}
		w.Header().Set("Content-Type", "application/x-protobuf")
		w.WriteHeader(200)
	}))
	defer collector.Close()
	t.Setenv("OTEL_EXPORTER_OTLP_ENDPOINT", collector.URL)
	previous, transport := otel.GetTracerProvider(), http.DefaultTransport
	t.Cleanup(func() { otel.SetTracerProvider(previous); http.DefaultTransport = transport })
	closeTrace, err := Setup("test")
	if err != nil {
		t.Fatal(err)
	}
	_, span := otel.Tracer("test").Start(context.Background(), "command")
	span.End()
	if err = closeTrace(context.Background()); err != nil {
		t.Fatal(err)
	}
	if requests.Load() != 1 {
		t.Fatal("expected one bounded export without recursive spans", requests.Load())
	}
}
