# Kubernetes / Istio — проверка 14.1

Изолированный функциональный стенд backend для Docker Desktop **kind provisioner**,
macOS arm64: Kubernetes 1.36.1, 1 нода, 4 CPU / 8 GiB Docker RAM.
Не использует БД/credentials/volumes Compose или процессы сервисов на MacBook.
Профиль не подтверждает вместимость, HA, drain, backup или egress TLS origination.
Задачи 14.2–14.6 остаются отдельными; Web static hosting здесь не добавляется.

## Состав

- `values.json` — namespace, образы, host/Origin, storage class, тип ingress Service.
- `tool/render.py` → `base/platform.json` — восемь backend Deployments
  (семь сервисов, Realtime отдельно socket/dispatcher), sidecars, ConfigMaps,
  ClusterIP Services, NetworkPolicy, PeerAuthentication/DestinationRule DISABLE.
- Отдельный ingress Deployment/Service, gateway injection template, Gateway и
  VirtualService: HTTPS `/api/v1/` → Spring Gateway, exact `/wss` → Realtime.
  `/api/v1`, `/wss/`, `/realtime`, `/internal/**`, `/actuator/**` не опубликованы.
- `tool/storage.py` → `storage/store.json` — PostgreSQL/Redis/NATS StatefulSets/PVC,
  отдельные БД/роли, init и bounded single-replica JetStream bootstrap Job.
  Хранилища без sidecar, порты классифицированы как TCP; TLS не добавляется.
- `istio.yaml` — Istio 1.31.2 minimal (sidecar mode).
- `vendor/network-policy.yaml` — upstream kube-network-policies из тега v1.1.2,
  commit `a145b01caf668ce509766b0a48ff4e153a5e50f8`, с указанным upstream образом
  `registry.k8s.io/networking/kube-network-policies:v1.1.1`.
  Это отдельный privileged node policy-controller, добавляемый к kindnetd;
  CNI не заменяется. Он нужен для фактического исполнения NetworkPolicy.

JSON manifests — обычные Kubernetes manifests (JSON является подмножеством YAML),
читаются `kubectl apply`/Kustomize. Редактируйте values/renderer, затем регенерируйте.
Секретных значений в них нет. `.local/` и `.tools/` игнорируются Git.

## Установка и развёртывание

Все команды из корня. Нужны Docker Desktop с работающим Kubernetes, kubectl,
Python 3, OpenSSL, Go по go.mod и JDK 25. Используется только явный контекст
`docker-desktop`; не переключайте скрипты на production.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
python3 scripts/init-local.py
python3 infra/kubernetes/tool/install.py
python3 infra/kubernetes/tool/render.py
python3 infra/kubernetes/tool/storage.py
python3 infra/kubernetes/tool/build.py --load
python3 infra/kubernetes/tool/deploy.py
python3 infra/kubernetes/tool/smoke.py
```

`init-local.py` нужен для локального CA, сохраняет существующие credentials.
`install.py` проверяет закреплённый SHA-256 istioctl macOS arm64, устанавливает
policy-controller и Istio. Не запускайте его поверх другого policy engine без
проверки совместимости. Istio 1.31 поддерживает Kubernetes 1.36.

`build.py` собирает bootJar pinned wrappers, статические Go Linux binary и
локальные образы, импортирует их в containerd контейнера `desktop-control-plane`.
Никакой registry push не выполняется. `--skip-compile --load` допустим только
при наличии актуальных артефактов. Другой provisioner/архитектура требует своего
механизма доставки образов; скрипт не угадывает чужие ноды.

`deploy.py` создаёт namespace/изоляцию до workloads, отдельные случайные credentials
в `.local/credential.json` (0600), Secret каждого DB/service credential и ключ
AES-GCM вне БД. Сертификат ingress создаётся от существующего локального CA с SAN
`127.0.0.1`/`tictactoe.test`; CA private key в кластер не передаётся. Затем поднимаются
stores, применяются миграции под отдельными ролями, bootstrap streams и приложения.
После rollout скрипт отдельно ждёт HTTP health каждого приложения через временные
внутренние туннели: Running container ещё не означает завершённый Spring startup.
Leaf содержит AKI/SKI и key usage для строгой TLS-проверки. Для явного перевыпуска
сертификата с тем же ключом: `python3 infra/kubernetes/tool/deploy.py --renew-certificate`.
Повтор deploy сохраняет secrets/PVC и проверяет immutable миграции. Изменения
образов с тем же local tag требуют явного restart соответствующего Deployment;
не пересоздавайте PVC ради обновления приложения.

`LoadBalancer` доступен согласно возможностям Docker Desktop. Для гарантированного
локального доступа используйте туннель (отдельный терминал):

```sh
kubectl --context docker-desktop -n tictactoe-ingress port-forward --address=127.0.0.1 service/ingress 8443:443
```

Внешний API: `https://tictactoe.test:8443/api/v1/**`, WSS: `wss://tictactoe.test:8443/wss`.
Для ручного обращения настройте локальное DNS/hosts-сопоставление `tictactoe.test` →
`127.0.0.1` либо используйте `curl --resolve tictactoe.test:8443:127.0.0.1
--cacert infra/local/certs/ca.crt https://tictactoe.test:8443/api/v1/auth/csrf`.
Сам smoke разрешает имя только для своего соединения, не меняя системный DNS.
Используйте имя из сертификата и SNI; подключение по голому IP не поддерживается
именованным TLS-listener. Wildcard host не используется.
HTTP listener/redirect на порт 80 отсутствует. Для браузерного клиента Origin
должен точно совпадать с `webOrigin`; текущий отдельный Vite LAN-стенд продолжает
работать со своими локальными backend-настройками. Здесь проверяется backend,
а не перенос Web из `web/` в Kubernetes.

