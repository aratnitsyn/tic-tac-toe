# realtime

Независимый Go scaffold. Доменные API и WebSocket ещё не реализованы.
Требуется Go 1.24.0. Из этого каталога:

```sh
go build -o build/realtime .
go run .
```

Внутренний health endpoint: `http://localhost:8086/healthz`.
Переменная `PORT` меняет порт. Публичный TLS завершает Istio ingress.
