# Auth и минимальный User

JDK 25, Boot 3.5.16; Gateway использует Spring Cloud 2025.0.3 (Gateway 4.3.5).
Внешние маршруты — HTTPS `/api/v1/auth/**`. Внутренний HTTP остаётся за ingress.
Gateway требует `AUTH_SERVICE_TOKEN`, проверяемый Auth на всех API, включая
introspection и consume ticket. Внешний клиент не может задавать этот credential:
Gateway перезаписывает заголовок и удаляет `X-User-Id`/`X-Session-Id`.
Закрытие прямого доступа и TLS на ingress проверяются в этапе Kubernetes/Istio.

## Локальный запуск

Сначала подготовьте зависимости по [local.md](local.md), затем из корня:

```sh
python3 scripts/migrate.py auth
python3 scripts/migrate.py user
python3 scripts/bootstrap-streams.py
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
python3 scripts/run-service.py auth
# В другом терминале:
python3 scripts/run-service.py user
# В третьем:
python3 scripts/run-service.py api-gateway
```

Скрипт читает существующий `infra/local/.env`, создаёт один раз
`infra/local/secrets/services.json` и `login-keys.properties` с правами 0600.
Каталог игнорируется Git. Секреты передаются через окружение, не аргументы.
Не удаляйте и не регенерируйте ключи при рестарте: потеря ключа делает replay
недоступным. Это локальные секреты; в Kubernetes используйте Secret/secret manager.
`AUTH_LOGIN_KEY_FILE` — properties-файл `keyId=base64` с 32 байтами AES-ключа;
`AUTH_LOGIN_KEY_ID` выбирает активный ключ. При ротации оставляйте старые ключи
до истечения всех зашифрованных ответов. Закрытые ключи не хранятся в БД.

Локальные порты: Gateway 8080, Auth 8081, User 8082. HTTP loopback применяется
для отладки и тестов, внешний HTTPS предоставляет будущий Istio ingress.

## Email и пароль

Email: trim, ASCII local/domain, структурная проверка Commons Validator,
максимум 254 символа; уникальный `emailKey` в нижнем регистре Locale.ROOT.
Точки и plus-суффиксы сохраняются. Проверки существования адреса и отправки
писем нет. `emailVerifiedAt` остаётся отдельным nullable полем.

Пароль: 6–15 ASCII символов без trim/нормализации; обязательны a-z, A-Z, 0-9
и один из `!@#$%^&*()_+-=[]{};:,.?`. Остальные символы запрещены.
Хранится Argon2id: 19 MiB, 2 итерации, 1 поток, случайная соль 16 байт,
выход 32 байта. Параметры и соль находятся в encoded hash; credential version
сохраняется отдельно. Повтор регистрации сверяет пароль по Argon2id,
не сохраняет быстрый отпечаток пароля в command store. Ответы и события
не содержат пароль/hash. Внешние провайдеры не подключены; таблица identities
резервирует уникальность `(provider, subject)` для будущих версий.

Подтверждения email и восстановления пароля в v1 нет. Потеря session token
без явного выхода требует дождаться 24 часов бездействия; второго входа
или административного обхода этого правила API не предоставляет.

## Сессии и клиенты

`register` и `login`: JSON `{email,password,clientType}`, `Idempotency-Key: UUID`.
Native до входа дополнительно передаёт `X-Client-Type: NATIVE`, не отправляет
Origin и cookies. После входа использует `Authorization: Bearer <token>`;
токен следует хранить в Keychain/Keystore. Не помещайте токен в URL или логи.

Web получает `/api/v1/auth/csrf`: `data.token`, `data.headerName` и pre-auth
Secure HttpOnly SameSite=Strict cookie `__Host-CSRF`. Для изменяющих запросов
передаёт разрешённый `Origin` и `X-CSRF-TOKEN`. Origin задаётся `WEB_ORIGIN`
(локально `https://localhost`). Login выставляет `SESSION`: Secure HttpOnly,
SameSite=Strict, Path=/api/v1. В JSON Web-login токен отсутствует.
Browser-запрос не может отключить CSRF заголовком Native при наличии Origin
или session cookie. Одновременные bearer и session cookie отклоняются.
HTTP loopback не предназначен для ручного browser-login с Secure cookies.

