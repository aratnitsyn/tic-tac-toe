# Realtime: соединения, поколения и синхронизация

Realtime реализован на Go. Внешний endpoint — `wss://<host>/wss`, TLS завершает
Istio ingress. Сам сервис принимает внутренний WS `/wss`. Kubernetes/Istio и
проверка внешнего TLS относятся к этапу 14; локальный smoke использует внутренний
WS на 127.0.0.1 и не доказывает готовность ingress или вместимость 100 000.

## Запуск

Нужен локальный PostgreSQL/Redis/NATS стенд из local.md и собранные
Auth, Game, Lobby, Matchmaking и Gateway. Из корня:

```sh
python3 scripts/migrate.py game
python3 scripts/migrate.py matchmaking
python3 scripts/migrate.py realtime
python3 scripts/bootstrap-streams.py
(cd services/realtime && go build -o build/realtime .)
python3 scripts/run-service.py realtime
```

`local.env` содержит несекретные настройки; runner подставляет credentials из
игнорируемых файлов локального стенда. Для прямого запуска нужны те же переменные
окружения. `REALTIME_ROLE=socket|dispatcher|all`; all предназначен для локального
запуска. Каждый socket-процесс получает уникальный `REALTIME_POD_ID` (без точек,
пробелов и NATS wildcard). Два процесса не должны иметь одинаковое имя.
Socket pods подписаны на собственные `realtime.pod.<podId>` без queue group.
Dispatcher workers делят durable pull consumer `realtime-dispatch-v1` в
DOMAIN_EVENTS, фильтр `events.*`. Их health endpoint — `/healthz`.

## Протокол

1. Авторизованный HTTPS `POST /api/v1/auth/ws-ticket` выдаёт одноразовый ticket
   на 30 секунд. Новый reconnect использует новый Idempotency-Key и новый ticket,
   сохраняя сессию; повторный login не нужен.
2. Первый текстовый frame — `{"type":"auth","ticket":"..."}`. Ticket не передают
   в URL, cookie или Authorization socket-запроса. Auth атомарно погашает ticket.
   Web Origin обязан совпадать с WEB_ORIGINS; отсутствие Origin допустимо для native.
   Запрос с query string, неподходящим Origin, лишними полями или бинарным frame
   отклоняется. На auth даётся 5 секунд, размер входящего сообщения — 4096 байт.
3. `auth_ok` содержит connectionGeneration. Перед ним сервер проверяет текущую
   сессию, ownership и подтверждение Game lease для обнаруженного активного матча.
4. `subscribe` с matchId проверяет членство через Game. Сервер устанавливает
   подписку и отправляет `resync_required` как подтверждение барьера подписки.
   После этого клиент читает HTTP snapshots, буферизуя пришедшие события.
   Чужой матч закрывает соединение. Команды ходов остаются в HTTPS API.
5. Heartbeat каждые 2 секунды с новым requestId продлевает сессию через Auth и
   lease. После 6 секунд без корректного frame socket закрывается; heartbeat_ack
   не выдаётся при отказе авторитетной проверки. Сессия проверяется также перед
   подписками, ack и выдачей событий; CLOSING не даёт доступ.
6. Клиент подтверждает eventId/aggregateId/aggregateVersion. Повтор ack безопасен.
   До 64 неподтверждённых событий, ожидание ack — 5 секунд; затем resync_required.
   Очередь socket ограничена 64 сообщениями; переполнение закрывает только медленный
   socket. Запись ограничена 2 секундами. Соединение после переполнения восстанавливают
   с новым ticket и snapshots.

## Generation и leases

Поколение выдаёт logged PostgreSQL sequence Realtime, общая для socket pods.
Redis хранит userId → pod/session/generation с TTL 6 секунд. Lua CAS не даёт
запоздавшему claim/renew/release заменить новое поколение. После потери Redis
поколение не начинается заново. Ownership не является источником состояния игры.
Старый socket при обнаружении замены не отправляет disconnect; даже доставленное
позднее наблюдение старого поколения отбрасывается Game.

Game хранит generation и connection_lease_until в match_member. Начальный lease
формирования пары — 6 секунд; последующие подтверждения используют серверное время.
Realtime собирает renew в пакеты до 1000 записей; окно пакетирования — 10 мс.
Просроченный или отключённый lease того же поколения не продлевается; reconnect
требует нового поколения. Сроки живут в PostgreSQL, не в памяти socket pod.
Worker после SIGKILL применяет идемпотентный disconnect с сохранённым поколением;
повтор не даёт второго перехода. Завершённые матчи не сканируются как потерянные.

После отмены подготовки живой socket продолжает продлевать старый match lease
до следующего матча/разрыва: поздний disconnect может отозвать ещё не потреблённый
автоматический возврат. Game проверяет версию участия, поэтому старый матч не
освобождает игрока из нового матча. Находившееся только в меню соединение не создаёт
записей Game lease.

