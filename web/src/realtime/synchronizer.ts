import { ApiClient, ApiError } from '../api/client';
import type {
    GameResponse,
    LobbyResponse,
    MatchResponse,
    ParticipationResponse,
    QueueTicketResponse,
    WsTicketResponse,
} from '../api/contract';

export type View = {
    participation?: ParticipationResponse['data'];
    lobby?: LobbyResponse['data'];
    ticket?: QueueTicketResponse['data'];
    match?: MatchResponse['data'];
    game?: GameResponse['data'];
    connected: boolean;
    syncing: boolean;
    error?: string | undefined;
};
type DomainEvent = {
    eventId: string;
    aggregateType: string;
    aggregateId: string;
    aggregateVersion: number;
};
type Frame = { type: string; aggregateId?: string; event?: DomainEvent };

// Events are hints. Match/game versions are independent; snapshots are authority.
export class Synchronizer {
    view: View = { connected: false, syncing: true };
    private socket: WebSocket | undefined;
    private running = false;
    private connecting = false;
    private poll: ReturnType<typeof setInterval> | undefined;
    private heartbeat: ReturnType<typeof setInterval> | undefined;
    private retry: ReturnType<typeof setTimeout> | undefined;
    private watchdog: ReturnType<typeof setTimeout> | undefined;
    private work: Promise<void> | undefined;
    private again = false;
    private subscribed: string | undefined;
    private barrier = false;
    private buffer: DomainEvent[] = [];
    private lastMatch: string | undefined;
    constructor(
        readonly api: ApiClient,
        private readonly publish: (view: View) => void,
        private readonly expired: () => void,
    ) {}
    private emit(patch: Partial<View>) {
        this.view = { ...this.view, ...patch };
        this.publish(this.view);
    }
    start() {
        this.running = true;
        this.poll = setInterval(() => {
            void this.refresh();
        }, 5000);
        document.addEventListener('visibilitychange', this.foreground);
        window.addEventListener('online', this.foreground);
        void this.connect();
    }
    stop() {
        this.running = false;
        clearInterval(this.poll);
        clearInterval(this.heartbeat);
        clearTimeout(this.retry);
        clearTimeout(this.watchdog);
        document.removeEventListener('visibilitychange', this.foreground);
        window.removeEventListener('online', this.foreground);
        this.socket?.close();
    }
    private foreground = () => {
        if (document.visibilityState === 'hidden') return;
        if (this.socket?.readyState === WebSocket.OPEN) void this.refresh();
        else if (this.running) {
            clearTimeout(this.retry);
            void this.connect();
        }
    };
    private send(frame: object) {
        if (this.socket?.readyState === WebSocket.OPEN)
            this.socket.send(JSON.stringify(frame));
    }
    private async connect() {
        if (
            !this.running ||
            this.connecting ||
            (this.socket && this.socket.readyState < WebSocket.CLOSING)
        )
            return;
        this.connecting = true;
        try {
            const ticket = await this.api.ticket<WsTicketResponse>();
            if (!this.running) return;
            const url = new URL('/wss', location.origin);
            url.protocol = 'wss:';
            const socket = new WebSocket(url);
            this.socket = socket;
            this.subscribed = undefined;
            this.barrier = false;
            this.buffer = [];
            this.watchdog = setTimeout(() => socket.close(), 6000);
            socket.onopen = () =>
                socket.send(
                    JSON.stringify({
                        type: 'auth',
                        ticket: ticket.data.ticket,
                    }),
                );
            socket.onmessage = (message: MessageEvent<string>) => {
                if (this.socket !== socket || !this.running) return;
                try {
                    this.frame(JSON.parse(message.data) as Frame);
                } catch {
                    socket.close();
                }
            };
            socket.onerror = () => socket.close();
            socket.onclose = () => {
                if (this.socket !== socket) return;
                clearInterval(this.heartbeat);
                clearTimeout(this.watchdog);
                this.emit({ connected: false, syncing: true });
                if (this.running)
                    this.retry = setTimeout(() => {
                        void this.connect();
                    }, 2000);
            };
        } catch (error) {
            this.failure(error);
            if (this.running)
                this.retry = setTimeout(() => {
                    void this.connect();
                }, 2000);
        } finally {
            this.connecting = false;
        }
    }
    private frame(frame: Frame) {
        if (frame.type === 'auth_ok') {
            clearTimeout(this.watchdog);
            this.emit({ connected: true });
            this.heartbeat = setInterval(
                () =>
                    this.send({
                        type: 'heartbeat',
                        requestId: crypto.randomUUID(),
                    }),
                2000,
            );
            this.watchdog = setTimeout(() => this.socket?.close(), 6000);
            // On reconnect, subscribe the known match before taking its snapshots.
            if (this.lastMatch) this.subscribe(this.lastMatch);
            void this.refresh();
        } else if (frame.type === 'heartbeat_ack') {
            clearTimeout(this.watchdog);
            this.watchdog = setTimeout(() => this.socket?.close(), 6000);
        } else if (frame.type === 'resync_required') {
            if (frame.aggregateId === this.subscribed) this.barrier = true;
            void this.refresh();
        } else if (frame.type === 'event' && frame.event) {
            if (this.buffer.length >= 64) {
                this.socket?.close();
                return;
            }
            this.buffer.push(frame.event);
            void this.refresh();
        } else if (frame.type === 'error') this.socket?.close();
    }
    private subscribe(id: string) {
        if (this.subscribed === id || !this.view.connected) return;
        this.subscribed = id;
        this.barrier = false;
        // Observe newly assigned participation before showing ready controls.
        // Frames are ordered: Realtime confirms this lease before subscribe's
        // barrier, closing the assignment-between-heartbeats disconnect window.
        this.send({ type: 'heartbeat', requestId: crypto.randomUUID() });
        this.send({ type: 'subscribe', matchId: id });
    }
    refresh(): Promise<void> {
        if (!this.running) return Promise.resolve();
        if (this.work) {
            this.again = true;
            return this.work;
        }
        this.work = this.reconcile()
            .catch((error: unknown) => this.failure(error))
            .finally(() => {
                this.work = undefined;
                if (this.again && this.running) {
                    this.again = false;
                    void this.refresh();
                }
            });
        return this.work;
    }
    private failure(error: unknown) {
        if (error instanceof ApiError && error.status === 401) {
            this.stop();
            this.expired();
        } else
            this.emit({
                syncing: true,
                error: 'Не удалось сверить состояние. Повторяем автоматически.',
            });
    }
    private async reconcile() {
        const batchSize = this.buffer.length;
        const current = await this.api.get<ParticipationResponse>(
            '/participation',
            'ParticipationResponse',
        );
        if (!this.running) return;
        if (
            !this.view.participation ||
            current.data.version >= this.view.participation.version
        )
            this.emit({ participation: current.data });
        const p = this.view.participation;
        if (!p) return;
        if (p.state === 'IN_MATCH' && p.resourceId)
            this.lastMatch = p.resourceId;
        if (this.lastMatch) {
            this.subscribe(this.lastMatch);
            // Subscribe acknowledgement is the race-free snapshot barrier.
            if (!this.barrier) return;
            const m = await this.api.get<MatchResponse>(
                '/matches/' + this.lastMatch,
                'MatchResponse',
            );
            if (!this.running) return;
            if (
                this.view.match?.matchId !== m.data.matchId ||
                m.data.version >= this.view.match.version
            )
                this.emit({ match: m.data });
            if (m.data.state === 'RUNNING' || m.data.state === 'FINISHED') {
                const g = await this.api.get<GameResponse>(
                    '/games/' + this.lastMatch,
                    'GameResponse',
                );
                if (!this.running) return;
                if (
                    this.view.game?.matchId !== g.data.matchId ||
                    g.data.version >= this.view.game.version
                )
                    this.emit({ game: g.data });
            }
        }
        if (p.state === 'LOBBY' && p.resourceId) {
            const l = await this.api.get<LobbyResponse>(
                '/lobbies/' + p.resourceId,
                'LobbyResponse',
            );
            if (
                this.running &&
                (this.view.lobby?.lobbyId !== l.data.lobbyId ||
                    l.data.version >= this.view.lobby.version)
            )
                this.emit({ lobby: l.data });
        }
        if (p.state === 'QUEUED') {
            const q = await this.api.get<QueueTicketResponse>(
                '/matchmaking/queue',
                'QueueTicketResponse',
            );
            if (this.running) this.emit({ ticket: q.data });
        }
        if (!this.running) return;
        const pending = this.buffer.splice(0, batchSize);
        for (const e of pending)
            this.send({
                type: 'ack',
                eventId: e.eventId,
                aggregateId: e.aggregateId,
                aggregateVersion: e.aggregateVersion,
            });
        this.emit({ syncing: false, error: undefined });
    }
}
