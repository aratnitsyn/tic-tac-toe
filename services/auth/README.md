# auth

Auth: регистрация, session slot, интроспекция, encrypted login replay, WS tickets и сохраняемый CLOSING. Сквозной logout ожидает Game (этапы 4/9).

Требуется JDK 25 для сборки и запуска; Spring Boot 3.5.16, Kotlin 2.3.21, JVM target 25.
Spring Boot обновляется до последнего стабильного patch только в ветке 3.5.x, с точным закреплением версии.
Для первой загрузки зависимостей нужна сеть.

Gradle Wrapper 9.8.1 включён в репозиторий с SHA-256 проверкой дистрибутива.
Чистая сборка scaffold проверена на JDK 25. При первом запуске нужны сеть
и возможность Gradle открыть локальный служебный сокет.
Если JAVA_HOME указывает на другую Java, выберите JDK 25; на macOS:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
```

Для `bootRun` нужно настроенное окружение из Auth runbook ниже;
скрипт `python3 scripts/run-service.py auth` из корня передаёт локальные credentials.

Из этого каталога:

```sh
./gradlew clean build --no-daemon
./gradlew bootRun
```

Внутренний health endpoint: `http://localhost:8081/actuator/health`.
Переменная `PORT` меняет порт. Внешний HTTPS/WSS предоставляет Istio ingress;
локальный HTTP health endpoint не является публичным API.

Проверки Kotlin исходников (также входят в `build`):

```sh
./gradlew ktlintCheck detektCheck --no-daemon
./gradlew ktlintFormat --no-daemon
```

CLI ktlint 1.7.1 и detekt 1.23.8 закреплены в `config/quality.gradle.kts`.


Запуск с локальными credentials и описание API/ограничений: [Auth runbook](../../docs/runbooks/auth.md).
