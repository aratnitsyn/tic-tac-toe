# Этап 14: наблюдаемость, восстановление — 2026-10-22

Контекст docker-desktop, Kubernetes 1.36.1, одна ARM64 нода, 4 CPU / 8 GiB.

## 14.3

- Исправлен lifecycle bootstrap-stream: post-install/post-upgrade Helm hook.
  Upgrade с local-14-3 и повторный upgrade прошли, восемь backend health — 200.
- Синтетическая HTTPS-команда: одна trace содержит Gateway, Auth, Go Matchmaking,
  Game; проверяется общий traceId, а не наличие независимых spans.
- Восемь Prometheus targets up; gauges Auth/FIFO/timers/WS/outbox присутствуют;
  семь DB observers up. В series нет user/session/token labels и фактических
  значений идентификатора/токена тестового пользователя.
- Provisioned dashboard Grafana получен с паролем из Vault в памяти.

## 14.4 и восстановление сети

Promtool проверил девять alert rules. Реальный отказ User вызвал BackendUnavailable.
Первый тест выявил конфликт двух NetworkPolicy engines и не закрыл задачу:
kindnet (NFQUEUE 101) содержал устаревшие pod IP и ошибки watch/API, дополнительный
kube-network-policies (NFQUEUE 98) принимал разрешённый User→NATS, но TCP не проходил.
Разрешённый пользователем перезапуск дополнительного контроллера не помог.

После отдельного явного разрешения дополнительный DaemonSet удалён, kindnet
перезапущен, соединение восстановлено. Installer больше не устанавливает второй
движок рядом с закреплённым kindnet. Повторный fault test прошёл: firing, возврат
replicas, Ready/up и resolved. Полный HTTPS/WSS/Envoy/NetworkPolicy smoke прошёл
после исправления. Политики не ослаблялись; PVC/credentials не заменялись.
Runbooks покрывают недоступность backend/observer, FIFO, лимиты, Auth, DB pool,
таймеры, outbox и массовые отключения.

## 14.5

Пользователь выбрал encrypted files на MacBook вне PVC, ключ в отдельной Keychain
записи. Создан authenticated encrypted backup PostgreSQL/Vault/NATS/Redis.
В новом default-deny recovery namespace с emptyDir:

- 47 public tables шести БД совпали по count и content hash;
- Vault восстановлен с исходным initialized storage, TLS и unseal через отдельный
  Keychain recovery; hash login key совпал;
- JetStream stream state совпал, Redis загрузил RDB и ответил PONG;
- временный namespace удалён, исходные данные не перезаписывались.

RTO восстановления хранилищ до проверенного состояния: 15,911 секунды.
Возраст snapshot на момент проверки (потенциальное окно RPO): 210,850 секунды.
Повторный restore после финальных guards: 16,669 секунды, возраст той же копии
820,317 секунды; те же 47 таблиц/Vault/JetStream/Redis успешно восстановлены.
Это не full-platform traffic RTO, не измерение реальной потери данных при аварии
и не нулевой RPO. Потеря MacBook/Keychain не покрывается локальным backup.

Текущий ConfigMap/Helm upgrade и совместимый rollback повторно проверены:
реальный порт Gateway изменился и вернулся, account identities сохранились.
Миграции БД не откатывались автоматически.

## 14.6

Отдельный egress Deployment/Service, Gateway/ServiceEntry/VirtualService и
DestinationRule TLS origination применены. CA получен через Vault/VSO.
Тестовый HTTPS endpoint находится на MacBook вне Kubernetes:

- HTTP mesh → egress → внешний TLS вернул 200;
- прямое HTTPS соединение с корректным CA/Host блокируется на TCP;
- недоверенный внешний сертификат дал 503 и рост Envoy ssl.fail_verify counters;
- доверенный endpoint восстановлен, повторно получен 200 без HTTP fallback.

Временные TLS listeners и probe Pod удалены; egress gateway и маршруты остаются.
Без тестового процесса внешний fixture недоступен — это не постоянная интеграция.

## Vault readiness

Финальная проверка выявила существующую проблему: Vault был unsealed, но exec
readiness обращалась к Service DNS через закрытый hairpin-путь. В chart 0.34.1
команда дополнительно была жёстко задана с tls-skip-verify. Добавлен отдельный YAML
Kustomize post-render patch для официального Helm chart: `vault status` использует
VAULT_ADDR=https://127.0.0.1:8200 и доверенный CA. Python не генерирует manifests.
Vault перезапущен на прежнем PVC, выполнен unseal из Keychain, Ready=1/1.
Повторный vault-check: свой path доступен, чужой path/namespace запрещён, scoped
token отозван. Root token и shares не выводились в логи.

## Итоговые проверки

28 Python tests, lint/template основных Helm charts и lint тестовых charts,
OpenSpec strict — успешно. Финальный HTTPS/WSS/NetworkPolicy smoke и проверка
tracing/метрик/Grafana прошли. Istio analyze завершился без ошибок; остались
информационные замечания о старых sidecar annotations и injection у default/
уже удалённого probe namespace в informer cache.

## Ограничения

Нагрузка 100k, многонодовая HA и внешний backup не подтверждены.
Наблюдаемость и thresholds относятся к локальному профилю, не утверждённым SLO.
Сквозные schema validators по-прежнему отложены по указанию пользователя.
Машинные отчёты/backup находятся в игнорируемом infra/kubernetes/.local/.
