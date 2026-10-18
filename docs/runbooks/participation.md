# Game: участие и координация

Game — единственный владелец `MENU`, `LOBBY`, `QUEUED`, `IN_MATCH`.
User и Redis не принимают решений о занятости. В этапе 4 реализованы реестр,
регистрация комнаты, закрепление пары, восстановление операций и snapshot.
Готовность, отсчёт, старт и отмена по deadline реализованы; см. [подготовку](preparation.md).
Ходы и завершение партии, leases Realtime и logout остаются задачами последующих этапов.

## Запуск

```sh
python3 scripts/migrate.py game
python3 scripts/bootstrap-streams.py
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
python3 scripts/run-service.py game
```

Нужен локальный PostgreSQL/NATS стенд. Несекретные настройки находятся в
`services/game/local.env`; пароль БД и NATS поступают из `infra/local/.env`,
служебный credential — из игнорируемого `infra/local/secrets/services.json`.
Game не создаёт фиктивного успешного ответа LogoutParticipation для Auth.

Авторизованный клиент получает `GET /api/v1/participation` через Gateway:
`serverTime`, `version`, `data` с userId/state/version и необязательным resourceId.
Первый snapshot свободного аккаунта — MENU, версия 0; каждый переход добавляет 1.
Переподключение читает тот же реестр и не сбрасывает версию или участие.
Gateway проверяет сессию и перезаписывает user/session/service заголовки из
результата introspection, удаляя исходные Authorization и Cookie перед Game.
Game требует свой служебный credential на всех API, кроме health.
Закрытие прямого внешнего доступа обеспечивается будущим этапом Kubernetes/Istio.

## Внутренние команды

Внутренний HTTP внутри mesh: `/internal/v1`, `X-Service-Token` для Game.
`Idempotency-Key` обязан совпадать с `operationId` в JSON. Необязательный
`X-Correlation-Id` — UUID, по умолчанию operationId. При повторе сохраняется
correlationId исходной операции. Служебные credentials не являются сессиями
игрока и передаются только доверенным backend callers.

- `POST /participation/claim`: MENU → QUEUED с resourceId=ticketId;
  QUEUED/ожидающее LOBBY → MENU; MENU → присоединение к зарегистрированному
  LOBBY с resourceId=roomId. Последний случай атомарно закрепляет обоих за
  новым WAITING_READY. Произвольный переход в IN_MATCH запрещён.
- `POST /rooms/register`: создаёт активную регистрацию комнаты, слот создателя
  и его LOBBY в одной транзакции. Повтор operationId возвращает исходный
  результат, другая регистрация занятого roomId отклоняется. Ожидающий хозяин
  может выйти в MENU, закрыв регистрацию. Закрытый roomId нельзя использовать
  для новой регистрации; новый жизненный цикл требует нового resourceId.
- `POST /matches/assign-pair`: два разных QUEUED игрока, expectedVersion и
  соответствующий ticketId каждого; originalSequence сохраняется без изменения.
  Game не выбирает FIFO-пару вместо Matchmaking. Создаются WAITING_READY,
  два активных member и общий readyDeadline = DB time + 10 секунд.
- `GET /operations/{operationId}`: PENDING/APPLIED/REJECTED; неизвестный ID — 404.

resourceId участия: null/отсутствует для MENU, roomId для LOBBY, ticketId для
QUEUED, matchId для IN_MATCH. У комнаты максимум два слота; у активного матча
максимум два разных участника; один пользователь не занимает два активных
матча, два места или два участия. Один QUEUED ticket не принадлежит двум игрокам.
При формировании пары слоты комнаты освобождаются, регистрация закрывается для
новых входов. Будущий возврат в лобби выполняется отдельным переходом этапа 7,
а не открытием старого кода по запоздалой проекции Lobby.

