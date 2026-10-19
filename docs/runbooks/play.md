# Партия и надёжные таймеры

Game реализует классическую 3×3 PvP-партию. Подготовка описана в [preparation.md](preparation.md).
Чистая модель `play` не зависит от Spring, PostgreSQL или HTTP: Board, SymbolAssignment,
PlayState, RecoveryState, PlayRule. На старте один раз сохраняется случайное X/O;
первый ход X, каждый новый незавершающий ход получает 5 секунд. Победная линия
имеет приоритет над заполнением поля. PvE, рейтинг и выбор лимита хода отсутствуют.

## API

Внешние маршруты HTTPS, локальная проверка HTTP на 127.0.0.1:

| Метод | Маршрут | Тело/результат |
|---|---|---|
| GET | /api/v1/games/{matchId} | GameResponse с serverTime, version, data |
| POST | /api/v1/games/{matchId}/moves | expectedVersion и cell от 0 до 8 |
| POST | /api/v1/games/{matchId}/surrender | expectedVersion |

Пример хода: `{ "expectedVersion": 0, "cell": 4 }`.
Версия относится к партии, а не match или participation. POST требует UUID
Idempotency-Key. Повтор команды с тем же payload возвращает тот же исторический
ответ без второго эффекта; изменённый payload — IDEMPOTENCY_CONFLICT.
Исторический ответ не заменяет новый snapshot, клиент должен сравнивать версии.

Gateway использует Auth introspection, cookie/CSRF для Web и bearer для native;
удаляет внешние bearer/cookie и подменённые trusted headers. Прямой Game требует
service credential. Гость получает 401, чужой зарегистрированный пользователь — 403.
Неверный формат/координата — 400; занятая клетка, чужая очередь, PAUSED,
завершённая партия и устаревшая версия — 409 с причиной. До старта Game snapshot
возвращает INVALID_STATE; наступившая отмена подготовки при этом сохраняется.
Ходы во время подготовки не создают партию преждевременно.

## Транзакция и точное время

Блокировки: command key → отсортированные participation → match → game.
Незавершённая coordination operation возвращает retriable 503; её участие
не освобождается по возрасту. Ожидание блокировок ограничено 3 секундами.

После получения блокировок берётся PostgreSQL clock_timestamp. Одна временная
точка используется для проверки сроков и самой команды. Сначала применяется
наступивший срок, затем проверяется expectedVersion и правило команды. Поэтому
ровно в turn/recovery deadline уже поздно, даже если worker ещё не сработал.
Синхронный Game snapshot и match snapshot также проверяют сроки. Отсутствие
или потеря задачи worker не даёт дополнительного времени на ход.

Ход меняет одну клетку и увеличивает game version. Поле, следующий срок или итог,
задачи таймеров, результат команды и outbox фиксируются одной транзакцией.
При завершении вместе сохраняются game FINISHED, match FINISHED, active=false
у обоих match members и participation MENU у обоих. Новый поиск не начинается.
Повторные и поздние команды не меняют результат, не освобождают участие нового матча.

## Пауза и общий запас восстановления

Служебный `/internal/v1/connections/observe` использует тот же контракт Connection,
Idempotency-Key и fencing matchId/generation, что подготовка. close текущего поколения
применяется один раз; reconnect требует нового поколения. Старый close не меняет
новое соединение. Источник socket/lease событий и renew остаётся этапом 10;
здесь поведение проверяется реальными служебными HTTP-наблюдениями.

При первом disconnect активного хода сохраняются X_TURN/O_TURN и точный остаток
хода; turnDeadline убирается, партия PAUSED. У отсутствующего игрока сохраняются
серверный disconnectedAt и recoveryDeadline = now + остаток его общего запаса.
Каждый начинает с 30 секунд на всю партию. Второй disconnect не продлевает первый
срок; отсутствие обоих расходует два независимых запаса.

Reconnect до срока сохраняет остаток `deadline - now`. Если другой ещё отсутствует,
пауза продолжается, запас подключённого больше не расходуется. Когда вернулись оба,
восстанавливается сохранённая очередь с `turnDeadline = now + remainingTurn`.
Новое отключение использует оставшийся запас: 12 секунд отсутствия оставляют 18,
а не новые 30. Внутри БД остатки хранятся в наносекундах и timestamptz; текущая
точность DB clock — микросекунды. В API миллисекунды округляются вниз только для
отображения и не используются для принятия решений. Поля recovery_x_ms/o_ms
сохранены как совместимая проекция; авторитетны *_ns и recovery deadlines.

