# matchmaking

Независимый Go scaffold. Доменные API и WebSocket ещё не реализованы.
Требуется Go 1.24.0. Из этого каталога:

```sh
go build -o build/matchmaking .
go run .
```

Внутренний health endpoint: `http://localhost:8085/healthz`.
Переменная `PORT` меняет порт. Публичный TLS завершает Istio ingress.
