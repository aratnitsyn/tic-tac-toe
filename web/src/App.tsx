import { useEffect, useState } from 'react';
import { ApiClient, ApiError, errorMessage } from './api/client';
import type { SessionResponse } from './api/contract';
import { AuthForm } from './auth/AuthForm';
import { Board } from './game/Board';
import { Synchronizer, type View } from './realtime/synchronizer';

const api = new ApiClient();
const reason: Record<string, string> = {
    WINNING_LINE: 'Три в ряд',
    BOARD_FULL: 'Поле заполнено',
    TURN_TIMEOUT: 'Время хода истекло',
    SURRENDER: 'Сдача',
    DISCONNECT_TIMEOUT: 'Время восстановления истекло',
    BOTH_DISCONNECTED_TIMEOUT: 'Оба игрока не восстановили соединение',
};
export function App() {
    const [session, setSession] = useState<SessionResponse['data']>();
    const [loading, setLoading] = useState(true);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');
    const [notice, setNotice] = useState('');
    const [code, setCode] = useState('');
    const [view, setView] = useState<View>({ connected: false, syncing: true });
    const [sync, setSync] = useState<Synchronizer>();
    const [now, setNow] = useState(api.now());
    async function restore() {
        setLoading(true);
        try {
            setSession(
                (
                    await api.get<SessionResponse>(
                        '/auth/session',
                        'SessionResponse',
                    )
                ).data,
            );
            setLoading(false);
        } catch (e) {
            if (e instanceof ApiError && e.status === 401) {
                setSession(undefined);
                setLoading(false);
            } else setError(errorMessage(e));
        }
    }
    useEffect(() => {
        void restore();
        const clock = setInterval(() => setNow(api.now()), 100);
        return () => clearInterval(clock);
    }, []);
    useEffect(() => {
        if (!session) {
            setView({ connected: false, syncing: true });
            return;
        }
        const client = new Synchronizer(api, setView, () => {
            setSession(undefined);
            setError('Сессия завершена. Войдите снова.');
        });
        setSync(client);
        client.start();
        return () => client.stop();
    }, [session]);
    async function action(task: () => Promise<unknown>) {
        if (busy) return;
        setBusy(true);
        setError('');
        setNotice('');
        try {
            await task();
            await sync?.refresh();
        } catch (e) {
            setError(errorMessage(e));
            if (
                e instanceof ApiError &&
                [
                    'VERSION_CONFLICT',
                    'PARTICIPATION_CONFLICT',
                    'DEADLINE_EXCEEDED',
                ].includes(e.code)
            )
                await sync?.refresh();
        } finally {
            setBusy(false);
        }
    }
    function auth(mode: 'login' | 'register', email: string, password: string) {
        void action(async () => {
            await api.command(
                '/auth/' + mode,
                mode === 'login' ? 'SessionResponse' : 'AccountResponse',
                { email, password, clientType: 'WEB' },
            );
            if (mode === 'login') await restore();
            else setNotice('Аккаунт создан. Теперь войдите.');
        });
    }
    const p = view.participation;
    const m = view.match;
    const g = view.game;
    const locked = busy || !view.connected || view.syncing || api.hasPending();
    const seconds = (deadline: string | undefined) =>
        deadline
            ? Math.max(0, (Date.parse(deadline) - now) / 1000).toFixed(1)
            : '—';
    function command(
        path: string,
        schema: string,
        version: number,
        extra?: object,
    ) {
        void action(() =>
            api.command(path, schema, { expectedVersion: version, ...extra }),
        );
    }
    const result = g?.result;
    const resultLabel =
        result?.outcome === 'DRAW'
            ? 'Ничья'
            : result?.outcome === 'NO_WINNER'
              ? 'Без победителя'
              : result?.winnerId === session?.userId
                ? 'Вы победили'
                : 'Вы проиграли';
    return (
        <div className="shell">
            <header>
                <a href="/" className="brand" aria-label="Ход — главная">
                    <span aria-hidden="true">× ○</span> ход
                    <span className="brand-point">.</span>
                </a>
                <div className="header-right">
                    <span className="edition">КРЕСТИКИ-НОЛИКИ / PvP</span>
                    {session && (
                        <button
                            className="text-button"
                            disabled={busy || api.hasPending()}
                            onClick={() => {
                                void action(async () => {
                                    await api.command(
                                        '/auth/logout',
                                        'LogoutOperationResponse',
                                    );
                                    sync?.stop();
                                    setSession(undefined);
                                    setNotice(
                                        'Вы вышли. Очистка участия продолжается на сервере.',
                                    );
                                });
                            }}
                        >
                            Выйти
                        </button>
                    )}
                </div>
            </header>
            <main>
                {error && (
                    <div className="banner error" role="alert">
                        {error}
                        {api.hasPending() && (
                            <button
                                onClick={() => {
                                    void action(async () => {
                                        await api.retry();
                                        if (!session) await restore();
                                    });
                                }}
                                disabled={busy}
                            >
                                Повторить неподтверждённое действие
                            </button>
                        )}
                    </div>
                )}
                {notice && (
                    <div className="banner" role="status">
                        {notice}
                    </div>
                )}
                {loading ? (
                    <div>
                        <p role="status">Проверяем сессию…</p>
                        {error && (
                            <button
                                disabled={busy}
                                onClick={() => {
                                    void action(restore);
                                }}
                            >
                                Повторить проверку сессии
                            </button>
                        )}
                    </div>
                ) : !session ? (
                    <div className="welcome">
                        <div className="intro">
                            <div className="eyebrow">КЛАССИКА НА ДВОИХ</div>
                            <h2>
                                Каждая клетка
                                <br />
                                имеет значение<span>.</span>
                            </h2>
                            <p>
                                Пригласите друга или найдите соперника.
                                <br />
                                Всё решит следующий ход.
                            </p>
                            <div className="intro-board" aria-hidden="true">
                                <span>×</span>
                                <span>○</span>
                                <span>×</span>
                                <span>○</span>
                                <span>×</span>
                                <span></span>
                                <span></span>
                                <span>○</span>
                                <span></span>
                            </div>
                        </div>
                        <AuthForm
                            busy={busy || api.hasPending()}
                            submit={auth}
                        />
                    </div>
                ) : (
                    <>
                        <div className="session-bar">
                            <span
                                className={
                                    'connection ' +
                                    (view.connected ? 'online' : '')
                                }
                            >
                                {view.connected
                                    ? 'Соединение установлено'
                                    : 'Восстанавливаем соединение…'}
                            </span>
                            <span>
                                {view.syncing
                                    ? 'Сверяем состояние…'
                                    : 'Всё готово к игре'}
                            </span>
                        </div>
                        {view.error && (
                            <p role="status" className="muted">
                                {view.error}
                            </p>
                        )}
                        {!p ? (
                            <p role="status">Загружаем участие…</p>
                        ) : p.state === 'MENU' ? (
                            <section aria-labelledby="menu-title">
                                <div className="eyebrow">
                                    ГОТОВЫ СДЕЛАТЬ ХОД?
                                </div>
                                <h1 id="menu-title">Выберите соперника</h1>
                                <p className="muted">
                                    Классические правила. Без рейтинга и лишних
                                    настроек.
                                </p>
                                {result && (
                                    <div className="result" role="status">
                                        <strong>{resultLabel}</strong>
                                        <span>{reason[result.reason]}</span>
                                    </div>
                                )}
                                <div className="menu-grid">
                                    <article className="card">
                                        <span className="card-number">
                                            01 / ПОДБОР
                                        </span>
                                        <h2>Новый соперник</h2>
                                        <p>
                                            Найдём первого свободного игрока в
                                            очереди.
                                        </p>
                                        <button
                                            className="primary"
                                            disabled={locked}
                                            onClick={() =>
                                                command(
                                                    '/matchmaking/queue',
                                                    'QueueTicketResponse',
                                                    p.version,
                                                )
                                            }
                                        >
                                            Найти игру{' '}
                                            <span aria-hidden="true">↗</span>
                                        </button>
                                    </article>
                                    <article className="card">
                                        <span className="card-number">
                                            02 / ПРИГЛАШЕНИЕ
                                        </span>
                                        <h2>Игра с другом</h2>
                                        <p>
                                            Создайте лобби и поделитесь кодом.
                                        </p>
                                        <button
                                            disabled={locked}
                                            onClick={() =>
                                                command(
                                                    '/lobbies',
                                                    'LobbyResponse',
                                                    p.version,
                                                )
                                            }
                                        >
                                            Создать лобби
                                        </button>
                                        <form
                                            className="join"
                                            onSubmit={(event) => {
                                                event.preventDefault();
                                                command(
                                                    '/lobbies/join',
                                                    'ParticipationResponse',
                                                    p.version,
                                                    {
                                                        code: code.toUpperCase(),
                                                    },
                                                );
                                            }}
                                        >
                                            <label htmlFor="code">
                                                Есть приглашение?
                                            </label>
                                            <div>
                                                <input
                                                    id="code"
                                                    placeholder="Код из 10 символов"
                                                    maxLength={10}
                                                    pattern="[A-HJ-NP-Z2-9]{10}"
                                                    required
                                                    value={code}
                                                    onChange={(e) =>
                                                        setCode(
                                                            e.target.value.toUpperCase(),
                                                        )
                                                    }
                                                    disabled={locked}
                                                />
                                                <button disabled={locked}>
                                                    Войти по коду
                                                </button>
                                            </div>
                                        </form>
                                    </article>
                                </div>
                            </section>
                        ) : p.state === 'LOBBY' ? (
                            <section className="card center">
                                <div className="eyebrow">ПРИВАТНОЕ ЛОББИ</div>
                                <h1>Ждём вашего друга</h1>
                                <p>Передайте ему код приглашения</p>
                                <output
                                    className="invite"
                                    aria-label="Код приглашения"
                                >
                                    {view.lobby &&
                                    view.lobby.lobbyId === p.resourceId
                                        ? view.lobby.code
                                        : 'Загружаем…'}
                                </output>
                                <p className="hint">
                                    После входа друга у вас будет 10 секунд на
                                    готовность.
                                </p>
                                <button
                                    disabled={locked}
                                    onClick={() =>
                                        command(
                                            '/lobbies/' +
                                                p.resourceId +
                                                '/leave',
                                            'ParticipationResponse',
                                            p.version,
                                        )
                                    }
                                >
                                    Покинуть лобби
                                </button>
                            </section>
                        ) : p.state === 'QUEUED' ? (
                            <section className="card center">
                                <div className="search-mark" aria-hidden="true">
                                    ○
                                </div>
                                <div className="eyebrow">ПОИСК СОПЕРНИКА</div>
                                <h1>Ищем соперника</h1>
                                <p>
                                    Подбор идёт по времени входа. После отмены
                                    подготовки ваше место сохраняется.
                                </p>
                                <button
                                    disabled={locked}
                                    onClick={() =>
                                        command(
                                            '/matchmaking/queue/cancel',
                                            'ParticipationResponse',
                                            p.version,
                                        )
                                    }
                                >
                                    Отменить поиск
                                </button>
                            </section>
                        ) : !m || m.matchId !== p.resourceId ? (
                            <p role="status">Получаем матч…</p>
                        ) : m.state === 'WAITING_READY' ? (
                            <section className="card center">
                                <div className="eyebrow">СОПЕРНИК НАЙДЕН</div>
                                <h1>Начинаем?</h1>
                                <div
                                    className="big-timer"
                                    role="timer"
                                    aria-label="Время на готовность"
                                >
                                    {seconds(m.readyDeadline)}
                                    <small>сек</small>
                                </div>
                                <p>
                                    Готовы:{' '}
                                    {
                                        m.participants.filter(
                                            (player) => player.ready,
                                        ).length
                                    }{' '}
                                    из 2
                                </p>
                                <button
                                    className="primary"
                                    disabled={
                                        locked ||
                                        m.participants.some(
                                            (player) =>
                                                player.userId ===
                                                    session.userId &&
                                                player.ready,
                                        )
                                    }
                                    onClick={() =>
                                        command(
                                            '/matches/' + m.matchId + '/ready',
                                            'MatchResponse',
                                            m.version,
                                        )
                                    }
                                >
                                    {m.participants.some(
                                        (player) =>
                                            player.userId === session.userId &&
                                            player.ready,
                                    )
                                        ? 'Готовность подтверждена'
                                        : 'Я готов'}
                                </button>
                                <p className="hint">
                                    Если соперник не подтвердит, вы вернётесь в
                                    очередь или лобби.
                                </p>
                            </section>
                        ) : m.state === 'COUNTDOWN' ? (
                            <section className="card center">
                                <div className="eyebrow">ОБА ИГРОКА ГОТОВЫ</div>
                                <h1>Игра скоро начнётся</h1>
                                <div
                                    className="big-timer"
                                    role="timer"
                                    aria-label="До начала игры"
                                >
                                    {seconds(m.startDeadline)}
                                    <small>сек</small>
                                </div>
                                <p>Первым ходит X. Символы назначает сервер.</p>
                            </section>
                        ) : m.state === 'RUNNING' &&
                          g?.matchId === m.matchId ? (
                            <section className="play">
                                <div>
                                    <div className="eyebrow">
                                        ПАРТИЯ /{' '}
                                        {g.xPlayerId === session.userId
                                            ? 'ВЫ ИГРАЕТЕ ЗА X'
                                            : 'ВЫ ИГРАЕТЕ ЗА O'}
                                    </div>
                                    <h1>
                                        {g.state === 'PAUSED'
                                            ? 'Партия на паузе'
                                            : g.state === 'FINISHED'
                                              ? resultLabel
                                              : (g.state === 'X_TURN'
                                                      ? g.xPlayerId
                                                      : g.oPlayerId) ===
                                                  session.userId
                                                ? 'Ваш ход'
                                                : 'Ход соперника'}
                                    </h1>
                                    <p className="muted">
                                        {g.state === 'PAUSED'
                                            ? 'Ждём восстановления соединения. Остаток хода сохранён.'
                                            : 'Три символа в ряд — победа.'}
                                    </p>
                                    <Board
                                        game={g}
                                        userId={session.userId}
                                        disabled={locked}
                                        move={(cell) =>
                                            command(
                                                '/games/' +
                                                    g.matchId +
                                                    '/moves',
                                                'GameResponse',
                                                g.version,
                                                { cell },
                                            )
                                        }
                                    />
                                </div>
                                <aside className="card">
                                    <div className="eyebrow">ВРЕМЯ ХОДА</div>
                                    <div
                                        className="turn-timer"
                                        role="timer"
                                        aria-label="Остаток хода"
                                    >
                                        {g.state === 'PAUSED'
                                            ? (
                                                  (g.remainingTurnMs ?? 0) /
                                                  1000
                                              ).toFixed(1)
                                            : seconds(g.turnDeadline)}
                                        <small>сек</small>
                                    </div>
                                    <hr />
                                    <h2>Восстановление</h2>
                                    {(['X', 'O'] as const).map((symbol) => {
                                        const id =
                                            symbol === 'X'
                                                ? g.xPlayerId
                                                : g.oPlayerId;
                                        return (
                                            <p className="budget" key={symbol}>
                                                <span>
                                                    {symbol}{' '}
                                                    {id === session.userId
                                                        ? '· вы'
                                                        : '· соперник'}
                                                </span>
                                                <strong
                                                    aria-label={
                                                        'Запас ' + symbol
                                                    }
                                                >
                                                    {g.recoveryDeadlines?.[id]
                                                        ? seconds(
                                                              g
                                                                  .recoveryDeadlines[
                                                                  id
                                                              ],
                                                          )
                                                        : (
                                                              g
                                                                  .recoveryRemainingMs[
                                                                  symbol
                                                              ] / 1000
                                                          ).toFixed(1)}{' '}
                                                    с
                                                </strong>
                                            </p>
                                        );
                                    })}
                                    <p className="hint">
                                        Общий запас — 30 секунд на игрока за всю
                                        партию.
                                    </p>
                                    <button
                                        className="danger wide"
                                        disabled={
                                            locked || g.state === 'FINISHED'
                                        }
                                        onClick={() =>
                                            command(
                                                '/games/' +
                                                    g.matchId +
                                                    '/surrender',
                                                'GameResponse',
                                                g.version,
                                            )
                                        }
                                    >
                                        Сдаться
                                    </button>
                                </aside>
                            </section>
                        ) : (
                            <p role="status">Сверяем итог матча…</p>
                        )}
                    </>
                )}
            </main>
            <footer>
                <span>ХОД. / КЛАССИЧЕСКИЕ КРЕСТИКИ-НОЛИКИ</span>
                <span>
                    3 × 3 <span aria-hidden="true">·</span> 5 секунд на ход
                </span>
            </footer>
        </div>
    );
}
