# Подготовка матча

Game владеет готовностью и сроками обоих игроков независимо от источника пары.
На формировании WAITING_READY сохраняется общий readyDeadline = DB now +10 секунд.
Один ready не меняет этот срок. Оба своевременных подтверждения переводят матч
в COUNTDOWN со startDeadline = DB now +5 секунд. По сроку одной транзакцией
создаются пустая партия X_TURN, случайное назначение X/O, независимые запасы
восстановления по 30 секунд и turnDeadline = DB now +5 секунд; матч становится RUNNING.
Ходы, завершение и пауза начавшейся партии реализованы; см. [play.md](play.md).

## API и версии

Внешние маршруты защищены Gateway: HTTPS `/api/v1/matches/{matchId}` (GET snapshot)
и `/api/v1/matches/{matchId}/ready` (POST). Локально используется HTTP на 127.0.0.1.
POST требует UUID Idempotency-Key и тело `{ "expectedVersion": 0 }` с версией
**матча**, а не participation. Чужой пользователь получает 403; гость — 401.
Gateway заменяет trusted user/session/service headers и удаляет bearer/cookie.
Internal Game API отсутствует среди публичных маршрутов.

После первого подтверждения версия растёт. Второму игроку с устаревшей версией
нужно получить snapshot и повторить ready с новой версией и новым commandId.
Повтор того же commandId и payload возвращает сохранённый исторический ответ;
изменённый payload — IDEMPOTENCY_CONFLICT. Новое подтверждение уже готового
участника не меняет состояние. Клиентское время не участвует в решении.

После блокировок Game сначала проверяет наступивший срок, затем expectedVersion
и допустимость команды. Равенство readyDeadline опоздало. Подтверждение после
отмены не возобновляет матч. GET snapshot также применяет наступившие сроки.

## Отмена и возврат

| Причина | Игрок A | Игрок B |
|---|---|---|
| Никто не подтвердил | MENU | MENU |
| Подтвердил только A | Исходная QUEUED/LOBBY | MENU |
| Подтвердил только B | MENU | Исходная QUEUED/LOBBY |
| Оба подтвердили до срока | COUNTDOWN → RUNNING | COUNTDOWN → RUNNING |
| A отключён в WAITING_READY/COUNTDOWN | MENU | По источнику независимо от ready |
| Оба отключены до старта | MENU | MENU |

Отмена не создаёт партию, результат или поражение. В одной транзакции снимаются
active membership, изменяется participation каждого игрока, записываются outbox.
Для очереди сохраняются исходные ticketId/originalSequence, отправляется ReturnToQueue.
Matchmaking consumer проверяет актуальный Game participation и коммитит inbox
с локальным AVAILABLE; ранний event при RESERVED повторяется после финализации pair.
Уже закреплённые пары не вытесняются. ParticipationChanged убирает устаревшую
доступность после отзыва возврата, не отменяя новый ticket.

Для Lobby Game восстанавливает room registration/slot единственного вернувшегося;
он становится владельцем даже если раньше был гостем. Lobby consumer меняет код,
обновляет состав и применяет event вместе с inbox. Событие до локального завершения
JOIN откатывается и повторяется. match/version fence защищает новую подготовку.

Второй disconnect может прийти после первой отмены. Game снимает только ещё
не потреблённый автоматический возврат с той же participation version/resource.
Если игрок уже начал новый поиск/матч, событие старого матча его не меняет.
Повтор MatchCancelled с большей версией позволяет закрыть уже возвращённое лобби.

## Соединения и временные границы

Служебный POST `/internal/v1/connections/observe` принимает контракт Connection:
operationId, userId, matchId, generation, connected; Idempotency-Key = operationId.
Требуется Game service credential. Новое поколение заменяет старое; close текущего
поколения применяется однократно, reconnect требует нового поколения. Старый close
не отключает новое поколение, событие адресовано конкретному матчу.

Для RUNNING наблюдения управляют PAUSED и восстановлением с сохранённым остатком
хода и общими запасами; см. play.md. FINISHED не меняется. Источник socket-событий, durable leases и их renew — этап 10;
в данном этапе close проверяется через служебный HTTP API. Начальные участники
считаются подключёнными до соответствующего наблюдения. Это ещё не проверка WSS.
Если close проверяется ровно в startDeadline, сначала применяется наступивший старт;
если раньше — отмена; после наступившего старта применяется пауза партии. В тестах время управляемое, в runtime — PostgreSQL clock_timestamp.

## Workers и восстановление

game_match хранит индексированные ready/start deadlines; удаление или повтор
процессной задачи не сбрасывает их. Worker использует COORDINATION_RETRY_DELAY_MS
из services/game/local.env и повторно читает актуальное состояние под блокировками.
Несколько workers могут увидеть один id: переход и создание game защищены
транзакцией, блокировками и PK game.match_id. CANCELLED/FINISHED не стартуют повторно.
Порядок блокировок — отсортированные participation, затем match, при возврате room.
Незавершённая coordination operation даёт retriable 503, не освобождает участие.

При сбое записи события откатываются партия, символы и переход состояния.
После commit и потери ответа повтор ready возвращает сохранённый ответ.
Не исправляйте состояние ручным DELETE и не продлевайте дедлайны при рестарте.
Проверяйте backlog outbox/inbox/parking по общему runbook событий.

## События

| Событие | Агрегат/версия | Содержимое |
|---|---|---|
| MatchReadyRequested | MATCH, текущая версия | Snapshot после создания или первого ready |
| MatchCountdownStarted | MATCH, увеличенная версия | Snapshot COUNTDOWN с startDeadline |
| MatchCancelled | MATCH, увеличенная версия | Snapshot CANCELLED; повторная большая версия при отзыве возврата |
| ParticipationChanged | PARTICIPATION, увеличенная версия | Новое участие каждого изменённого игрока |
| ReturnToQueue | PARTICIPATION, версия возврата | userId, ticketId, originalSequence, participationVersion |
| GameStarted | GAME, 0 | Пустое поле, X/O, X_TURN, turnDeadline и запасы восстановления |

События соответствуют contracts/events/domain-event.schema.json. Outbox publisher
подтверждает broker ack до отметки публикации; consumers используют inbox.
Нет обещания общего порядка между агрегатами. После GameStarted snapshot матча
уже RUNNING; match version и game version независимы.

## Проверка на локальном стенде

Нужны PostgreSQL/Redis/NATS, bootstrap streams, JDK 25 и сборки сервисов.
Из корня:

```sh
python3 scripts/migrate.py game
python3 scripts/migrate.py lobby
python3 scripts/check-command-stores.py --only kotlin --service game
python3 scripts/check-command-stores.py --only kotlin --service lobby
python3 scripts/check-command-stores.py --only go --service matchmaking
services/api-gateway/gradlew -p services/api-gateway build --no-daemon
scripts/check-go.sh
python3 scripts/check-preparation-runtime.py
```

Runtime создаёт шесть случайных аккаунтов и сохраняет локальные тестовые матчи.
Проверяет ready/replay/права, реальный ReturnToQueue через NATS, возврат гостя
в Lobby с ротацией кода, новую пару без старого ready, два последовательных close
и рестарт Game на COUNTDOWN с единственной партией. Останавливает только свои процессы.
Точные границы и транзакционный сбой старта проверяются тестами PostgreSQL;
модельный тест проверяет 48 последовательностей для QUEUE/LOBBY. Производительность,
TLS/Istio, WSS, игровые ходы и исчерпание времени хода этот smoke не проверяет.
