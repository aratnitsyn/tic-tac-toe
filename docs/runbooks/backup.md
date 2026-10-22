# Локальный backup и проверка восстановления

Решение пользователя 2026-10-22: зашифрованные файлы на MacBook вне Kubernetes PVC,
ключ — macOS Keychain. Это не защита от потери MacBook, его диска или Keychain.

Из корня, в отсутствие параллельных игровых/нагрузочных тестов:

```sh
infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/backup.py create
infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/backup.py restore-check --file /absolute/path/to/backup.tttbak
```

По умолчанию файл сохраняется в игнорируемый `infra/kubernetes/.local/backup/`,
режим 0600, каталог 0700 при создании. Существующий файл не перезаписывается.
Keychain service `com.github.aratnitsyn.tictactoe.backup.docker-desktop`, account
`encryption-v1` отделён от Vault bootstrap/recovery. Не заменяйте потерянный ключ:
он не восстановит старые копии. Экспорт recovery для другого Mac требует отдельной
защищённой процедуры; ключи не включаются в сам backup.

Формат TTTBACKUP1: OpenSSL AES-256-CBC/PBKDF2 (200000 iterations, случайная соль),
затем HMAC-SHA256 по заголовку и ciphertext с отдельным производным ключом.
MAC проверяется до расшифровки. Пароль передаётся OpenSSL по приватному fd,
не в argv/environment/файл. Backup содержит секреты и чувствительные DB records;
не добавляйте его в Git и не публикуйте. Для расшифровки нужен согласованный tooling.

Создание копии вызывает плановое окно недоступности: все backend Deployment в
`tictactoe` останавливаются с сохранением их replica counts. После завершения
shutdown создаётся логический pg_dumpall (включая роли), фиксируются fingerprints
47 текущих public tables; JetStream копируется при кратком SIGSTOP NATS с обязательным
SIGCONT в finally; Redis выполняет SAVE; Vault file backend копируется после seal.
Unseal использует существующий Keychain recovery. В finally восстанавливаются
прежние replicas и проверяется rollout. Игровые сроки не продлеваются: после
возобновления возможны штатные timeout results. Не используйте это как online backup.

Если процесс принудительно убит, проверьте NATS (при необходимости SIGCONT PID 1),
Vault (`vault.py --unseal-only`) и верните replicas из values/сохранённого плана.
Не выполняйте init Vault, не меняйте credentials и не удаляйте PVC.

Restore-check расшифровывает копию в памяти и создаёт **новый** namespace
`tictactoe-recovery-<случайный id>` через отдельный YAML Helm chart recovery.
Это четыре Pod с пустыми emptyDir, без Service и с default-deny ingress/egress.
PostgreSQL/NATS/Redis слушают loopback; Vault использует проверенный TLS через
локальный port-forward, сертификаты из backup происходят из Vault. SQL-восстановление
не направляется в действующий PostgreSQL. Сверяются count/content hash каждой
таблицы, инициализация/unseal Vault и hash login key, состояние JetStream; Redis
должен загрузить RDB и отвечать PONG. Временный namespace удаляется в finally.

Redis содержит временные tickets/маршрутизацию: истёкшие записи при загрузке могут
отсутствовать; актуальное участие остаётся в Game PostgreSQL. Реальные клиенты
должны reconnect/resync; restore-check не запускает клиентский трафик.

Отчёт `.local/restore-report.json` содержит snapshotAt, backupAgeSeconds (возраст
точки восстановления, потенциальное окно RPO), restoreSeconds (RTO восстановления
хранилищ до проверяемого состояния). Это не измерение full-platform/region recovery.
Нулевой RPO не заявляется: изменения после snapshot отсутствуют в копии. Для
уменьшения возраста нужны расписание и retention, согласованные с ресурсами.

Для регулярных ручных копий используйте уникальные имена; автоматическое удаление
старых копий не настроено. Контролируйте место на MacBook. Перед удалением копии
проверьте другую копию restore-check. Перед обновлением схемы сохраните backup.
Миграции выполняются только вперёд штатным deploy.py; Helm rollback сам по себе
не отменяет DDL. При несовместимой схеме остановитесь, выберите согласованный
restore point и проведите полноценную процедуру переключения, не восстанавливая
поверх работающей БД. Изолированный drill не заменяет такой план.
