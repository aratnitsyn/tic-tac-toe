# Локальный Kubernetes: Helm, Istio и Vault

Профиль для Docker Desktop (kind provisioner), Kubernetes 1.36.1, одна ARM64 нода,
4 CPU / 8 GiB. Это функциональный стенд; вместимость и HA им не доказываются.
Backup/restore и egress проверяются отдельно описанными ниже сценариями. Контекст всех команд — `docker-desktop`.

## Источники конфигурации

- `chart/platform/`: Kubernetes workloads, Services, ConfigMaps, RBAC, NetworkPolicy.
- `chart/storage/`: PostgreSQL/Redis/NATS и bootstrap streams, постоянные PVC.
- `chart/mesh/`: только Istio Gateway, VirtualService, DestinationRule, ServiceEntry, PeerAuthentication.
- `chart/secret/`: VaultConnection/Auth/StaticSecret, sync ServiceAccounts и Vault NetworkPolicy.
- `chart/observation/`: закрытые Prometheus, Jaeger, Grafana и alert rules.
- `chart/recovery/`, `chart/egress-probe/`: изолированные временные проверки.
- `values.yaml`: несекретные параметры, ConfigMap data и ссылки на Vault paths.
- `vault/server-values.yaml`, `operator-values.yaml`: отдельные настройки официальных charts.
- `istio.yaml`: values официальных charts base/istiod, версия 1.31.2.

Все manifests/templates/values — YAML; Python управляет CLI, проверками и миграцией,
но не генерирует инфраструктурные manifests. ConfigMap передаёт несекретные env;
секретные env и mounts используют только копии, синхронизируемые из Vault.
Образы сервисов остаются `tictactoe/<service>:local-14-3`; registry push не выполняется.

## Инструменты и установка

Helm 3.19.0, PyYAML 6.0.3, Istio 1.31.2. Installer проверяет SHA-256 Helm и официального
архива Istio. Charts Istio берутся из release-архива той же версии: Helm index может
отставать от опубликованного релиза. Vault chart 0.34.1 / image 2.0.4;
Vault Secrets Operator chart/image 1.6.0. Vault не запускается в dev mode.
Официальный Vault chart применяет отдельный YAML Kustomize post-render patch
`vault/post-render.sh`: readiness выполняет `vault status` на HTTPS 127.0.0.1
с проверкой CA, без встроенного в upstream chart tls-skip-verify. Используется
встроенный `kubectl kustomize`; не пропускайте post-renderer при ручном Helm upgrade.

Из корня:

```sh
python3 infra/kubernetes/tool/install.py
infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/check.py
JAVA_HOME="$(/usr/libexec/java_home -v 25)" infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/build.py --load
```

Installer для macOS ARM64 устанавливает инструменты в игнорируемый `.tools/`,
Istio через Helm. CNI kindnet сохраняется; закреплённый Docker Desktop image
kindest/kindnetd:v20260528-9350166c уже включает NetworkPolicy. Второй движок
не устанавливается. Installer останавливается при обнаружении дополнительного
kube-network-policies DaemonSet или неизвестной версии kindnet. Исторический
vendor/network-policy.yaml не применяется: одновременные движки недопустимы.
Istio native sidecars могут находиться в initContainers с restartPolicy Always.

Сам кластер/узлы создаются Docker Desktop заранее; Helm управляет ресурсами внутри.
`vault.kubeApiCIDR` должен совпадать с InternalIP API-ноды (сейчас 172.19.0.4/32),
а `vault.kubeApiServiceCIDR` — с ClusterIP kubernetes Service (10.96.0.1/32).
При пересоздании Docker Desktop проверьте адрес перед применением сетевых политик.

## Vault и переход с прежнего стенда

```sh
infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/vault.py
infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/deploy.py
infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/smoke.py
```

Первая команда устанавливает Vault на постоянный PVC, включает проверенный TLS,
инициализирует его (3 unseal shares, threshold 2), сохраняет recovery и начальный
административный token в **macOS Keychain**, проверяет чтение и снимает seal.
Service Keychain: `com.github.aratnitsyn.tictactoe.vault.docker-desktop`, account
`bootstrap`. Материалы не выводятся в терминал, не передаются в argv и не пишутся
в репозиторий. Доступ к Keychain может потребовать системного разрешения macOS.
Это локальная административная bootstrap-запись; workloads не получают root token.