Сначала проверяется уже наступивший turn timeout: запоздалое отключение не может
превратить истёкший ход в новую паузу. В PAUSED выбирается самый ранний recovery
срок независимо от порядка workers. Если оба срока равны — BOTH_DISCONNECTED_TIMEOUT.
Если различаются и worker опоздал за оба — проигрывает тот, чей срок был раньше.
В финале остатки замораживаются на эффективном времени завершения и больше не убывают.

## Результаты

| reason | outcome | Кто побеждает |
|---|---|---|
| WINNING_LINE | WIN | Игрок с победной линией |
| BOARD_FULL | DRAW | Никто, игровая ничья |
| TURN_TIMEOUT | WIN | Соперник текущего игрока |
| SURRENDER | WIN | Соперник сдавшегося, включая PAUSED |
| DISCONNECT_TIMEOUT | WIN | Соперник первым исчерпавшего запас |
| BOTH_DISCONNECTED_TIMEOUT | NO_WINNER | Без победителя, отдельно от ничьей |

winnerId/loserId передаются только для WIN. FINISHED терминален.
При гонке хода, сдачи, timeout и disconnect сериализация и server time дают один
результат и одно событие GameFinished. Явный выход из аккаунта подключён к сдаче через [LogoutParticipation/Auth CLOSING](logout.md).

## Сохраняемые задачи

Миграция V004 расширяет game и создаёт game_deadline:
matchId, kind TURN/RECOVERY_X/RECOVERY_O, generation, expectedVersion, dueAt, lease.
Каждое изменение партии атомарно заменяет задачи. Новое поколение и версия
не позволяют задаче старого хода закончить следующий ход или паузу.
Ранее созданные RUNNING партии получают TURN-задачи при миграции.

Worker по COORDINATION_RETRY_DELAY_MS короткой транзакцией захватывает до 100
независимых due jobs с FOR UPDATE SKIP LOCKED и lease 5 секунд, затем коммитит.
Блокировка deadline не удерживается при ожидании participation/match/game.
Это не FIFO-очередь игроков: в Matchmaking SKIP LOCKED по-прежнему запрещён.

При применении сверяются matchId, kind, dueAt, generation и версия партии.
Затем обрабатывается самое раннее фактически наступившее событие агрегата,
даже если захваченная задача относится к более позднему recovery deadline.
После crash lease истекает и задача доступна другому worker. Повтор уже обработанной
задачи — no-op. Scheduler не хранит единственную копию игрового времени.

## События и восстановление

GameStarted, GameStateChanged, GamePaused, GameResumed и GameFinished содержат Game
snapshot и монотонную версию GAME. Изменение списка отсутствующих при сохранении
PAUSED публикует GamePaused; GameResumed — только при восстановлении обоих.
ParticipationChanged публикуется для обоих при завершении. Match version и game
version независимы; итог GameFinished означает уже зафиксированный FINISHED матча.

Outbox сохраняется вместе с эффектом и публикуется с broker ack; повтор broker
доставки не означает повторный ход. При недоступном NATS принятые команды остаются
в PostgreSQL, события ожидают публикации. Не удаляйте game/participation/команды
или таймеры для ручного «исправления» результата. После инфраструктурного сбоя
восстановите доступ к БД/NATS и проверьте outbox/parking по [events.md](events.md).
Рестарт не продлевает сроки. SIGKILL-проверка не является доказательством RPO/RTO
при потере PostgreSQL-кластера; резервное копирование и нагрузочная приёмка впереди.

## Проверки и запуск

После локального стенда из [local.md](local.md), bootstrap streams и JDK 25:

```sh
python3 scripts/migrate.py game
python3 scripts/check-command-stores.py --only kotlin --service game
services/api-gateway/gradlew -p services/api-gateway build --no-daemon
python3 scripts/check-play-runtime.py
python3 scripts/check-preparation-runtime.py
```

Runtime требует собранных Auth/Game/Lobby/Gateway JAR, создаёт три случайных аккаунта
и три матча, сохраняет данные, останавливает только свои процессы. Проверяет
подтверждённый ход и тот же replay после SIGKILL, сохранность символов, рестарт в PAUSED,
неизменный recovery deadline/остаток хода, reconnect, fencing старого close,
сдачу, MENU обоих, фоновый timeout и единственный GameFinished.

Тесты PostgreSQL с управляемым временем проверяют границы, 12+18 секунд, оба срока,
позднюю обработку, stale generation, конкурентные исходы, сбой финального outbox и
откат command/game/match/participation. Отдельный тест использует реальный DB clock
после ожидания row lock. Worker crash проверяется восстановлением lease и повтором
задач. Чистые тесты покрывают восемь линий, выигрыш последней клеткой, ничью и первый X.
Это не приёмка 100 000 пользователей, TLS/Istio или WSS. Сквозной logout проверяется отдельно — [logout.md](logout.md).
