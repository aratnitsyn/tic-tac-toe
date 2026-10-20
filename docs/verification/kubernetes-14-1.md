# Проверка Kubernetes / Istio 14.1

Дата: 20 октября 2026 (Europe/Moscow). Только функциональная проверка 14.1.
Контекст `docker-desktop`, kind provisioner, одна ARM64-нода Kubernetes 1.36.1;
Docker: 4 CPU, 8 GiB RAM. Istio 1.31.2, kube-network-policies v1.1.1
(закреплённый upstream manifest из тега v1.1.2).

Развёрнуты восемь backend Deployments (Realtime socket и dispatcher отдельно),
девять Envoy вместе с ingress, PostgreSQL/Redis/NATS с отдельными PVC и credentials.
Все восемь внутренних HTTP health endpoints прошли проверку после повторного deploy.
Существующие Compose volumes и процессы на MacBook не изменялись.

| Проверка | Результат |
|---|---|
| Сборка пяти bootJar на JDK 25 и двух статических Go Linux images; импорт в containerd | Успешно |
| Миграции шести databases под отдельными ролями, повторное применение | Успешно |
| Bootstrap bounded JetStream streams, повторный deploy | Успешно |
| Server-side validation manifests base/storage | Успешно |
| Istio analyze backend/ingress | Без validation issues |
| HTTPS csrf, отказ session без авторизации, register/login и защищённое участие через Game | Успешно |
| WSS `/wss`: upgrade, ticket auth, heartbeat | Успешно |
| `/realtime`, `/wss/`, `/api/v1`, `/internal/v1/sessions/introspect`, `/actuator/health` | HTTP 404 с ingress |
| Envoy upstream transport для app/data | 11 plaintext clusters на каждом из 9 прокси, TLS transport sockets/matches отсутствуют |
| Backend inbound application filter chains | Без TLS transport/socket match |
| Прямой TCP к backend и stores из недоверенного namespace | Заблокировано: 22 проверки ClusterIP/PodIP |
| Ingress 443 / 80 из того же probe | 443 разрешён, 80 закрыт: 2 проверки |
| Положительный HTTPS контроль из probe со strict CA validation Python 3.13 | Успешно |
| Неподходящий SNI/host и отсутствие доверия к CA | Соединение отклонено |
| Cleartext HTTP на TLS-порту | Продуктовый запрос не обслуживается |
| Logout созданного smoke-аккаунта | Успешно |
| Python unit suite | 18 тестов, без пропусков |
| Render consistency, Kustomize render, Python compile, OpenSpec strict, diff whitespace | Успешно |

Воспроизведение: [инструкция](../../infra/kubernetes/README.md), затем
`python3 infra/kubernetes/tool/smoke.py`. Локальный машинный отчёт находится в
игнорируемом `infra/kubernetes/.local/smoke-report.json`; credentials в отчёт
не включаются. Smoke сохраняет уникальный тестовый аккаунт и удаляет только свой
временный namespace; свои port-forward процессы завершает.

Использован именованный TLS host `tictactoe.test` с SNI; wildcard listener не
применяется. Контрольный TLS leaf содержит AKI/SKI/key usage, strict validation не
ослаблена. Control-plane xDS/CA TLS не входит в проверку plaintext data plane.

Не проверялись: 100 000 пользователей, HA/rolling continuity, ресурсные SLO,
egress TLS origination, dashboards/alerts, backup/restore. Это отдельные пункты
14.2–14.6 и этап 15; данная проверка не закрывает их.
