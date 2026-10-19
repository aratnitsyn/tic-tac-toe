# matchmaking

Go 1.24.0 сервис FIFO-подбора: admission через Game, очередь с queue_control,
резервирование пары, durable AssignPair/reconciliation, отмена и QueueChanged outbox.
Возврат после отмены подготовки подключён через NATS/inbox к Game.
Этап 10 подключает availability leases Realtime и закрывает 6.5; см. [runbook](../../docs/runbooks/realtime.md).

Из этого каталога:

```sh
go build -o build/matchmaking .
python3 ../../scripts/run-service.py matchmaking
```

Runner читает local.env и игнорируемые credentials стенда; окружение имеет приоритет.
Прямому бинарному файлу задайте настройки явно. Внутренний health:
http://127.0.0.1:8085/healthz. Внешний TLS завершается на Istio ingress.

API, миграции, проверки, replay и ограничения: [runbook](../../docs/runbooks/matchmaking.md).
