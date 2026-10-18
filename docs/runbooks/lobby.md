# Lobby: приватные приглашения

Lobby хранит код и метаданные комнаты в собственной PostgreSQL БД. Game — единственный
владелец участия и мест: Lobby не обращается к его БД и не принимает решение по числу
участников локальной проекции. После второго игрока Game атомарно создаёт WAITING_READY
с общим readyDeadline +10 секунд. Готовность, отмена и запуск реализованы в Game;
см. [подготовку](preparation.md).

## Локальный запуск

Требуются JDK 25 и работающий стенд из [local.md](local.md). Из корня:

```sh
python3 scripts/migrate.py auth
python3 scripts/migrate.py lobby
python3 scripts/migrate.py game
python3 scripts/bootstrap-streams.py
python3 scripts/run-service.py auth
python3 scripts/run-service.py game
python3 scripts/run-service.py lobby
python3 scripts/run-service.py api-gateway
```

Сервисы запускаются в отдельных терминалах. Gateway/Auth/Game должны быть собраны при
запуске runtime-проверки через JAR. Локальные адреса и лимиты находятся в local.env;
секрет Lobby создаётся в игнорируемом infra/local/secrets/services.json. Существующие
Auth/Game credentials при добавлении Lobby сохраняются. Окружение процесса имеет приоритет.
Прямой запуск JAR требует передачи всех переменных окружения.

Внешний контракт: HTTPS `/api/v1/lobbies/**`. Внутри локального стенда используются
HTTP Gateway :8080, Lobby :8083 и Game :8084 на 127.0.0.1. TLS/Istio будут проверены
на этапе 14; локальная проверка не подтверждает TLS или нагрузочную вместимость.
Gateway выполняет introspection, CSRF/Origin для Web, удаляет Authorization/Cookie из
запросов к Lobby и заменяет user/session/IP/service заголовки. Прямой Lobby требует
служебный credential; он не доверяет внешнему bearer. Internal API Game наружу не открыт.

## API

Все команды требуют UUID Idempotency-Key и целочисленный неотрицательный expectedVersion
текущего participation в Game. Поля сверх контракта отклоняются.

| Метод | Путь | Назначение |
|---|---|---|
| POST | /api/v1/lobbies | Создание, тело `{ "expectedVersion": 0 }`, ответ 201 LobbyResponse |
| POST | /api/v1/lobbies/join | Вход, тело с expectedVersion и code, ответ ParticipationResponse IN_MATCH |
| GET | /api/v1/lobbies/{lobbyId} | Собственная комната; чужая — NOT_FOUND |
| POST | /api/v1/lobbies/{lobbyId}/leave | Выход из ожидающей комнаты, ответ ParticipationResponse MENU |

Код состоит из 10 криптографически случайных символов A–H/J–N/P–Z/2–9. Уникальный индекс
защищает от коллизии; генерация повторяется до 10 раз, затем SERVICE_UNAVAILABLE.
Автоматического пользовательского срока действия кода нет. REGISTERING — внутренний статус:
код ещё не открывается, GET собственной незавершённой комнаты возвращает 503, join — 404.
Публичные статусы: WAITING, PREPARING, CLOSED. CLOSED/PREPARING не принимают новых гостей.

Первый успешный join делает обоих IN_MATCH; остальные получают отказ без освобождения
участия. Leave конкурирует с join в Game: либо MENU и закрытая комната, либо
PARTICIPATION_CONFLICT и сохранённый матч. Повтор команды с тем же ключом возвращает
исторический ответ; состояние затем проверяется через GET participation/комнаты.
Изменение payload или lobbyId в повторе leave — IDEMPOTENCY_CONFLICT.

## Восстановление операции

1. В транзакции Lobby сохраняет room_operation с постоянными operationId, roomId,
   game_payload и correlationId. Новая комната сохраняется REGISTERING.
2. Вне транзакции обращается к результату Game. При NOT_FOUND отправляет исходную команду
   с тем же operationId; при PENDING не выполняет компенсацию.
3. После APPLIED/REJECTED атомарно сохраняет ответ, метаданные и LobbyChanged в outbox.
   Только подтверждённое создание открывает код. Код отказа берётся из результата Game.
