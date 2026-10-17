# Локальное окружение

Требуется работающий Docker Desktop / Docker Engine с Compose v2, Python 3 и
OpenSSL с поддержкой `req -addext`. Сервисы приложения пока запускаются отдельно.

Из корня:

```sh
python3 scripts/init-local.py
docker compose --env-file infra/local/.env -f infra/local/compose.yml up -d --wait
scripts/check-local.sh
```

Генератор создаёт уникальные пароли в игнорируемом `.env` с правами 0600 и
локальный CA в игнорируемом `infra/local/certs/`. Существующие значения не заменяет.
CA предназначен для последующего ingress/egress стенда: Compose не разворачивает
Istio и не предоставляет внешние HTTPS/WSS endpoints.

Доступны только localhost порты: PostgreSQL 5432, Redis 6379, NATS 4222,
NATS monitoring 8222. Внутренний TCP без TLS соответствует принятой модели mesh.
В Compose нет service mesh: его поведение будет проверено на Kubernetes отдельно.
Учётная запись NATS — `app`, пароль берётся из `.env`; Redis требует пароль.
Этот стенд использует одну локальную replica каждого хранилища.

PostgreSQL создаёт отдельные БД и роли для auth/user/lobby/matchmaking/game/realtime.
Роль не является superuser и не имеет CONNECT к чужим сервисным БД.
Проверка изоляции использует TCP и пароль, а не локальную trust-аутентификацию.
Init scripts выполняются только на новом volume; изменение `.env` не ротирует
пароли существующих БД. Сохраняйте `.env` вместе с соответствующими volumes.

Безопасная остановка без удаления данных:

```sh
docker compose --env-file infra/local/.env -f infra/local/compose.yml down
```

Не используйте `down -v` для обычного перезапуска. Для проверки чистой установки
создавайте отдельный Compose project с `-p <name>`; его данные изолированы от
основного стенда. Не печатайте `.env`, `docker compose config` без `--quiet`
или container environment в публичные отчёты.
