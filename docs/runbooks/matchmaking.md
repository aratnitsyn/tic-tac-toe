# FIFO Matchmaking

Реализованы вход, собственный snapshot ticket, отмена поиска, строгий FIFO-выбор пары,
передача в Game и восстановление неизвестных исходов. Готовность и отмена подготовки
матча остаются этапом 7; WAITING_READY пока не завершается по таймеру автоматически.
Возврат и connection-generation адаптер подготовлены и проверены отдельно, но пункт
6.5 остаётся открытым до интеграции с Game/Realtime. Нагрузка 100 000 не подтверждена.

## Локальный запуск

Из корня после запуска PostgreSQL/Redis/NATS по local.md:

```sh
python3 scripts/migrate.py matchmaking
python3 scripts/bootstrap-streams.py
python3 scripts/run-service.py auth
python3 scripts/run-service.py game
python3 scripts/run-service.py matchmaking
python3 scripts/run-service.py api-gateway
```

Каждую длительную команду запускайте в отдельном терминале. Matchmaking требует Go
1.24.0; JVM-сервисы — JDK 25. Внутренний health: http://127.0.0.1:8085/healthz.
Настройки без секретов — services/matchmaking/local.env; runner добавляет PGPASSWORD,
NATS_PASSWORD и отдельный MATCHMAKING_SERVICE_TOKEN из игнорируемых файлов стенда.
PG_DSN и остальные переменные текущего окружения имеют приоритет. Секреты не передавайте
в аргументах процесса. В production задавайте окружение явно; local.env не загружается
самим бинарным файлом. Внешние вызовы используют HTTPS; локальный HTTP соответствует
внутреннему участку после ingress.

## API и порядок

- POST /api/v1/matchmaking/queue: Idempotency-Key UUID и {"expectedVersion":N}, где N —
  версия участия MENU. Успех 201 возвращает QueueTicketResponse.
- GET /api/v1/matchmaking/queue: последний собственный ticket, чужие tickets недоступны.
  До завершения admission snapshot может возвращать 404: повторяйте исходный POST.
- POST /api/v1/matchmaking/queue/cancel: такой же ключ команды и expectedVersion текущего
  QUEUED из GET /api/v1/participation. Успех 200 возвращает MENU. Если назначение победило,
  409 не освобождает игрока; текущий матч доступен через participation snapshot.

Gateway проверяет сессию, заменяет пользовательские identity/service headers, удаляет
Authorization/Cookie перед внутренним вызовом. Прямой вызов Matchmaking без service
credential отклоняется. Web-команды требуют обычную Origin/CSRF-защиту Gateway.

Повтор команды возвращает сохранённый результат, включая исходный serverTime. Новый
payload при том же user/kind/Idempotency-Key даёт IDEMPOTENCY_CONFLICT. Исторический
ответ может показывать AVAILABLE после уже состоявшегося назначения: затем читайте
snapshot участия и ticket. Результаты и операции не очищаются автоматически.

Game — единственный владелец MENU/QUEUED/IN_MATCH. Matchmaking сначала сохраняет запрос
claim с operationId, затем получает подтверждение Game. Под queue_control присваивается
монотонный admissionSequence, совпадающий с initial originalSequence. Это момент входа
в FIFO; порядок прихода HTTP на разных экземплярах не обещается.

Выбор блокирует queue_control и две первые AVAILABLE/connected строки по
originalSequence/admissionSequence. SKIP LOCKED не используется. Создание PAIR operation,
RESERVED обоих tickets и QueueChanged outbox фиксируются вместе. Сеть вызывается после
commit; HTTP ожидание не удерживает queue_control. Повтор AssignPair использует тот же
operationId, matchId, ticket ids, expectedVersion и originalSequence.

APPLIED переводит пару в CONSUMED. PENDING/сетевая ошибка сохраняет RESERVED без timeout
освобождения. REJECTED сверяет обоих через Game: действительный QUEUED ticket снова
AVAILABLE с прежним порядком, остальные CANCELLED. Каждое назначение всё равно проходит
авторитетный CAS Game, даже если проекция успела устареть после сверки.