4. Worker повторяет незавершённые операции. Перезапуск не генерирует новый идентификатор
   и не освобождает участие по времени. Сбой Game даёт клиенту 503; повторять нужно тот же
   запрос с тем же Idempotency-Key. Нельзя заменять ключ для «исправления» неизвестного исхода.

Для ручной диагностики в БД Lobby найдите room_operation по user_id/kind/command_id,
проверьте operation_id, correlation_id, наличие response. Через внутренний Game API
`GET /internal/v1/operations/{operationId}` проверьте PENDING/APPLIED/REJECTED с Game
credential из secret storage. При NOT_FOUND повторите сохранённый game_payload по
`POST /internal/v1/rooms/register` для CREATE или `/internal/v1/participation/claim`
для JOIN/LEAVE с **тем же** operationId/Idempotency-Key и correlationId. Затем дайте
worker Lobby завершить запись либо повторите исходный публичный запрос. Не публикуйте
credential в URL, shell history или логах. Не удаляйте participation/room_slot вручную,
не переносите строки между БД и не создавайте новый идентификатор для replay.

Результат Game REJECTED не меняет чужой слот; REGISTERING-комната закрывается.
Результат APPLIED восстанавливается без второго матча. Опубликованный outbox повторяется
с тем же eventId; потребители должны применять версии и inbox дедупликацию.

## Лимиты кода

Каждая авторизованная попытка join, включая неверный формат/несуществующий код, учитывается
в PostgreSQL. По умолчанию — 10 попыток на пользователя и 30 на IP за фиксированное окно
60 секунд с первой попытки. Лимиты общие для реплик, задаются LOBBY_CODE_USER_LIMIT и
LOBBY_CODE_IP_LIMIT; IP берётся из перезаписанного Gateway заголовка. Время — БД,
равенство концу окна открывает новое окно. Превышение — RATE_LIMITED/429 без вызова Game.
Повторы join также учитываются: после 429 дождитесь нового окна и повторите прежний ключ.

## Возврат после отмены

Consumer events.MatchCancelled с durable lobby-return-v1 использует inbox/outbox.
Game восстанавливает регистрацию и слот единственного вернувшегося участника;
для source=LOBBY consumer сверяет актуальное участие, matchId и source version,
ротирует код и передаёт владение комнатой даже бывшему гостю. Если никто не вернулся,
комната закрывается. Новая пара получает новую готовность и новый matchId.

При незавершённой локальной операции JOIN/LEAVE событие откатывается и повторяется,
поэтому ранняя отмена не теряется между commit Game и commit Lobby. Повтор eventId
не повторяет эффект. Старый матч не меняет новую пару. Для второго close после уже
применённого возврата сохраняются return_match_id и return_participation_version:
большая версия отмены закрывает ещё не потреблённый возврат, не затрагивая новое участие.

Сквозная ротация, передача владения гостю, новая пара и второй close проверяются
scripts/check-preparation-runtime.py; тесты раннего event/inbox и старого match — в Lobby.
Ходы, результат и освобождение после сыгранной партии остаются этапом 8.

## Проверки

```sh
python3 -m unittest discover -s scripts/tests -v
python3 scripts/check-command-stores.py --only kotlin --service lobby
services/api-gateway/gradlew -p services/api-gateway clean build --no-daemon
python3 scripts/check-lobby-runtime.py
```

Unit/integration Lobby проверяют коллизию, недоступную незавершённую комнату, durable
replay и конфликт payload/resource, закрытие, доступ, лимиты с границей времени БД,
дедупликацию/ротацию consumer, ранний event до commit JOIN и старый match. Runtime поднимает реальные Auth/Gateway/
Lobby/Game, проверяет три конкурентных join, гонку join/leave, отказ leave после назначения,
теряет ответ Game после commit, перезапускает Lobby, проверяет одну комнату/место и повтор,
закрытие и HTTP rate limit. Создаёт пять локальных аккаунтов и до двух WAITING_READY матчей;
секреты не выводит, процессы останавливает. Остановите другие экземпляры Lobby перед
проверкой сбоя: чужой reconciler может успеть завершить специально оставленную операцию.