## Что проверяет smoke

`smoke.py` сам открывает/закрывает свой port-forward на 127.0.0.1:8443; порт должен
быть свободен. Использует реальную проверку CA/SAN, отвергает неправильное имя и
отсутствие доверия. Проверяет HTTPS csrf/session/register/login, защищённый вызов
Game через Spring Gateway, WS upgrade, ticket auth и heartbeat через ingress.
Создаёт уникальный тестовый аккаунт и выполняет logout; данные не удаляет.
Непубликованные пути дают 404, cleartext на TLS-порту не обслуживается.

Для каждого backend и ingress проверяет наличие Envoy и выгруженные upstream
clusters: для app/data нет TLS transport socket или auto-mTLS transport matches.
Для backend проверяет inbound application filter chains без TLS. Control-plane
xDS/CA использует собственный TLS, это не application data-plane соединение.
Проверка сертификатов нигде не отключается.

Создаёт временный pod в отдельном недоверенном namespace: ingress:443 доступен,
а ingress:80, все backend ClusterIP/PodIP и хранилища недоступны по TCP.
Положительный контроль ingress отличает сетевой запрет от сломанной сети.
Namespace теста удаляется в finally; workloads/данные стенда сохраняются.
Отчёт без credentials — `.local/smoke-report.json`.

Внутри mesh нет cryptographic source principal: защита основана на сетевых
selectors/RBAC и проверяемых приложениями service credentials. Право создавать
pods с доверенными labels остаётся административным; NetworkPolicy не заменяет
RBAC и не защищает от администратора ноды.

## Проверки без развёртывания

```sh
python3 infra/kubernetes/tool/render.py --check
python3 infra/kubernetes/tool/storage.py --check
kubectl kustomize infra/kubernetes/base
kubectl kustomize infra/kubernetes/storage
python3 -m unittest discover -s scripts/tests -v
openspec validate pvp-platform-v1 --strict
```

При работающем стенде:

```sh
kubectl --context docker-desktop apply --dry-run=server -k infra/kubernetes/base
kubectl --context docker-desktop apply --dry-run=server -k infra/kubernetes/storage
infra/kubernetes/.tools/istioctl --context docker-desktop analyze -n tictactoe
```

Диагностика: `kubectl --context docker-desktop get pods -n tictactoe`,
`kubectl --context docker-desktop logs -n tictactoe deployment/<service> -c <service>`.
Не печатайте Secret, `.local/credential.json`, login-ответы или ConfigDump с
секретными данными в отчёты. Для остановки без удаления данных масштабируйте
Deployments и StatefulSets в ноль. Удаление namespace/PVC может удалить данные;
оно не входит в обычный перезапуск. Не удаляйте `.local/credential.json` при
сохранённых PostgreSQL PVC: пароли автоматически не ротируются.

Источники: [Istio compatibility](https://istio.io/latest/docs/releases/supported-releases/),
[gateway injection](https://istio.io/latest/docs/setup/additional-setup/gateway/),
[authentication policies](https://istio.io/latest/docs/tasks/security/authentication/authn-policy/),
[NetworkPolicy controller](https://kube-network-policies.sigs.k8s.io/docs/quick-start/).
