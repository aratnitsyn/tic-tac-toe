# Версионированные SQL миграции

Каждый сервис-владелец БД имеет собственный `services/<service>/db/migration/`.
Gateway не владеет БД. Имена: `V001__foundation.sql`, затем возрастающие версии.
Применённые файлы неизменяемы. Нельзя удалять версии, уже присутствующие в БД.
Добавление старой версии после более новой также не допускается при выпуске.

Скрипт миграций — отдельный шаг до запуска приложения; запуск Spring/Go scaffold
не применяет миграции автоматически. Роль запуска — соответствующая сервисная,
не postgres superuser. Зависимости Python не устанавливаются.

На локальном Compose стенде из корня:

```sh
python3 scripts/migrate.py auth
python3 scripts/migrate.py user
python3 scripts/migrate.py lobby
python3 scripts/migrate.py matchmaking
python3 scripts/migrate.py game
python3 scripts/migrate.py realtime
```

С обычным PostgreSQL client в deployment job: `python3 scripts/migrate.py auth --psql`.
PGHOST/PGPORT/PGPASSWORD или PGPASSFILE задаются окружением job, не аргументами с
секретами в логах. Клиент psql должен поддерживать используемые meta commands.

Одна транзакция и advisory lock сериализуют migrators одной БД. `schema_history`
сохраняет SHA-256 исходного файла, время и версию. Повтор пропускает известные
миграции, несовпадающий checksum завершает процесс с ошибкой. DDL и history
фиксируются вместе; ошибка откатывает весь текущий прогон.

Миграции содержат обычный DDL/DML без BEGIN/COMMIT, psql meta commands и операций,
запрещённых внутри транзакции (например CREATE INDEX CONCURRENTLY). Такие изменения
требуют отдельного механизма и согласованного обновления плана. Разрушительный
rollback не автоматизируется: используйте expand/contract и резервное копирование.

Проверки генератора: `python3 -m unittest discover -s tests/integration -p 'test_migration_render.py'`.
PostgreSQL проверки: первая установка шести БД, повторный запуск без новых history
и отказ на изменённом checksum выполняются на изолированном локальном стенде.