source матча — QUEUE либо LOBBY. sourceRef для QUEUE — pair operationId,
для LOBBY — roomId. Для очереди member сохраняет ticketId/originalSequence,
для лобби эти поля отсутствуют. Match version начинается с 0; назначение пары
переводит версии participation из 1 в 2. Новый матч не создаётся повтором команды.

В Operation.version: версия participation при регистрации/входе/выходе из очереди;
версия нового матча при формировании пары (0). Operation.resourceId — roomId,
ticketId либо matchId по результату; для возврата в MENU отсутствует.
Результат REJECTED доступен через GET; повтор исходного POST возвращает
сохранённую причину отказа. Snapshot показывает текущее участие, а результат
команды — исторический исход, который не меняется после последующих переходов.
Изменение payload того же operationId, включая kind или порядок players,
даёт IDEMPOTENCY_CONFLICT. Порядок полей JSON не влияет на сравнение.

## Транзакции, блокировки и восстановление

1. Транзакция резервирования: lock operationId, создание операции, блокировки
   participation по возрастанию канонической строки UUID, запись участников
   операции и operation_id в participation. Публичное состояние пока прежнее.
2. Транзакция применения: тот же operationId → те же отсортированные пользователи
   → room/match resource lock. Проверяются версии, состояние и ресурс.
   Изменения участия/слотов/матча, результат операции и outbox фиксируются вместе.
3. Worker повторяет PENDING из PostgreSQL. Любой экземпляр может продолжить
   операцию; блокировки сериализуют повтор без второго эффекта.

operation_id в строке participation — единственная незавершённая операция
этого пользователя; это не пятое состояние публичного автомата.
При бизнес-отказе изменения откатываются до savepoint, операция становится
REJECTED, снимается только её собственная резервация. При сбое БД или процесса
PENDING и резервация сохраняются. При откате первой транзакции их нет вообще.
Timeout HTTP не означает отмену: повторяется тот же POST либо запрашивается GET.
Нельзя освобождать участие или удалять marker по возрасту неизвестной операции.

На пользователя и ресурс блокировки всегда берутся в одном порядке. Даже
инициализация отсутствующего participation выполняется в порядке userId.
Уникальные ограничения БД дополнительно защищают userId, room/slot, активного
member и ticket. lock_timeout — 3 секунды: тайм-аут/недоступность БД даёт 503,
а не успешную компенсацию. Операции и их результаты пока не очищаются по TTL.

## События и трассировка

ParticipationChanged записывается при каждом принятом переходе;
MatchReadyRequested — ровно один раз при создании пары. Envelope содержит
correlationId, causationId=operationId, aggregateVersion и recipientIds.
Publisher использует существующую outbox с eventId и JetStream publish ack.
Сбой публикации не отменяет уже принятую команду; delivery повторяется.

После commit логируется `Coordination committed` с operationId, correlationId,
kind и status, без token/payload. Связь можно восстановить по
coordination_operation.correlation_id → game_match.correlation_id →
outbox.envelope.correlationId и causationId. Это корреляция операций;
развёртывание полноценного tracing/dashboard относится к этапу 14.

## Проверки

```sh
python3 scripts/check-command-stores.py --only kotlin --service game
services/api-gateway/gradlew -p services/api-gateway clean build --no-daemon
python3 scripts/check-participation-runtime.py
```

Последний сценарий требует собранных Auth/Gateway/Game JAR и миграций Game.
Он создаёт два локальных аккаунта и один WAITING_READY матч, останавливает Game,
сохраняет контролируемую PENDING-резервацию, запускает новый процесс и проверяет
восстановление worker-ом, ещё один рестарт и стабильный replay, snapshot через
Gateway и корреляцию пяти outbox events. Процессы останавливаются автоматически;
тестовые аккаунты/матч остаются в локальной БД, секреты не печатаются.
Интеграционные JVM-тесты используют отдельные временные схемы: проверяют CAS,
unique constraints, гонки join/queue/cancel/assign, fault injection перед commit
резервации и после доменного эффекта, отсутствие лишнего outbox при retry.
