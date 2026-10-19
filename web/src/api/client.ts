import specification from '../../../contracts/http/public.openapi.json' with { type: 'json' };
import type { CsrfResponse } from './contract';

type Shape = {
    type?: string;
    $ref?: string;
    enum?: unknown[];
    required?: string[];
    properties?: Record<string, Shape>;
    items?: Shape;
    minimum?: number;
    minItems?: number;
    maxItems?: number;
};
const shape = specification.components.schemas as Record<string, Shape>;

// Check the wire fields this client consumes. This is not the repository's full
// OpenAPI compatibility validator (that remains a separate deferred task).
function matches(value: unknown, schema: Shape): boolean {
    if (schema.$ref)
        return matches(value, shape[schema.$ref.split('/').at(-1) ?? ''] ?? {});
    if (schema.enum && !schema.enum.includes(value)) return false;
    if (schema.type === 'string') return typeof value === 'string';
    if (schema.type === 'boolean') return typeof value === 'boolean';
    if (schema.type === 'integer')
        return (
            typeof value === 'number' &&
            Number.isSafeInteger(value) &&
            value >= (schema.minimum ?? 0)
        );
    if (schema.type === 'array')
        return (
            Array.isArray(value) &&
            value.length >= (schema.minItems ?? 0) &&
            value.length <= (schema.maxItems ?? Infinity) &&
            value.every((v: unknown) => matches(v, schema.items ?? {}))
        );
    if (schema.type === 'object') {
        if (!value || typeof value !== 'object' || Array.isArray(value))
            return false;
        const record = value as Record<string, unknown>;
        return (
            (schema.required ?? []).every((key) => key in record) &&
            Object.entries(schema.properties ?? {}).every(
                ([key, field]) =>
                    !(key in record) || matches(record[key], field),
            )
        );
    }
    return true;
}
export function decode<T>(name: string, value: unknown): T {
    if (!shape[name] || !matches(value, shape[name]))
        throw new ApiError('INVALID_RESPONSE');
    return value as T;
}
export class ApiError extends Error {
    constructor(
        readonly code: string,
        readonly status = 0,
    ) {
        super(code);
    }
}
const message: Record<string, string> = {
    SESSION_ALREADY_ACTIVE:
        'Сессия уже активна. Выйдите на другом устройстве или дождитесь 24 часов бездействия.',
    UNAUTHENTICATED: 'Сессия недействительна. Войдите снова.',
    VERSION_CONFLICT:
        'Состояние изменилось. Оно обновлено — повторите действие.',
    PARTICIPATION_CONFLICT:
        'Участие уже изменилось. Проверяем текущее состояние.',
    DEADLINE_EXCEEDED: 'Серверный срок истёк.',
    INVALID_MOVE: 'Этот ход недопустим.',
    LOBBY_UNAVAILABLE: 'Лобби недоступно. Проверьте код приглашения.',
    VALIDATION_ERROR: 'Проверьте email и пароль.',
    RATE_LIMITED: 'Слишком много попыток. Повторите позже.',
    FORBIDDEN: 'Запрос отклонён. Обновите страницу.',
    INVALID_RESPONSE: 'Сервер вернул неподходящий ответ.',
    NETWORK:
        'Не удалось подтвердить действие. Повторите его с тем же идентификатором.',
    SERVICE_UNAVAILABLE: 'Сервис временно недоступен. Повторите действие.',
};
export function errorMessage(error: unknown): string {
    return error instanceof ApiError
        ? (message[error.code] ?? 'Действие отклонено сервером.')
        : 'Связь временно недоступна.';
}

export class ApiClient {
    offset = 0;
    private lastServerTime = 0;
    private csrf: CsrfResponse['data'] | undefined;
    private pending:
        | {
              path: string;
              body: string | undefined;
              key: string;
              schema: string;
          }
        | undefined;
    constructor(private readonly transport: typeof fetch = fetch) {}
    now() {
        return Date.now() + this.offset;
    }
    private async request<T>(
        path: string,
        schema: string,
        body?: object,
        key?: string,
    ): Promise<T> {
        const headers: Record<string, string> = { Accept: 'application/json' };
        if (key) {
            if (!this.csrf)
                this.csrf = (
                    await this.get<CsrfResponse>('/auth/csrf', 'CsrfResponse')
                ).data;
            headers[this.csrf.headerName] = this.csrf.token;
            headers['Idempotency-Key'] = key;
            if (body !== undefined)
                headers['Content-Type'] = 'application/json';
        }
        let response: Response;
        try {
            const transport = this.transport;
            response = await transport('/api/v1' + path, {
                method: key ? 'POST' : 'GET',
                credentials: 'same-origin',
                cache: 'no-store',
                headers,
                ...(body === undefined ? {} : { body: JSON.stringify(body) }),
                signal: AbortSignal.timeout(8000),
            });
        } catch {
            throw new ApiError('NETWORK');
        }
        let value: unknown;
        try {
            value = await response.json();
        } catch {
            throw new ApiError('INVALID_RESPONSE', response.status);
        }
        if (!response.ok) {
            const code =
                value &&
                typeof value === 'object' &&
                'code' in value &&
                typeof value.code === 'string'
                    ? value.code
                    : 'SERVICE_UNAVAILABLE';
            if (response.status === 403) this.csrf = undefined;
            throw new ApiError(code, response.status);
        }
        const result = decode<T>(schema, value);
        if (
            value &&
            typeof value === 'object' &&
            'serverTime' in value &&
            typeof value.serverTime === 'string'
        ) {
            const time = Date.parse(value.serverTime);
            if (!Number.isFinite(time)) throw new ApiError('INVALID_RESPONSE');
            if (time >= this.lastServerTime) {
                this.offset = time - Date.now();
                this.lastServerTime = time;
            }
        }
        return result;
    }
    get<T>(path: string, schema: string) {
        return this.request<T>(path, schema);
    }
    async command<T>(path: string, schema: string, body?: object): Promise<T> {
        const serialized =
            body === undefined ? undefined : JSON.stringify(body);
        // A timeout is an unknown outcome. Preserve this key for a manual retry;
        // never silently submit changed intent while that outcome is unresolved.
        if (
            this.pending &&
            (this.pending.path !== path || this.pending.body !== serialized)
        )
            throw new ApiError('NETWORK');
        this.pending ??= {
            path,
            body: serialized,
            schema,
            key: crypto.randomUUID(),
        };
        try {
            const result = await this.request<T>(
                path,
                schema,
                body,
                this.pending.key,
            );
            this.pending = undefined;
            return result;
        } catch (error) {
            if (
                error instanceof ApiError &&
                error.status > 0 &&
                error.status < 500
            )
                this.pending = undefined;
            throw error;
        }
    }
    hasPending() {
        return this.pending !== undefined;
    }
    async retry() {
        const p = this.pending;
        if (!p) return;
        await this.command(
            p.path,
            p.schema,
            p.body === undefined ? undefined : (JSON.parse(p.body) as object),
        );
    }
    // A WS ticket belongs to one connection attempt, never to the next one.
    ticket<T>() {
        return this.request<T>(
            '/auth/ws-ticket',
            'WsTicketResponse',
            undefined,
            crypto.randomUUID(),
        );
    }
}
