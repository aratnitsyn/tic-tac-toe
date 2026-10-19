# Web — Ход

React/TypeScript клиент в корневом `web/`. Все продуктовые запросы same-origin:
HTTPS `/api/v1/**`, WSS `/wss`. Session token недоступен JavaScript:
Secure HttpOnly SameSite cookie, CSRF header получается через `/auth/csrf`.

## Установка и сборка

Проверяемое окружение: Node 26.11.1, npm 11.20.0, Chromium из Playwright 1.64.1.
Версии зависимостей закреплены в package.json/package-lock.json. Из `web/`:

```sh
npm ci
npm run typecheck
npm run lint
npm run contract
npm run build
```

Типы API генерируются из `contracts/http/public.openapi.json` командой
`python3 tool/generate-contract.py`. Проверка `contract` выявляет расхождение.
Адаптер проверяет используемые поля ответа; это не полная schema-validation
репозитория, установка которой отложена. Форматирование: `npm run format`.

## Локальный запуск

Подготовьте инфраструктуру, credentials, миграции и streams по
[local.md](../docs/runbooks/local.md) и [realtime.md](../docs/runbooks/realtime.md).
Соберите Auth, Game, Lobby, API Gateway на JDK 25; Matchmaking и Realtime — Go.
Из корня в отдельных терминалах запустите каждый из шести сервисов:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
python3 scripts/run-service.py auth
python3 scripts/run-service.py game
python3 scripts/run-service.py lobby
python3 scripts/run-service.py matchmaking
python3 scripts/run-service.py api-gateway
python3 scripts/run-service.py realtime
```

Для loopback-режима из корня можно выпустить leaf certificate с SAN IP:127.0.0.1:

```sh
python3 web/tool/init-tls.py
```

Скрипт использует существующий CA `infra/local/certs/ca.crt`, не заменяет
сертификаты и не устанавливает доверие. Явно добавьте этот CA в доверенные
сертификаты своего локального браузера. Не отключайте проверку сертификатов.
Ключи/сертификаты игнорируются Git; CA и private keys не публикуйте.
Из `web/`:

```sh
npm run dev
```

Текущий локальный стенд настроен для LAN-адреса MacBook: откройте
`https://192.168.0.94:3000` на обоих устройствах. Vite слушает `WEB_HOST=192.168.0.94`;
Gateway и Realtime разрешают тот же Origin. В `web/local.env` указан отдельный
сертификат `web-lan.crt`/`web-lan.key` с SAN `192.168.0.94`, подписанный локальным CA.
Он уже выпущен на этом MacBook и игнорируется Git; `init-tls.py` выпускает только
loopback-сертификат и не заменяет LAN-сертификат. На каждом клиентском устройстве
нужно явно установить доверие к публичному `infra/local/certs/ca.crt`; приватные
ключи остаются на MacBook. В брандмауэре macOS разрешите входящие подключения Node.js.
При смене LAN IP согласованно обновите `WEB_HOST`, оба Origin и SAN сертификата.
Внутренние адреса Gateway/Realtime остаются `127.0.0.1`.

Настройки читаются из `web/local.env`,
окружение имеет приоритет. Vite — локальный TLS proxy к внутренним HTTP/WS
сервисам; production build — статический `web/dist`, его API/WSS маршрутизацию
и TLS обеспечивает будущий Istio ingress. `npm run preview` использует те же
локальные TLS/proxy настройки. Пароли и session token не хранятся в localStorage,
sessionStorage, URL или логах. Две вкладки одного браузерного профиля используют
одну cookie и заменяют socket generation; для двух игроков нужны два профиля.

## Проверки в браузере

После сборки сервисов и инициализации локального стенда, из `web/`:

```sh
npx playwright install chromium
npm run build
JAVA_HOME="$(/usr/libexec/java_home -v 25)" npm test
```

Тесты запускают/останавливают только свои шесть backend-процессов, сохраняют
созданные тестовые аккаунты/партии; существующие данные не удаляются. Порт
`127.0.0.1:19090` должен быть свободен. Логи — во временном `ttt-web-test-*`.
Playwright подставляет синтетическую browser network boundary для same-origin
страницы: static assets из `dist`, HTTP — в настоящий Gateway, socket frames —
в настоящий Realtime. Cookie jar, Origin, CSRF, реальные БД и обработчики команд
работают штатно. Проверка сертификатов нигде не отключена; это не проверка
внешнего Istio/TLS. Игровые сроки проверяются реальными серверами, без подмены БД.

Сценарии: критерии пароля, активная сессия на другом клиенте, cookie/CSRF,
восстановление сессии после reload, выход, Lobby/queue cancel, оба пути подбора,
возврат после неподтверждения, ready/countdown, ход с клавиатуры, сдача,
тайм-аут, потерянное последнее событие и pause/reconnect/foreground.

## Управление и синхронизация

Tab/Shift+Tab перемещают фокус, Enter/Space активируют кнопки. На поле работают
стрелки и Home/End; каждая клетка имеет подпись строки, столбца и содержимого.
Недоступный ход остаётся доступен фокусу с aria-disabled; сервер проверяет ход.
Таймеры имеют role=timer без непрерывных live announcements. Ошибки — role=alert,
результат — role=status. Интерфейс адаптируется к узким экранам.

Сначала auth ticket, затем subscribe и resync_required barrier, после него HTTP
snapshots; входящие события ограничены 64 кадрами и подтверждаются после сверки.
При обнаружении нового матча клиент отправляет heartbeat перед subscribe: Realtime
успевает зафиксировать участие до барьера и показа готовности, даже если назначение
пары произошло между периодическими heartbeat.
Версии MATCH/GAME независимы, старые snapshots не откатывают состояние. Heartbeat
2 секунды, сверка 5 секунд и при online/foreground/reconnect. При обрыве берётся
новый одноразовый ticket в существующей cookie-сессии. После HTTP timeout
доступен повтор того же commandId; другая команда до разрешения неизвестного
исхода заблокирована. Таймеры отображаются по serverTime и deadline и не
определяют результат локально. Вместимость 100 000 пользователей не измерена.
