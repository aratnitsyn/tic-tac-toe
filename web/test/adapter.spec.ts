import { test, expect } from '@playwright/test';
import { ApiClient, ApiError, decode } from '../src/api/client';
import { passwordIssue } from '../src/auth/password';

test('password boundaries and character categories agree with contract', () => {
    for (const value of ['Abc1!x', 'Aa1!' + 'x'.repeat(11)])
        expect(passwordIssue(value)).toEqual([]);
    for (const value of [
        'Ab1!x',
        'Aa1!' + 'x'.repeat(12),
        'abc1!x',
        'ABC1!X',
        'Abcd!x',
        'Abcd1x',
        'Abc1! ',
        'Abc1!я',
        'Abc1!\\',
    ])
        expect(passwordIssue(value).length).toBeGreaterThan(0);
});

test('unknown HTTP outcome replays original command and rejects different intent', async () => {
    const keys: string[] = [];
    const bodies: Array<string | undefined> = [];
    let attempt = 0;
    const transport: typeof fetch = async (input, init) => {
        if (String(input).endsWith('/auth/csrf'))
            return Response.json({
                serverTime: new Date().toISOString(),
                version: 1,
                data: { headerName: 'X-CSRF-TOKEN', token: 'test-csrf' },
            });
        const header = init?.headers as Record<string, string>;
        expect(init?.credentials).toBe('same-origin');
        expect(header['X-CSRF-TOKEN']).toBe('test-csrf');
        expect(header.Authorization).toBeUndefined();
        keys.push(header['Idempotency-Key'] ?? '');
        bodies.push(init?.body as string | undefined);
        if (attempt++ === 0) throw new TypeError('lost response');
        return Response.json({
            serverTime: new Date().toISOString(),
            version: 2,
            data: { userId: 'u', version: 2, state: 'QUEUED' },
        });
    };
    const api = new ApiClient(transport);
    await expect(
        api.command('/test', 'ParticipationResponse', { expectedVersion: 1 }),
    ).rejects.toMatchObject({ code: 'NETWORK' });
    await expect(
        api.command('/other', 'ParticipationResponse', {}),
    ).rejects.toBeInstanceOf(ApiError);
    await api.retry();
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
    expect(bodies[0]).toBe(bodies[1]);
    expect(api.hasPending()).toBe(false);
});

test('malformed aggregate response is rejected before rendering', () => {
    expect(() =>
        decode('ParticipationResponse', {
            serverTime: new Date().toISOString(),
            version: 1,
            data: { state: 'OTHER', userId: 'x', version: 1 },
        }),
    ).toThrow(ApiError);
    expect(() =>
        decode('GameResponse', {
            serverTime: new Date().toISOString(),
            version: 1,
            data: { board: ['X'] },
        }),
    ).toThrow(ApiError);
});

test('late HTTP response cannot move the server clock back', async () => {
    const now = Date.now();
    const sample = [now + 1000, now - 1000];
    const transport: typeof fetch = async () =>
        Response.json({
            serverTime: new Date(sample.shift() ?? now).toISOString(),
            version: 1,
            data: { userId: 'u', version: 1, state: 'MENU' },
        });
    const api = new ApiClient(transport);
    await api.get('/participation', 'ParticipationResponse');
    const offset = api.offset;
    await api.get('/participation', 'ParticipationResponse');
    expect(api.offset).toBe(offset);
});