Существующие Kubernetes Secrets импортируются в KV v2 `platform/<namespace>/<name>`
без изменения паролей, ключей и сертификатов. Повторный запуск не перезаписывает Vault
старыми Kubernetes значениями. VSO получает краткоживущие JWT отдельных sync
ServiceAccounts, привязанные к namespace; каждый Vault role читает только свой path.
Kubernetes Secret — автоматически обновляемая копия с `refreshAfter: 15s`.
VSO перезапускает Deployment-потребители при смене секретных env. Ingress получает
TLS Secret через SDS. Vault server TLS тоже синхронизируется из Vault после bootstrap.

Начальное TLS-доверие — bootstrap-исключение: используется существующий локальный CA
из `infra/local/certs/`, временные leaf keys удаляются после импорта. CA также
импортируется в закрытый `platform/authority/local-ca`; app/sync policies не дают
доступа к его private key. Локальный CA остаётся нужен независимому Compose/Web
стенду; кластерный deployment больше не читает старые `.local/credential.json` и
`.local/ingress.key`. Удаляйте эти старые кластерные файлы только после успешной
проверки миграции, не удаляя PVC либо recovery Keychain.

Для новой среды без данных используйте `vault.py --initialize-new`: initial credentials
генерируются непосредственно в память и Vault, seed остаётся только в закрытом
Vault bootstrap path. Флаг запрещён при наличии data PVC. Не используйте его как
способ восстановления утерянных паролей действующих хранилищ. Нужен настроенный
локальный bootstrap CA; подготовка описана в `docs/runbooks/local.md`.

`deploy.py` требует SecretSynced для всех обязательных материалов, принимает прежние
ресурсы под управление Helm с `--take-ownership`, сохраняет PVC, применяет миграции
каждой БД своей ролью и проверяет фактические HTTP health endpoints. Helm rollback
не отменяет миграции БД. Namespace и StatefulSet имеют keep policy; это не backup.

## Отказ, recovery и ротация

После рестарта Vault остаётся sealed до восстановления:

```sh
infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/vault.py --unseal-only
infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/vault-check.py
infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/vault-check.py --rotate-service-token
infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/vault-check.py --rotate-ingress-certificate
infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/vault-outage.py
```

Проверки не печатают tokens/passwords. Outage test временно seal-ит локальный Vault,
проверяет блокировку нового потребителя без обязательного секрета, затем unseal из
Keychain и восстановление синхронизации. Уже работающие процессы сохраняют текущие
материалы в памяти; проверка сессии и игровые сроки продолжают действовать. Отказ
Vault не включает fallback к credential-файлам. Если unseal не удался, восстановите
доступ к Keychain/PVC; не выполняйте новый init и не удаляйте том.

Ротация service token обновляет общий разрешённый внутренний credential и его
Deployment-потребителей. Для смены DB/Redis/NATS password нужно согласованно менять
само хранилище и Vault path, дождаться обновления всех потребителей; простая запись
нового password в Vault не меняет пароль сервера. Действующие login keys сохраняются
в `login-key` под прежними keyId до истечения связанных 24-часовых ciphertext.
Проверка сертификата повторяет HTTPS/WSS smoke после SDS обновления.

Keychain не заменяет резервное копирование Vault PVC и раздельное защищённое хранение
recovery. Процедуры backup/restore и измерения RPO/RTO относятся к 14.5. Потеря Keychain
и всех shares делает unseal невозможным; потеря PVC не восстанавливается shares.

## Внешний доступ и проверки

```sh
kubectl --context docker-desktop -n tictactoe-ingress port-forward --address=127.0.0.1 service/ingress 8443:443
curl --resolve tictactoe.test:8443:127.0.0.1 --cacert infra/local/certs/ca.crt https://tictactoe.test:8443/api/v1/auth/csrf
```

Только HTTPS `/api/v1/**` и WSS exact `/wss`. Web static hosting здесь нет.
SNI/Host — `tictactoe.test`; для браузера нужны DNS/hosts mapping и доверие CA.
Проверку сертификатов не отключать. Внутри игрового mesh HTTP/WS и TCP stores —
plaintext, явно заданы PeerAuthentication/DestinationRule DISABLE; Vault management
использует отдельный проверенный TLS. NetworkPolicy ограничивает фактические соединения.

```sh
infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/check.py
python3 -m unittest discover -s scripts/tests -v
openspec validate pvp-platform-v1 --strict
infra/kubernetes/.tools/istioctl --context docker-desktop analyze -n tictactoe
```

