# Istio egress и проверка внешнего TLS

Kubernetes ресурсы — `chart/platform/templates/egress.yaml`, Istio —
`chart/mesh/templates/egress.yaml`. Egress — самостоятельный gateway Deployment
с ClusterIP Service в `tictactoe-egress`; внешний LoadBalancer не добавляется.
Приложение отправляет HTTP к разрешённому host, mesh VirtualService направляет
его на egress Service, gateway завершает внутренний HTTP hop и устанавливает TLS
к внешней системе. PeerAuthentication/DestinationRule DISABLE сохраняют plaintext
внутри mesh. External DestinationRule SIMPLE ограничен workload selector gateway,
проверяет CA из Vault/VSO Secret и явный SAN/SNI. Проверку сертификата не отключать.

NetworkPolicy разрешает backend→egress:8080 и только egress→указанный внешний IP
на тестовых TLS-портах 18443/18444; прямой backend→внешний IP не разрешён.
ServiceEntry/VirtualService сами по себе не являются сетевым запретом обхода.

Проверка из корня:

```sh
infra/kubernetes/.tools/venv/bin/python infra/kubernetes/tool/egress.py
```

Тест устанавливает gateway и CA policies, затем поднимает **временные** Python TLS
listeners на MacBook 18443/18444, доступные из Docker Desktop через
host.docker.internal (сейчас 192.168.65.254). Это endpoint вне Kubernetes, а не
внутрикластерный HTTP mock. Сертификаты/ключи находятся в закрытых Vault paths
`platform/authority/egress-test-*`, процессы получают их в временные файлы 0600.
Trusted CA берётся из Vault authority; invalid endpoint использует другую CA.
Listeners удаляются/закрываются в finally, постоянный внешний сервис не создаётся.

Внутренний probe Pod с sidecar обращается к ServiceEntry VIP 240.240.0.10 по HTTP.
Проверяется 200 через gateway; прямое HTTPS-соединение с корректным CA должно
блокироваться на TCP, а не из-за неверного сертификата. Затем endpoint port
переключается на недоверенный TLS server: требуются 503 и **рост** Envoy
ssl.fail_verify counters. В finally восстанавливается доверенный маршрут, после
чего повторно проверяется 200. Pod удаляется; gateway и Vault CA остаются.

Для нового реального внешнего назначения отдельно согласуйте host/IP/порт и CA,
обновите ServiceEntry, routes, SAN/SNI и узкую NetworkPolicy. Не добавляйте
0.0.0.0/0 или wildcard host. V1 не включает внешние auth/email интеграции.
Если Docker Desktop изменил host gateway IP, проверьте его на ноде и скорректируйте
values до теста. Неверный/просроченный сертификат не исправляется insecure flags.