В session slot — только SHA-256 случайного 256-битного opaque token. Проверка
сессии и touch идут через PostgreSQL без положительного кеша. Авторизованная
интроспекция (включая будущий heartbeat Realtime) обновляет lastActivity
и expiresAt=serverNow+24h. Равенство сроку уже означает истечение; время
читается из БД после блокировки. Потеря соединения не закрывает сессию.
Другой login после проверки пароля получает `SESSION_ALREADY_ACTIVE`.

Успешный login replay хранится отдельно в AES-256-GCM: случайный nonce,
AAD с userId/commandId/keyId, срок 24 часа от создания без продления.
Повтор возвращает тот же token только пока действует исходная сессия.
После срока ciphertext недоступен; фоновая очистка стирает ciphertext/nonce/keyId.
Остаётся не содержащий секретов tombstone команды, запрещающий повторное
создание сессии тем же commandId. Открытого token в command_results нет.

WS ticket: POST `/api/v1/auth/ws-ticket` с авторизацией и Idempotency-Key.
Срок 30 секунд, повтор не продлевает его. Redis хранит ticket с TTL;
consume выполняет atomic GETDEL и затем повторно проверяет session slot.
Закрытая сессия и deadline equality отклоняются. Ticket передаётся первым
WSS auth frame, не в URL; сам WSS endpoint относится к этапу 10.

Rate limits Auth перед Argon2: 60 попыток/мин на источник и 10/мин на emailKey,
общие для register/login, фиксированное окно Redis. Значения — начальная
техническая конфигурация, не подтверждённый нагрузочный SLO. Источник сейчас
берётся из socket remote address Gateway; пользовательский X-Client-IP
перезаписывается. До нагрузки за ingress необходима настройка доверенного
адреса клиента на ingress и проверка защиты от подмены в этапе 14; иначе
запросы одного ingress будут делить лимит. При Redis failure запрос не
проходит к Argon2. HTTP body регистрации/входа ограничен 8 KiB.

## Выход и User

Logout сохраняет operation и переводит session в CLOSING. Обычные обращения,
новый вход и ticket запрещены сразу. Worker повторяет идемпотентный вызов
Game `/internal/v1/participation/logout` с тем же operationId. Только ответ
APPLIED именно этой операции закрывает slot. Недоступность Game сохраняет
CLOSING; отсутствие его реализации нельзя трактовать как MENU.
Реальный Game пока scaffold: сквозной logout/сдача проверяются в этапе 9.
Auth-тест проверяет контракт успешного обычного выхода и retry через adapter.

Регистрация атомарно сохраняет AccountRegistered в outbox. Publisher ждёт
JetStream ack. User читает durable consumer `user-profiles-v1`, применяет
профиль и inbox одной транзакцией; повтор eventId или отдельное повторное
событие того же userId не создаёт второй профиль. User не хранит участие,
рейтинги или историю. Его задержка не блокирует регистрацию/login Auth.
При недоступном User события остаются в JetStream; см. [events.md](events.md).

## Проверки

```sh
python3 scripts/check-command-stores.py --only kotlin --service auth
python3 scripts/check-command-stores.py --only kotlin --service user
services/api-gateway/gradlew -p services/api-gateway clean build --no-daemon
```

Auth tests используют PostgreSQL, Redis и NATS локального стенда. Новые доменные
тесты создают отдельные временные schemas и удаляют их после теста.
Перед runtime smoke остановите другие локальные экземпляры User.
Дополнительно `python3 scripts/check-auth-runtime.py` запускает собранные Auth/Gateway/User
JAR на loopback с локальным стендом, создаёт один случайный тестовый аккаунт,
проверяет login при остановленном User, replay, конфликт сессии, ticket и
появление профиля после запуска User. Процессы останавливаются автоматически.

Gateway security tests используют настоящий embedded Gateway и HTTP Auth fixture;
это не проверка ingress TLS и не сквозная проверка будущего Game.
