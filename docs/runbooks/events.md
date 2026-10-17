# Transactional outbox

У каждого владельца БД своя outbox таблица и своя реализация publisher.
Kotlin: persistence/Outbox.kt; Go: internal/outbox. Это инфраструктурные компоненты;
постоянные фоновые deployment roles будут подключаться к ним при реализации сервисов.

Доменный callback вызывает enqueue в той же транзакции, где изменяет состояние.
Publisher отдельно захватывает одну непубликованную запись с lease на 30 секунд,
отправляет envelope с eventId в Nats-Msg-Id и ждёт синхронный JetStream publish ack.
Только затем published_at фиксируется в БД с проверкой владельца lease.

При сбое до publish или после ack, но до published_at запись остаётся доступной
после истечения lease. Повтор использует тот же eventId. Broker dedup ограничен
окном: окончательное подавление повторного доменного эффекта относится к inbox.
SKIP LOCKED применяется здесь к независимой доставке, не к строгому FIFO игроков.
Порядок доставки агрегатов не гарантируется; downstream использует версии и resync.

Создание локальных streams:

```sh
python3 scripts/bootstrap-streams.py
```

Команда использует Go module Matchmaking и его закреплённые зависимости;
секреты берёт из локального .env и не печатает. Повтор с той же конфигурацией
безопасен. Отличающаяся конфигурация существующего stream вызывает ошибку:
не удаляйте stream для её обхода, сначала согласуйте миграцию.

Конфигурация infra/local/jetstream.json задаёт file storage, limits retention,
max_age/max_msgs/max_bytes, discard new и одну replica для локального стенда.
На кластерном нагрузочном стенде требуется три реплики и отдельный ресурсный бюджет.
PARKING используется для сообщений, которые consumer не смог применить.

Проверки на реальных PostgreSQL и NATS:

```sh
python3 scripts/check-command-stores.py
```

Тесты создают отдельные временные JetStream streams с уникальными subjects,
проверяют rollback enqueue, commit без publish, сбой после ack и повтор публикации
того же eventId. Временные streams очищаются тестами; DOMAIN_EVENTS не удаляется.
Повторные публикации за пределами dedup window считаются допустимыми.

## Inbox, retries и parking

Inbox сохраняет `(consumer_name, event_id)` в той же транзакции, что доменный
эффект. При повторе зафиксированного события callback не вызывается. При ошибке
оба изменения откатываются; broker ack отправляется только после commit.
Потеря ack даёт повторную доставку и повторное чтение inbox, а не второй эффект.

Регистрация consumer включает explicit ack, durable name, ackWait 30 секунд,
maxAckPending 256 и **неограниченный broker maxDeliver**. Прикладной лимит —
5 попыток: задержки 1, 5, 30 секунд, затем сохранение в `parking.<consumer>`.
Broker limit нельзя ограничить пятью: при недоступном parking stream сообщение
иначе перестало бы доставляться до его сохранения. Ошибка parking publish
оставляет исходное сообщение неподтверждённым и назначает повтор.

Parking хранит consumer, исходный subject и исходные bytes в base64. Его publish
подтверждается до term исходного сообщения. Повторы записи дедуплицируются по
consumer и SHA-256 bytes. Не помещайте в события пароли или токены; parking
не является публичным endpoint. Доступ к нему — только у внутренних операторов.
Retention ограничен конфигурацией: мониторьте возраст, размеры и число сообщений,
не обещайте бесконечное хранение при заполнении или истечении max_age.

## Replay после исправления обработчика

1. Найдите stream sequence нужного сообщения PARKING и убедитесь, что причина
   ошибки исправлена. Не удаляйте inbox и не меняйте original eventId.
2. Выберите UUID операции replay; повтор команды использует тот же UUID.
3. Из корня выполните:

```sh
python3 scripts/replay-parking.py <parking-sequence> <operation-uuid> --confirm
```

CLI разрешает только original subjects под `events.`. Он декодирует исходные bytes
и сохраняет eventId в envelope. Для broker publish используется отдельный
`parking-replay/<operation-uuid>`: повтор с исходным broker Msg-Id внутри dedup
window мог бы не создать новую доставку после term старого сообщения.

Повтор того же replay UUID дедуплицируется брокером; новая replay-команда для уже
применённого eventId не повторяет эффект благодаря inbox. Исходная parking запись
сохраняется для разбора. Некорректный JSON требует отдельного исправления события,
а не слепого replay. Не меняйте существующий eventId ради обхода дедупликации.

Integration tests проверяют rollback, дубликаты после commit, повтор доставки,
parking, отказ parking publisher и replay без повторного доменного эффекта.