## Зависшие операции и replay

1. Проверьте доступность Game, PostgreSQL и NATS; просмотрите логи по operationId. Pending
   reconciliation/outbox логируется без токенов и содержимого запросов.
2. В собственной БД Matchmaking найдите queue_operation с response_status IS NULL и
   tickets с pair_id. Разрешённые диагностические запросы:

```sql
SELECT operation_id,kind,created_at FROM queue_operation
WHERE response_status IS NULL ORDER BY created_at;
SELECT ticket_id,status,original_sequence,pair_id FROM queue_ticket
WHERE status='RESERVED' ORDER BY original_sequence;
```

3. Reconciler повторит запрос автоматически. Для одной сохранённой операции:

```sh
python3 scripts/reconcile-matchmaking.py <operation-uuid>
```

Утилита читает исходный game_payload, сперва запрашивает Game operation и при отсутствии
посылает именно прежнюю команду. Она не вызывает selector и не создаёт новую операцию.
При PENDING возвращает ошибку и оставляет данные для следующей попытки. Повтор завершённой
операции возвращает сохранённый результат без новых событий/матчей. Ответ 409 означает
сохранённый отказ исходной команды, а не необходимость удалить ticket вручную.

Не меняйте pair_id, original_sequence, статус Game или participation вручную, не
переводите RESERVED в AVAILABLE по возрасту и не удаляйте неизвестную операцию. Устраните
причину отказа инфраструктуры и повторите прежний operationId. NATS outage задерживает
outbox, но не откатывает принятую команду; после восстановления publisher продолжает с
publish ack и eventId. Parking/replay событий описаны в events.md.

## Подготовленный возврат (6.5)

Service.Return применяется в транзакции inbox. Он сверяет ticket/user/originalSequence,
версию и текущий ресурс Game, принимает только CONSUMED, при ещё RESERVED требует retry,
не трогает более новое участие и сохраняет исходный приоритет. Уже зарезервированные
пары не вытесняются. Availability хранит connected и generation: отключённый ticket
не выбирается, устаревшее поколение не отменяет reconnect.

Эти адаптеры пока не подключены к производящим ReturnToQueue/connection событиям:
Game отмену подготовки и Realtime connection lifecycle ещё предстоит реализовать.
Нельзя считать отключения и возврат после реальной отмены готовыми. Этап 7 должен
добавить consumer с inbox/ack/parking, проверить ранний возврат до CONSUMED, повторную
отмену при втором отключении и гонки возврата с новым назначением. Это согласованная
граница этапа 6, а не изменение правил продукта.

## Проверки

```sh
python3 scripts/check-command-stores.py --only go --service matchmaking
scripts/check-go.sh
python3 scripts/benchmark-matchmaking.py
JAVA_HOME="$(/usr/libexec/java_home -v 25)" python3 scripts/check-matchmaking-runtime.py
```

Для smoke сначала соберите Auth/Game/Gateway JAR и `go build -o build/matchmaking .`
в services/matchmaking. Smoke создаёт четыре тестовых аккаунта и один WAITING_READY матч,
сохраняет их в локальной БД, останавливает только свои процессы. Проверяет FIFO A/B/C,
доверенную identity, повторы admission, потерянный AssignPair response после Game commit,
рестарт, ручной replay и отмену. Это не тест игровой логики или готовности.

PostgreSQL-тесты используют отдельные удаляемые схемы и race detector: параллельный
admission, конкурентные selectors, locked head, crash до/после Game, PENDING, оба порядка
и реальная конкурентная гонка cancel/assign, generation и подготовленный возврат/inbox.
Benchmark использует 400 tickets, 4 selectors и 200 reservations в отдельной схеме;
измеряет throughput, latency с ожиданием блокировок и samples ожидающих queue_control.
Game/HTTP и полный пользовательский поток в этот microbenchmark не входят. Результат —
в docs/performance/matchmaking-local.md, не доказательство целевой вместимости.
