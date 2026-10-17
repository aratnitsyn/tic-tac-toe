## Purpose

Описывает конечный автомат встречи двух игроков от формирования пары до отмены подготовки или завершения единственной партии.

**Состояния:** `WAITING_READY`, `COUNTDOWN`, `RUNNING`, `CANCELLED`, `FINISHED`. Начальное — `WAITING_READY`. Конечные — `CANCELLED` и `FINISHED`.

```text
START --> WAITING_READY --> COUNTDOWN --> RUNNING --> FINISHED
               |               |
               |               | disconnect
               |               v
               +----------> CANCELLED
                 ready timeout / readiness disconnect
```

| Откуда | Событие / условие | Куда | Эффект |
|---|---|---|---|
| Создание | Пара сформирована из очереди или лобби | WAITING_READY | Закрепить обоих, установить общий срок +10 секунд |
| WAITING_READY | Подтвердил один до срока | WAITING_READY | Сохранить подтверждение без продления срока |
| WAITING_READY | Оба подтвердили до срока | COUNTDOWN | Установить срок старта +5 секунд |
| WAITING_READY | Истекли 10 секунд, подтверждены не оба | CANCELLED | Индивидуальный возврат участников |
| WAITING_READY | Обнаружено отключение участника | CANCELLED | Отключённый в меню, подключённый по источнику |
| COUNTDOWN | Оба отключились | CANCELLED | Оба в меню |
| COUNTDOWN | Игрок отключился | CANCELLED | Без поражения и расхода запаса восстановления |
| COUNTDOWN | Истекли 5 секунд, оба подключены | RUNNING | Начать партию с хода X |
| RUNNING | Партия приостановлена или возобновлена | RUNNING | Состояние матча не меняется |
| RUNNING | Партия завершена | FINISHED | Сохранить результат и вернуть обоих в меню |

Внутри WAITING_READY хранятся признаки готовности A и B:

```text
NONE_READY --> ONLY_A_READY --> BOTH_READY --> COUNTDOWN
     |
     +-------> ONLY_B_READY --> BOTH_READY --> COUNTDOWN
```

`BOTH_READY` — условие перехода, а не отдельное устойчивое состояние матча. Повтор подтверждения одного игрока не подтверждает второго.

Связь сущностей: в WAITING_READY и COUNTDOWN игроки уже IN_MATCH, но партия ещё не началась. В RUNNING партия находится в X_TURN, O_TURN или PAUSED. CANCELLED не имеет игрового результата; FINISHED содержит результат завершённой партии. Один матч первой версии содержит одну партию.

## ADDED Requirements

### Requirement: Match lifecycle states
Система SHALL создавать матч в WAITING_READY при формировании пары и поддерживать переходы по таблице автомата. Только RUNNING допускает начавшуюся партию; пауза партии не отменяет и не завершает матч.

#### Scenario: Match created from lobby
- **WHEN** в лобби появляется второй участник
- **THEN** матч создаётся в WAITING_READY, оба участника закреплены за ним

### Requirement: Readiness and countdown transitions
Система SHALL переводить WAITING_READY в COUNTDOWN только после двух своевременных подтверждений, а COUNTDOWN в RUNNING — после 5 секунд при подключённых участниках. Первое подтверждение не продлевает общий срок готовности 10 секунд.

#### Scenario: Single confirmation
- **WHEN** A подтверждает на восьмой секунде, а B не подтверждает
- **THEN** матч остаётся WAITING_READY до первоначального срока и затем переходит в CANCELLED

#### Scenario: Countdown completed
- **WHEN** оба подтвердили готовность и предстартовые 5 секунд истекли без отключений
- **THEN** матч переходит в RUNNING, начинается ход X с лимитом 5 секунд

### Requirement: Cancel before game starts
Система SHALL переводить подготовку в CANCELLED при истечении срока готовности без двух подтверждений либо при отключении во время COUNTDOWN. Отмена не создаёт игровое поражение и не расходует запас восстановления; возврат участников определяется их участием и источником подбора.

#### Scenario: Countdown disconnect
- **WHEN** участник отключается в COUNTDOWN
- **THEN** матч становится CANCELLED без запуска партии и таймера восстановления

### Requirement: Terminal match states
Система SHALL переводить RUNNING в FINISHED при завершении партии. CANCELLED и FINISHED терминальны: поздние команды не возобновляют матч. Новая сформированная пара требует нового матча и новых подтверждений.

#### Scenario: Late ready command
- **WHEN** подтверждение поступило для CANCELLED
- **THEN** матч остаётся CANCELLED, новый отсчёт не запускается

#### Scenario: Game paused
- **WHEN** партия матча RUNNING переходит в PAUSED
- **THEN** матч остаётся RUNNING и сохраняет обоих участников

### Requirement: Single preparation outcome
Система SHALL применять не более одного итогового перехода при конкуренции подтверждения, отмены и старта. Повтор события не запускает вторую партию и не выполняет повторный возврат участника.

#### Scenario: Repeated start event
- **WHEN** событие истечения COUNTDOWN обрабатывается повторно после начала игры
- **THEN** матч остаётся RUNNING с одной и той же партией

### Requirement: Readiness disconnect transition
Система SHALL выполнять WAITING_READY → CANCELLED при отключении участника. Если в COUNTDOWN отключились оба, результат — CANCELLED с переводом обоих в MENU. Подтверждение ровно в дедлайн считается опоздавшим.

#### Scenario: Readiness disconnect
- **WHEN** в WAITING_READY обнаружено отключение одного участника
- **THEN** матч переходит в CANCELLED без игрового поражения с индивидуальным возвратом участников
