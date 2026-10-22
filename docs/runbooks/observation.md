# Наблюдаемость и восстановление

Локальные пороги ниже — начальные эксплуатационные настройки, не утверждённые SLO.
Все команды выполняются из корня в контексте docker-desktop. Prometheus/Grafana/
Jaeger доступны только внутри кластера либо через port-forward на 127.0.0.1.
Не публикуйте их ingress-маршрутами и не выводите пароль Grafana из Vault в логи.

Установка: `infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/observation.py`.
Проверка: тот же Python с `infra/kubernetes/tool/observation-check.py`.
Метрики без userId/session/token labels; идентификаторы операций ищите в traces
и прикладных логах, не добавляйте их в series. Jaeger локально хранит данные в памяти.

## Backend unavailable

`BackendUnavailable`: scrape недоступен 10 секунд. Проверьте `get pods`,
`describe pod`, readiness, последние логи приложения, endpoints и NetworkPolicy.
Проверьте Vault: после рестарта нужен `vault.py --unseal-only`. Не заменяйте credentials.
Если replicas случайно обнулены, восстановите значение из Helm values; при дефекте
нового образа используйте совместимый Helm rollback, не откатывая схему БД вслепую.
Восстановление: Ready и `up == 1`, alert resolved; затем HTTPS/WSS smoke.

## Domain observer unavailable

`ttt_observer_up == 0`: samples устарели из-за недоступности собственной БД.
Проверьте PostgreSQL, доступность своего DB role, Hikari/pgx pool и SQL ошибки в логах.
Не используйте stale gauge как доказательство отсутствия очереди. После восстановления
дождитесь `ttt_observer_up == 1` и свежих samples; чужие БД не запрашивайте.

## Outbox stalled

При наличии pending lag выше 30 секунд: проверьте NATS readiness/JetStream,
диск/PVC NATS, permissions, ошибки publish/ack и фонового worker.
Восстановите NATS/worker, затем дождитесь падения pending/lag.
Не удаляйте outbox/inbox и не помечайте строки опубликованными вручную:
повторная доставка штатна, дедупликация сохраняет доменные инварианты.

## Timer worker stalled

Due deadlines старше 5 секунд: проверьте Game, PostgreSQL lock waits,
пулы, CPU/memory и ошибки scheduled worker. При необходимости rolling restart Game.
Сроки серверные: задержка worker не продлевает ход или recovery budget.
Проверьте уменьшение due/lag и актуальный snapshot; не переписывайте due_at вручную.

## Queue availability lost

Больше половины ожидающих tickets отключены: проверьте Realtime, heartbeat,
Redis, Auth introspection, durable availability leases и доставку NATS.
Не переставляйте originalSequence и не освобождайте RESERVED по timeout:
сначала выясняется результат координации Game. Восстановление — connected tickets
с прежним приоритетом и снижение доли disconnected; тестовый logout очищает своё участие.

## Command rate limited

Более 10 ответов 429 за 2 минуты: сравните интенсивность команд и клиентские повторы,
проверьте Redis и gateway/auth limiter. Не отключайте лимиты и единственную сессию.
Исправьте цикл повторов, соблюдайте backoff и повторяйте тот же commandId.
Восстановление — снижение 429 при обычном потоке и успешная авторизованная команда.

## Session error burst

Ошибки 5xx Auth: проверьте PostgreSQL/Redis, pool, наличие текущих login keys из Vault
и внутренний Game logout API. Не сбрасывайте сессии массово: CLOSING восстанавливается
повтором операции. Восстановление — нормальный login/introspection/logout и снижение 5xx.

## Database pool saturated

Hikari active/max не ниже 90% 30 секунд: проверьте lock waits, длительные транзакции,
CPU/IO PostgreSQL, число реплик и суммарный connection budget.
Не увеличивайте pool без бюджета БД. Исправьте зависший запрос/транзакцию либо
снизьте входную нагрузку. Убедитесь, что active падает и pending не растёт.

## Mass game disconnection

Не менее 5 PAUSED и больше половины активных игр: сопоставьте WS connections,
Ingress/Realtime rollout, Redis/NATS/Auth ошибки и клиентскую сеть.
Не меняйте 30-секундные общие бюджеты: после восстановления клиенты получают snapshot,
а не создают новый login/матч. Ожидаемы паузы, resume либо единственный timeout result.
Проверьте снижение paused и восстановление WS; подтверждённые ходы остаются в Game.

## Проверка алерта с реальным отказом

`infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/alert-check.py`
временно обнуляет replicas только User, ждёт firing BackendUnavailable,
в finally восстанавливает прежнее число реплик, ждёт Ready/up и resolved.
PVC и записи не удаляются. На одноузловом стенде это вызывает краткую недоступность
User; запустите без параллельного пользовательского тестирования.
Если процесс принудительно завершён, восстановите replicas User из values и повторите
проверку targets/alerts. После теста выполняется smoke.py.
