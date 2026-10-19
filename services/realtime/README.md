# realtime

Go 1.24.0: внутренний WS `/wss`, ticket-аутентификация, generation/ownership,
heartbeat и durable Game leases, адресный NATS dispatcher и тестовый клиент
синхронизации. Внешний WSS обслуживает будущий Istio ingress.

Из этого каталога:

```sh
go build -o build/realtime .
python3 ../../scripts/run-service.py realtime
```

Runner читает несекретный local.env; окружение имеет приоритет. Секреты поступают
из игнорируемых настроек локального стенда. `/healthz` — внутренний health endpoint.
`REALTIME_ROLE=socket|dispatcher|all`; каждому socket нужен уникальный REALTIME_POD_ID.
Запуск, протокол, ограничения и проверки: [runbook](../../docs/runbooks/realtime.md).
