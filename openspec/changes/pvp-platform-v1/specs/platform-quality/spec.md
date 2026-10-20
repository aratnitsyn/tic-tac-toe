## Purpose

Определяет нефункциональные ограничения общей платформы и условия воспроизводимой проверки её вместимости и корректности.

## ADDED Requirements

### Requirement: Secure API routes and transport
Система SHALL предоставлять внешний API через HTTPS по /api/v1/**, WebSocket — через WSS по /wss. Входящий TLS SHALL завершаться на ingress gateway; внутри Istio mesh сервисы используют HTTP/WS без TLS/mTLS. Внешние HTTP/WS варианты и fallback запрещены. Исходящий HTTP-трафик к внешним системам SHALL проходить через egress gateway с TLS origination и проверкой сертификата внешнего узла.

#### Scenario: HTTPS API route
- **WHEN** клиент вызывает https://<host>/api/v1/ с действующей авторизацией
- **THEN** ingress завершает TLS и направляет внутренний HTTP-запрос в Spring Cloud API Gateway через mesh

#### Scenario: Secure WebSocket route
- **WHEN** клиент устанавливает wss://<host>/wss
- **THEN** ingress завершает TLS и направляет WS в Realtime через mesh с предусмотренной аутентификацией

#### Scenario: Internal plaintext transport
- **WHEN** backend сервисы взаимодействуют внутри mesh
- **THEN** HTTP-вызовы не требуют application TLS и не оборачиваются в proxy mTLS, а внутренние TCP хранилища используют собственные протоколы

#### Scenario: External TLS validation
- **WHEN** устанавливается внешнее TLS-соединение с недоверенным сертификатом либо несовпадающим именем узла
- **THEN** соединение отклоняется без отключения проверки и fallback на cleartext

#### Scenario: Egress boundary
- **WHEN** сервис обращается по HTTP к разрешённой внешней системе через egress gateway
- **THEN** egress устанавливает проверенное TLS-соединение с внешним HTTPS endpoint

#### Scenario: Obsolete realtime endpoint
- **WHEN** клиент пытается подключиться к WebSocket по пути /realtime
- **THEN** Realtime endpoint на этом пути не предоставляется

### Requirement: Concurrent user capacity
Система SHALL поддерживать целевую нагрузку 100 000 одновременно активных пользователей суммарно в меню, лобби, очереди, подготовке и игре. Это общий лимит, а не 100 000 пользователей в каждом состоянии. Профиль испытания SHALL задавать распределение и интенсивность действий.

#### Scenario: Mixed capacity test
- **WHEN** выполняется испытание с суммарно 100 000 активных пользователей по зафиксированному профилю
- **THEN** измеряются ошибки, задержки и соблюдение игровых инвариантов во всех задействованных состояниях

### Requirement: Client behavior parity
Web, iOS и Android SHALL предоставлять согласованные возможности регистрации, входа, очереди, лобби, готовности, игры, сдачи и восстановления. Серверные правила времени и доступа одинаковы для всех клиентов.

#### Scenario: Cross-platform match
- **WHEN** соперники используют разные поддерживаемые платформы
- **THEN** они участвуют в одной партии по одинаковым правилам

### Requirement: Idempotent state changes
Изменяющие состояние команды SHALL поддерживать распознавание повторного выполнения одной операции. Повтор не создаёт дополнительное участие, ход или результат. Конкурирующие команды одной версии SHALL не нарушать инварианты игры и участия.

#### Scenario: Lost command response
- **WHEN** клиент повторяет ту же операцию после потери ответа
- **THEN** побочный эффект не применяется повторно и клиент может установить её результат

### Requirement: Authoritative state synchronization
Система SHALL предоставлять актуальное версионированное состояние матча и участия для восстановления после пропущенных или повторных событий. Запоздалое событие не должно откатывать отображение к более старой версии.

#### Scenario: Missed game events
- **WHEN** клиент обнаружил пропуск или переподключился
- **THEN** он может получить текущее состояние независимо от доставки всех прошлых событий

### Requirement: Preserve acknowledged moves
Система SHALL сохранять подтверждённые ходы и завершённые результаты при перезапуске экземпляра сервиса. Повторная обработка после сбоя не создаёт дополнительного результата.

#### Scenario: Restart after acknowledgement
- **WHEN** обработчик перезапущен после подтверждения хода клиенту
- **THEN** восстановленное состояние содержит этот ход

### Requirement: Protect authenticated operations
Система SHALL проверять действующую сессию и права на команды и подписки, защищать внешний трафик шифрованием и не раскрывать пароль в ответах, событиях или логах. Способ хранения паролей SHALL не позволять получить исходный пароль чтением хранилища.

#### Scenario: Unauthorized subscription
- **WHEN** игрок пытается подписаться на приватное состояние чужого матча
- **THEN** доступ отклоняется

### Requirement: Reproducible quality verification
Отчёт о нагрузочной проверке SHALL указывать ресурсы среды, версии компонентов, длительность, профиль пользователей, частоту действий, условия сети, ошибки и перцентили задержек. Отдельно SHALL проверяться конкуренция команд, повторная доставка, переподключения и перезапуск экземпляра.

#### Scenario: Capacity report
- **WHEN** заявляется достижение цели 100 000 пользователей
- **THEN** доступен воспроизводимый отчёт, а наличие соединений без сценариев активности не считается достаточным доказательством

### Requirement: JVM backend runtime baseline
JVM backend сервисы SHALL собираться и запускаться на JDK 25 с JVM target 25 и использовать последний стабильный patch Spring Boot ветки 3.5.x. Версия SHALL закрепляться точным номером и обновляться согласованно после проверки, без динамических диапазонов. Текущая проверенная по документации версия — 3.5.16; это не означает успешную проверку сборки проекта.

#### Scenario: JVM backend configuration
- **WHEN** проверяется конфигурация JVM backend сервисов
- **THEN** toolchain и target равны 25, а версия Spring Boot закреплена на едином стабильном patch 3.5.x

#### Scenario: Spring Boot patch upgrade
- **WHEN** выходит новый стабильный patch Spring Boot 3.5.x
- **THEN** после проверки совместимости обновляется точный номер во всех JVM backend сервисах; переход на 4.x автоматически не выполняется

### Requirement: Helm deployment and separate YAML manifests
Инфраструктура и приложения SHALL устанавливаться и обновляться в подготовленном Kubernetes-кластере через Helm с закреплёнными версиями charts и образов. Kubernetes и Istio SHALL описываться в отдельных charts/каталогах, а manifests, templates и values SHALL храниться только в YAML. Создание узлов и control plane является предварительным условием, а не обязанностью Helm.

#### Scenario: Reproducible Helm installation
- **WHEN** оператор устанавливает платформу в подготовленный кластер по инструкции
- **THEN** helm lint и helm template проходят, отдельные Kubernetes/Istio YAML описания устанавливаются в порядке зависимостей и HTTPS/WSS smoke подтверждает принятые маршруты и границы TLS

#### Scenario: Upgrade and rollback
- **WHEN** проверяется обновление и совместимый rollback Helm-релиза
- **THEN** конфигурация воспроизводима, durable данные сохраняются, а миграции БД не считаются автоматически отменёнными

### Requirement: Vault secret and certificate source
Vault SHALL быть единым источником инфраструктурных секретов, ключей и сертификатов Kubernetes-окружения. Интеграция SHALL использовать Kubernetes ServiceAccount и минимальные права по namespace/сервису. Kubernetes Secret SHALL быть только автоматически синхронизируемой копией из Vault для необходимого потребителя. Секреты SHALL отсутствовать в Git, Helm values, ConfigMap и логах; доменные password hashes и session state остаются в БД.

#### Scenario: Isolated secret access
- **WHEN** workload запрашивает секрет через интеграцию с Vault
- **THEN** ServiceAccount получает только разрешённые материалы своего сервиса, а доступ к чужим секретам отклоняется

#### Scenario: Secret and certificate rotation
- **WHEN** credentials, ключ или сертификат обновлён в Vault
- **THEN** потребитель получает обновление по документированной процедуре, использует действующие материалы, а старые ключи login-ответов сохраняются до окончания необходимого срока чтения

#### Scenario: Vault unavailable at startup
- **WHEN** Vault недоступен и обязательные материалы ещё не получены
- **THEN** workload не становится ready и не использует локальные credential-файлы либо встроенные значения как fallback

#### Scenario: Existing environment migration
- **WHEN** существующий Kubernetes-стенд переводится на Vault
- **THEN** доступ к действующим БД, данным и зашифрованным login-ответам сохраняется, а дальнейшая доставка секретов и TLS-материалов выполняется из Vault

### Requirement: Application configuration through ConfigMap
Все несекретные параметры приложений Kubernetes-окружения SHALL передаваться через ConfigMap посредством envFrom/configMapKeyRef либо конфигурационного mount. Секретные параметры SHALL поступать из Vault через предусмотренную интеграцию и не попадать в ConfigMap.

#### Scenario: Configuration update
- **WHEN** параметр приложения изменяется в YAML values и применяется Helm upgrade
- **THEN** соответствующий ConfigMap обновляется, приложение получает значение через документированный rollout или reload и фактическая конфигурация проверяется

### Requirement: Explicit Istio service routing
HTTP/WS-маршрутизация между сервисами SHALL явно описываться через VirtualService, DestinationRule и необходимые связанные Istio ресурсы в отдельных YAML manifests. Для внешних границ SHALL использоваться Gateway и необходимые ServiceEntry. Маршруты SHALL сохранять /api/v1/**, точный /wss и внутренний HTTP/WS без TLS/mTLS; NetworkPolicy SHALL независимо ограничивать доступ.

#### Scenario: Mesh route verification
- **WHEN** сервис выполняет разрешённый межсервисный HTTP-вызов
- **THEN** применяются объявленные mesh VirtualService/DestinationRule, Envoy config подтверждает назначение и отсутствие TLS для внутреннего игрового маршрута

#### Scenario: Network isolation remains enforced
- **WHEN** источник без разрешения NetworkPolicy пытается обратиться к внутреннему API или хранилищу
- **THEN** соединение блокируется независимо от наличия Istio-маршрута