Matchmaking получает защищённый `POST /internal/v1/availability` с userId,
generation и connected. Heartbeat продлевает собственный PostgreSQL срок
availability_until. После смерти pod просроченный ticket не выбирается; участие
QUEUED, ticket и originalSequence сохраняются. Reconnect возвращает доступность,
не вытесняя уже RESERVED пару. Этот путь закрывает отложенную часть 6.5.

Важно: 5-секундный срок хода продолжает идти до обнаружения отключения.
При SIGKILL сразу после heartbeat TURN_TIMEOUT может наступить раньше 6-секундного
lease; это согласованная семантика, а не продление хода на время детектора.
Общий запас восстановления 30 секунд расходуется от обнаружения Game.

## Доставка и клиентская сверка

Dispatcher использует inbox вместе с commit перед ack JetStream. После lookup
ownership отправляет best-effort Core NATS сообщение на адресный pod subject;
внутри сообщения проверяются userId/generation и адресаты envelope. Отсутствие
ownership не восстанавливают из чужих БД. Неизвестный результат доставки не
означает отмену команды. Повтор publish безопасен для клиента по версии.

`services/realtime/internal/syncclient` — тестовый клиент синхронизации,
согласованный для 10.5–10.6. Он читает socket независимо от HTTP snapshots,
ограничивает буфер 64 frame, подтверждает события, сверяет authority на gap,
reconnect, foreground и каждые 5 секунд. MATCH и GAME имеют разные последовательности
версий даже при общем matchId. Старый snapshot не откатывает новое состояние.
Последнее потерянное событие исправляет периодическая проверка без следующего
события. При отказе HTTP сохраняется последнее состояние и повторяется сверка;
это не успешное подтверждение непрочитанного события. Reconnect получает новый
ticket в той же сессии и повторяет подписки перед snapshot.
Алгоритм будет перенесён в Web/iOS/Android на этапах 11–13; их UI ещё не реализован.

## Сбои и диагностика

- Нет heartbeat_ack: проверить Auth ACTIVE/CLOSING, доступность Redis, сроки и
  generation в собственных Game API/логах. Не очищать участие или session вручную.
- Reconnect постоянно заменяет socket: проверить уникальность podId и наличие
  второго соединения той же сессии. Не уменьшать sequence и не сбрасывать fencing.
- Потеря Redis закрывает непроверяемые socket, но не удаляет матчи, FIFO и участие.
  После восстановления нужен новый ticket/generation. Game обнаруживает отсутствие
  renew по PostgreSQL lease. Во время партии возможны обычные поражения по срокам.
- NATS outage/lag: проверить consumer lag DOMAIN_EVENTS и PARKING. Нельзя сбрасывать
  durable consumer/inbox ради уменьшения lag: это может скрыть необработанные данные.
  Core NATS не является durable доставкой до устройства; snapshot сверка обязательна.
  Publisher/dispatcher retry и parking не заменяют клиентский ack.
- Отставание lease worker: проверить due index, DB connections/lock waits и scheduler
  Game. Рестарт не сбрасывает дедлайны; массовой аварийной отмены матчей в v1 нет.

Метрики/алерты для lag, overflow, поколений и late timers внедряются в этапе 14.
До них используйте логи процессов, NATS consumer info и PostgreSQL запросы только
к БД её владельцем. Не записывайте tickets, session token или service credentials.

## Проверка

```sh
scripts/check-go.sh
JAVA_HOME="$(/usr/libexec/java_home -v 25)" python3 scripts/check-command-stores.py --service game
JAVA_HOME="$(/usr/libexec/java_home -v 25)" python3 scripts/check-command-stores.py --service auth
python3 scripts/check-command-stores.py --only go
(cd services/realtime && go build -o build/check-realtime ./cmd/check-realtime)
JAVA_HOME="$(/usr/libexec/java_home -v 25)" python3 scripts/check-realtime-runtime.py
```

Runtime smoke создаёт два аккаунта и матч; запускает свои Auth/Game/Lobby/Gateway,
Matchmaking без автоматического selector (интервал 1 час), два socket-процесса и
отдельный dispatcher. Выполняет ход, SIGKILL socket, проверяет PAUSED, убивает и
перезапускает Game, проверяет snapshot/reconnect на заменяющий pod и терминальную сверку после TURN_TIMEOUT.
Останавливает только свои процессы; тестовые записи сохраняет.

Redis/NATS интеграционные fault tests обрывают соединения своих клиентов,
не останавливая общий стенд. Они проверяют fencing, отказ в доступе без ownership,
остановку dispatcher при потере NATS, адресную доставку двум pods и inbox.
Общий отказ кластера/вместимость и внешний HTTPS/WSS ingress здесь не измеряются.
