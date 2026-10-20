# tic-tac-toe

Учебная микросервисная PvP-платформа. Требования и архитектура находятся в
`openspec/changes/pvp-platform-v1/`. Реализованы Auth API, защищённый Gateway,
минимальный событийный User, реестр участия Game, Lobby с приватными кодами и FIFO Matchmaking.
Реализована [подготовка матча](docs/runbooks/preparation.md): готовность, отсчёт, старт и отмена с возвратом.
Реализована [партия](docs/runbooks/play.md): ходы, результат, сдача, пауза и надёжные таймеры.
Реализован [сквозной logout](docs/runbooks/logout.md) с восстановлением CLOSING; Realtime реализует WS, dispatcher и синхронизацию; Web-клиент находится в корневом `web/`, мобильные клиенты ещё предстоят. Локальные PostgreSQL/Redis/NATS
доступны через infra/local/compose.yml. Запуск и ограничения Auth:
[docs/runbooks/auth.md](docs/runbooks/auth.md).

## Backend

Kotlin/Spring Boot: `api-gateway`, `auth`, `user`, `lobby`, `game`.
Go: `matchmaking`, `realtime`. Все находятся в `services/`.

JVM: JDK 25, Kotlin 2.3.21, Spring Boot 3.5.16, Gradle Wrapper 9.8.1 с SHA-256.
Go: 1.24.0. Maven для этих сборок не требуется. Первая сборка загружает зависимости.

На macOS выберите установленный JDK 25:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
```

Из каталога любого Kotlin-сервиса:

```sh
./gradlew clean build --no-daemon
python3 ../../scripts/run-service.py <service>
```

Проверки ktlint/detekt входят в build. Автоформатирование: `./gradlew ktlintFormat`.
Из каталога Go-сервиса: `go build -o build/service .`, `python3 ../../scripts/run-service.py <service>`.
Общие проверки Go из корня:

```sh
scripts/install-go-tools.sh
scripts/check-go.sh
```

Установщик проверяет SHA-256 golangci-lint 2.1.6 и помещает его в `.cache/bin/`.
Backend CI: `.github/workflows/backend.yml`. Service README перечисляют порты.
Health endpoints — внутренний HTTP; публичные HTTPS `/api/v1/**` и WSS `/wss`
предоставляются отдельным Istio ingress в локальном Kubernetes-профиле 14.1.
Развёртывание и сетевые проверки — [infra/kubernetes/README.md](infra/kubernetes/README.md).

## Локальные хранилища

```sh
python3 scripts/init-local.py
docker compose --env-file infra/local/.env -f infra/local/compose.yml up -d --wait
scripts/check-local.sh
```

Требуется запущенный Docker Engine. Проверены healthchecks и запрет подключения
сервисных ролей к чужим БД. Инструкции остановки без потери данных:
[docs/runbooks/local.md](docs/runbooks/local.md).

## Контракты

Публичные/внутренние OpenAPI и JSON Schema событий описаны в [contracts/README.md](contracts/README.md).
Без установки зависимостей доступна структурная проверка:

```sh
python3 tools/contracts/check-structure.py
```

Полная schema-validation отложена по указанию пользователя и не считается пройденной.

Локальный запуск через `scripts/run-service.py <service>` читает
`services/<service>/local.env`. Переменные текущего окружения имеют приоритет.
В этих файлах хранятся только несекретные настройки; источник локальных
credentials описан в [Auth runbook](docs/runbooks/auth.md).

Проверки локальных скриптов (без внешних зависимостей):

```sh
python3 -m unittest discover -s scripts/tests -v
```

Полный интеграционный прогон: `python3 scripts/check-command-stores.py` после
подготовки локального стенда и streams. Он отклоняет несовместимые фильтры
сервиса/языка и завершает работу с ошибкой при пропущенных тестах.

Game: реестр участия, CAS и восстановление операций описаны в [runbook участия](docs/runbooks/participation.md).
Проверка рестарта Game: `python3 scripts/check-participation-runtime.py` после сборки Auth/Gateway/Game и миграций.

Lobby: [API и восстановление комнат](docs/runbooks/lobby.md). Проверка: `python3 scripts/check-lobby-runtime.py`.
Возврат после отмены подготовки и ротация кода реализованы; см. [подготовку](docs/runbooks/preparation.md).

Matchmaking: [FIFO, восстановление и benchmark](docs/runbooks/matchmaking.md). Проверка: `python3 scripts/check-matchmaking-runtime.py`.

Этап 10: Realtime обслуживает внутренний WS `/wss`, проверяет tickets и подписки,
использует Redis ownership и сохраняемые Game leases, адресно доставляет NATS events.
Тестовый клиент проверяет ack, snapshot/gap, периодическую сверку и reconnect;
Web находится в корневом `web/`; iOS/Android ещё предстоят. Запуск и ограничения — [Realtime runbook](docs/runbooks/realtime.md).

## Web клиент

React/TypeScript в [web/](web/README.md): cookie/CSRF, Lobby/FIFO, готовность,
поле, сдача, пауза и version resync. Установка, локальный HTTPS proxy,
управление клавиатурой и browser tests описаны в инструкции клиента.
Из `web/`: `npm ci`, `npm run lint`, `npm run build`.

Инфраструктура Kubernetes переводится на отдельные YAML Helm charts Kubernetes/Istio и Vault/VSO. Команды, миграция credentials, ConfigMap и recovery через macOS Keychain: [infra/kubernetes/README.md](infra/kubernetes/README.md). Статус задач — в OpenSpec tasks.md.
