# Проверка backend и локальных скриптов

Для JVM используйте JDK 25. Команды выполняются из корня проекта.

## Подготовка и полный прогон

```sh
python3 scripts/init-local.py
scripts/check-local.sh
python3 scripts/migrate.py auth
python3 scripts/migrate.py user
python3 scripts/migrate.py lobby
python3 scripts/migrate.py matchmaking
python3 scripts/migrate.py game
python3 scripts/migrate.py realtime
python3 scripts/bootstrap-streams.py
scripts/install-go-tools.sh
python3 -m unittest discover -s scripts/tests -v
python3 tools/contracts/check-structure.py
openspec validate pvp-platform-v1 --strict
scripts/check-go.sh
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
python3 scripts/check-command-stores.py
services/api-gateway/gradlew -p services/api-gateway clean build --no-daemon
python3 scripts/check-auth-runtime.py
```

Стек PostgreSQL/Redis/NATS должен быть запущен по [local.md](local.md).
Перед runtime smoke остановите остальные экземпляры User: smoke проверяет
регистрацию при выключенном User и доставку профиля после его запуска.
Он создаёт один случайный аккаунт в локальной БД.

`check-command-stores.py` проверяет четыре JVM-сервиса и два Go-сервиса
на реальном стенде, включает форматирование/линтеры/сборку JVM и race detector Go.
Он проверяет отчёты: пустой прогон или пропущенные интеграционные тесты — ошибка.
`check-go.sh` проверяет форматирование, vet, lint, unit tests и сборку; без
переменных интеграционного стенда его PostgreSQL/NATS-тесты пропускаются.
Поэтому он не заменяет `check-command-stores.py`.

Python-тесты проверяют буквальное чтение env, приоритет окружения, диагностику
без вывода значений, отсутствие скрытых defaults, неправильные миграции и
отклонение фильтра, который не выполнил бы ни одного теста. Они также входят в CI.

## Что проверяют интеграционные сценарии

- Command store: конкурентный повтор с одним эффектом и сохранённым ответом,
  конфликт payload, rollback и повтор, независимость JSON от порядка свойств.
- Inbox: реальная запись в БД и marker фиксируются вместе; после ошибки
  оба откатываются, после commit повтор не вызывает callback. Есть retry,
  parking, недоступность parking и replay без повторного эффекта.
- Outbox: rollback enqueue, сбой после broker ack, восстановление lease,
  повтор того же eventId и дедупликация брокером.
- Auth: регистрация с разным регистром email под конкуренцией, единственная
  сессия, replay зашифрованного login, граничные сроки и очистка ciphertext,
  cookie, авторизация внутренних вызовов, одноразовые WS tickets и logout adapter.
- Rate limit: десять неверных паролей зарегистрированного аккаунта действительно
  вызывают проверку hash; последующая регистрация того же email блокируется до
  hash, подтверждая общий лимит login/register с разных источников.
- Gateway: bearer/сессия, CSRF/origin, передача ошибок и cookie,
  перезапись служебного credential и IP, удаление user/session заголовков.
- User: повтор eventId и разные события того же аккаунта создают один профиль.

Kotlin и Go integration fixtures используют отдельные временные схемы БД.
Их outbox workers не могут забрать событие приложения или другого теста.
Временные JetStream streams очищаются после тестов. Тест replay CLI использует
собственную parking запись и subject, удаляет только созданные им сообщения,
проверяет отказ без `--confirm`, сохранение bytes и дедупликацию повтора операции.
Запуск `replay-parking.py` со случайным существующим parking sequence для
проверки недопустим: это повторно публикует реальное сообщение.

## Границы проверки

Структурная проверка контрактов проверяет JSON, локальные ссылки и operationId.
Полная OpenAPI/JSON Schema validation пока отложена по решению пользователя;
успех структурной проверки не означает соответствия схемам и fixtures.

`PasswordBenchmarkTest` — локальное измерение Argon2id, а не приёмочный
нагрузочный тест. Успешный прогон не подтверждает вместимость 100 000 пользователей.
Сквозной logout с реальным Game ещё не реализован; тест logout проверяет adapter
и состояние Auth, а не отсутствующую игровую логику. Kubernetes/Istio/TLS также
не проверяются этими локальными HTTP-сценариями.
