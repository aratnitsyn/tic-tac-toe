# tic-tac-toe

Учебная микросервисная PvP-платформа. Требования и архитектура находятся в
`openspec/changes/pvp-platform-v1/`. Реализованы Auth API, защищённый Gateway
и минимальный событийный User. Сквозной logout ожидает реализацию Game;
остальные игровые сервисы пока scaffold. Локальные PostgreSQL/Redis/NATS
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
будут предоставлены Istio ingress при реализации инфраструктуры.

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
