# matchmaking

Независимый Go scaffold. Доменные API и WebSocket ещё не реализованы.
Требуется Go 1.24.0. Из этого каталога:

```sh
go build -o build/matchmaking .
python3 ../../scripts/run-service.py matchmaking
```

Внутренний health endpoint: `http://127.0.0.1:8085/healthz`.
Переменная `PORT` меняет порт. Публичный TLS завершает Istio ingress.

Локальный запуск через `scripts/run-service.py` читает `local.env` этого сервиса.
Переменные текущего окружения имеют приоритет. В `local.env` хранятся только
несекретные настройки.
Для прямого запуска бинарного файла задайте переменные окружения самостоятельно.
