# Контракты первой версии

Это описание API для последующей реализации, а не подтверждение наличия endpoints.

- `http/public.openapi.json`: HTTPS base URL `https://<host>/api/v1`.
- `http/game-internal.openapi.json`: внутренние операции Game, недоступные через ingress.
- `http/auth-internal.openapi.json`: introspection и погашение одноразовых WS tickets.
- `events/domain-event.schema.json`: 14 типов событий с единым envelope.
- `events/ws-client.schema.json`, `events/ws-server.schema.json`: кадры WSS `/wss`.
- `events/common.schema.json`: формы snapshots, согласованные с публичным контрактом.

## Статус проверок

Пользователь разрешил продолжить без установки валидаторов. Установка npm-пакетов
и полная OpenAPI/JSON Schema валидация отложены. Пункты 1.4 и 1.5 не завершены.
Позитивные/негативные fixtures подготовлены, но не считаются прошедшими проверку.
Проверка обратной совместимости также ещё не реализована.

Доступная проверка без внешних пакетов:

```sh
python3 tools/contracts/check-structure.py
```

Она проверяет чтение JSON, локальные ссылки, уникальность operationId и объявления
required-полей. Она НЕ заменяет OpenAPI или JSON Schema validator.
Будущий полный валидатор находится в `tools/contracts/validate.mjs`, зависимости —
в package.json рядом с ним. Пока зависимости не установлены, его запуск не работает.

## Команды и безопасность

Изменяющие запросы используют UUID `Idempotency-Key`; тот же ключ с другим payload
даёт IDEMPOTENCY_CONFLICT. expectedVersion относится к сущности, указанной
в описании операции: participation, match или game. При VERSION_CONFLICT клиент
получает актуальный snapshot перед новой командой.

Для Web сессионный token находится в Secure HttpOnly cookie. Изменяющие запросы
требуют CSRF header, включая регистрацию/вход с clientType WEB. Native использует
opaque bearer token. Пароль в fixtures синтетический из спецификации, не секрет.
Внутренние сервисные credentials передаются отдельно от пользовательской сессии;
решение о разрешении конкретного внутреннего действия остаётся за владельцем API.

## WSS и события

Внешнее соединение — `wss://<host>/wss`, внутри mesh — WS без TLS. Сначала auth
с одноразовым ticket, затем subscribe на свой matchId. До auth события запрещены.
После подписки клиент читает HTTPS snapshot и применяет буфер событий новее его
версии. При gap, resync_required, reconnect и foreground выполняется повторная
сверка; фоновая сверка версии — каждые 5 секунд. Heartbeat — каждые 2 секунды.

ack кадра не обещает exactly-once доставку. Повтор eventId/aggregateVersion не
применяется второй раз; запоздалое событие не откатывает состояние. Таймеры рисуются
по serverTime/deadline; равенство сроку уже означает опоздание. Символы и поле —
в Game snapshot; отсутствующий result означает незавершённую партию.

Realtime использует `resync_required` после успешной подписки как барьер: HTTP
snapshot читается после этого frame. `sessions/touch` — внутренний Auth endpoint
для проверки userId/sessionId без передачи session token. `connections/renew`
возвращает только подтверждённые записи. Внутренний Matchmaking
`POST /internal/v1/availability` (X-Service-Token) принимает ровно
`userId: UUID`, `generation: integer >= 1`, `connected: boolean` и возвращает
`{"status":"APPLIED"}`; обновляет доступность существующего AVAILABLE ticket.
