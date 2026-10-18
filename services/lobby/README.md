# lobby

Kotlin/Spring Boot сервис приватных приглашений. Реализованы создание, вход, чтение
собственной комнаты и выход через авторитетный Game, durable coordination и лимиты кода.
Возврат после отмены подготовки ожидает этап 7; пункт 5.4 остаётся открытым.
Подробности API, восстановления и проверки: [runbook Lobby](../../docs/runbooks/lobby.md).

Требуется JDK 25 для сборки и запуска; Spring Boot 3.5.16, Kotlin 2.3.21, JVM target 25.
Spring Boot обновляется до последнего стабильного patch только в ветке 3.5.x, с точным закреплением версии.
Для первой загрузки зависимостей нужна сеть.

Gradle Wrapper 9.8.1 включён в репозиторий с SHA-256 проверкой дистрибутива.
Сборка проверена на JDK 25. При первом запуске нужны сеть
и возможность Gradle открыть локальный служебный сокет.
Если JAVA_HOME указывает на другую Java, выберите JDK 25; на macOS:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
```

Из этого каталога:

```sh
./gradlew clean build --no-daemon
python3 ../../scripts/migrate.py lobby
python3 ../../scripts/run-service.py lobby
```

Внутренний health endpoint: `http://127.0.0.1:8083/actuator/health`.
Переменная `PORT` меняет порт. Внешний HTTPS/WSS предоставляет Istio ingress;
локальный HTTP health endpoint не является публичным API.

Проверки Kotlin исходников (также входят в `build`):

```sh
./gradlew ktlintCheck detektCheck --no-daemon
./gradlew ktlintFormat --no-daemon
```

CLI ktlint 1.7.1 и detekt 1.23.8 закреплены в `config/quality.gradle.kts`.

Локальный запуск через `scripts/run-service.py` читает `local.env` этого сервиса.
Переменные текущего окружения имеют приоритет. В `local.env` хранятся только
несекретные настройки.
Обычный запуск JAR/`bootRun` вне скрипта требует явно заданного окружения.
