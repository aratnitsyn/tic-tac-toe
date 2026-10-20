# Проверка Helm/Vault и rollout — 2026-10-20

Контекст docker-desktop, Kubernetes 1.36.1, ARM64, одна нода / 4 CPU / 8 GiB.
Прежний отчёт 14.1 описывает стенд до перехода; этот отчёт относится к YAML Helm charts.

Проверено на действующем стенде:

- Istio 1.31.2 base/istiod приняты под управление Helm из официального release-архива
  с проверкой SHA-256; platform/storage/mesh установлены, повторный upgrade проходит.
- Vault 2.0.4 (chart 0.34.1), persistent data/audit PVC, TLS; VSO 1.6.0.
  Recovery проверен в macOS Keychain, root token не передаётся workloads.
- Старые пароли восьми app/store credentials и cluster-v1 login key совпали с Vault.
  Перенос не меняет доступ к действующим БД. Устаревшие пять локальных кластерных
  credential/certificate файлов удалены после явного подтверждения пользователя.
- Реальный ServiceAccount JWT получает свой path; чужой path/namespace запрещены.
- Auth service token ротирован в Vault и синхронизирован VSO. Ingress certificate
  перевыпущен из Vault CA/key и доставлен через VSO/SDS; HTTPS/WSS прошёл после ротации.
- При sealed Vault новый consumer остаётся в CreateContainerConfigError без обязательного
  секрета. Unseal из Keychain восстановил sync и Ready; тестовые ресурсы удалены.
- ConfigMap изменил фактически обслуживаемый порт Gateway; Helm rollback вернул 8080,
  сохранив count/hash account identities (самих идентификаторов в отчёте нет).
- Startup/readiness/liveness, ограниченные pools, requests/limits, grace/drain и rollout
  применены. Реальный PvP через HTTPS/WSS: подтверждённый X на клетке 0 пережил rollout
  Game, повтор move вернул исходный результат, replay Lobby не создал второй match.
- Финальный smoke: HTTPS/WSS auth/heartbeat, закрытые маршруты; 9 Envoy proxies с
  11 plaintext app/store clusters; 25 TCP boundary checks, включая ingress PodIP/ServiceIP,
  TLS CA/SNI validation и запрет cleartext. Негативные проверки не ослаблялись.
- Helm lint/template четырёх charts, server dry-run исходных трёх charts,
  Istio analyze backend/ingress, Python suite 23/23, OpenSpec strict — успешно.

Ограничения: исторические startup restarts и краткий 503 при распространении mesh
endpoint наблюдались; нулевой downtime не заявляется. Профиль с несколькими репликами
не испытывался на этой ноде. Чистая новая среда с --initialize-new не прошла отдельный
полный integration run. Вместимость 100k, HPA, наблюдаемость 14.3/14.4, backup/restore
14.5 и egress 14.6 этим отчётом не подтверждаются.

Воспроизведение: [runbook](../../infra/kubernetes/README.md).
Машинные smoke-артефакты — в игнорируемом infra/kubernetes/.local/.

Последующая подготовка 14.3: сборки/линтеры пяти JVM и двух Go сервисов,
Helm lint/template пяти charts и Python suite 23/23 прошли. Образы local-14-3
собраны локально. Итоговый deploy остановлен Kubernetes: изменение image у
существующего Job bootstrap-stream меняет immutable spec.template. Поэтому
live trace/metrics/dashboard check не запускался, 14.3 не закрыт. Результаты
предыдущего smoke выше относятся к стенду до этого незавершённого upgrade.