На CI нужны Helm 3.19.0 и `pip install -r infra/kubernetes/requirements.txt`.
`check.py` выполняет lint/template пяти основных charts и lint двух тестовых; тесты проверяют реальные rendered
ресурсы, маршруты, ConfigMap и сетевые ограничения. Smoke проверяет внешний TLS,
WSS, авторизацию, Envoy plaintext и запрет обхода через ServiceIP/PodIP. Данные
отчёта `.local/smoke-report.json` относятся только к завершившемуся успешному прогону.

## Probes, ресурсы и rollout (14.2)

Workload values задают startup/readiness/liveness, requests/limits, 45s grace,
Envoy drain 10s, RollingUpdate maxSurge 1 / maxUnavailable 0 и предпочтительное
распределение реплик по нодам. JVM использует lifecycle health groups; Go health
становится доступен после инициализации зависимостей. Hikari и pgx pools ограничены
пятью соединениями на экземпляр; Envoy connection pools тоже ограничены.

`load-values.yaml` задаёт 3 backend replicas / 2 ingress replicas и PDB minAvailable 2
для backend. Локальный профиль сохраняет 1 replica и minAvailable 0: одна нода не
даёт HA. Ресурсный бюджет нагрузочного профиля ещё требует измерений, HPA по
соединениям/lag не считается реализованным наличием replica values.

```sh
infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/helm-check.py
infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/rolling-check.py
```

Первый тест меняет фактически обслуживаемый порт Gateway через ConfigMap, выполняет
Helm rollback и сверяет сохранность account identities. Второй создаёт два тестовых
аккаунта, приватный матч через HTTPS/WSS, подтверждает ход X, выполняет rolling restart
Game, проверяет доску и повтор команды, единственный match по sourceRef и завершает
тестовые сессии. Accounts/результат остаются как тестовые данные. Краткий 503/reconnect
допустим на одноузловом профиле; тест ограниченно ожидает восстановление mesh и не
отменяет игровые таймеры. Это не проверка нулевого downtime.

## Наблюдаемость (14.3)

Добавлен отдельный YAML chart `chart/observation`: Prometheus 3.2.1,
Jaeger 2.11.0, Grafana 12.2.0; доступ только внутри кластера/через локальный
port-forward. Пароль Grafana поступает из Vault. JVM использует закреплённый
OpenTelemetry Java agent 2.20.0, Go — OpenTelemetry SDK; метрики публикуются
на внутренних `/actuator/prometheus` и `/metrics`.

Подготовлены команды (исполняются Python из `.tools/venv/bin/python`):
`tool/observation.py` — установка компонентов,
`tool/observation-check.py` — сквозная проверка trace, метрик и dashboard.
Проверка на стенде прошла: единая трасса Gateway/Auth/Matchmaking/Game,
восемь доступных targets, доменные метрики без user/session/token labels и dashboard.
Bootstrap streams выполняется идемпотентным post-install/post-upgrade Helm hook;
успешный Job удаляется, перед повтором старый Job заменяется. PVC не затрагиваются.
Для проверки после изменения образов повторите deploy.py и observation-check.py.
Jaeger хранит до 5000 трасс в памяти; Prometheus — 2 часа/256 MB. Это настройки
локального стенда, а не согласованные SLO или долговременное хранение telemetry.

## Алерты и проверка восстановления (14.4)

Rules находятся в ConfigMap Prometheus; runbooks — `docs/runbooks/observation.md`.
`tool/alert-check.py` временно останавливает User, проверяет firing и возвращает
прежние replicas в finally. Запускайте отдельно от smoke и пользовательских тестов.
Пороги — эксплуатационные значения локального профиля, не согласованные SLO.

## Решение по backup (14.5)

Согласовано 2026-10-22: зашифрованные файлы на MacBook вне PVC, ключ шифрования
в macOS Keychain. Такой backup не защищает от потери MacBook. Реализация и
проверка изолированного восстановления описаны в `docs/runbooks/backup.md`;
shares Vault сами по себе не восстанавливают данные.

```sh
infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/backup.py create
infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/backup.py restore-check --file /absolute/path/to/backup.tttbak
```

## Egress (14.6)

Отдельный gateway, plaintext mesh и внешний TLS с CA/SAN/SNI проверкой из Vault;
NetworkPolicy запрещает прямой выход. Установка и реальный тест внешнего HTTPS:
`infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/egress.py`.
Порты временного TLS endpoint на MacBook — 18443/18444, host gateway Docker Desktop
192.168.65.254; endpoint находится вне Kubernetes и закрывается после теста.
Подробности и ограничения — `docs/runbooks/egress.md`.

Результаты текущего прогона: `docs/verification/kubernetes-observation.md`.
