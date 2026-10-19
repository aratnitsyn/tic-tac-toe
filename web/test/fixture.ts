import {
    test as base,
    expect,
    type Browser,
    type BrowserContext,
    type Page,
    type WebSocketRoute,
} from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { resolve, extname } from 'node:path';

export type Player = {
    page: Page;
    context: BrowserContext;
    email: string;
    password: string;
    network: {
        offline: boolean;
        dropEvent: boolean;
        close: () => void;
        ack: number;
        lastGame: unknown;
        nextGame: unknown;
        gameReads: number;
    };
};
const origin = 'https://127.0.0.1:3000';
export async function player(browser: Browser): Promise<Player> {
    const context = await browser.newContext({ baseURL: origin });
    const page = await context.newPage();
    const upstream = (await (
        await context.request.get('http://127.0.0.1:19090/healthz')
    ).json()) as { gateway: string; socket: string };
    const network = {
        offline: false,
        dropEvent: false,
        close: () => {},
        ack: 0,
        lastGame: undefined as unknown,
        nextGame: undefined as unknown,
        gameReads: 0,
    };
    await context.route(origin + '/**', async (route) => {
        const request = route.request();
        const url = new URL(request.url());
        if (url.pathname.startsWith('/api/v1/')) {
            if (network.offline) {
                await route.abort();
                return;
            }
            // Synthetic browser boundary; upstream remains the actual Gateway. Cookie
            // jar, Origin, CSRF and HTTP failures are exercised without disabling TLS.
            const headers = await request.allHeaders();
            delete headers.host;
            const response = await context.request.fetch(
                upstream.gateway + url.pathname,
                {
                    method: request.method(),
                    headers,
                    ...(request.postData() === null
                        ? {}
                        : { data: request.postData() ?? '' }),
                },
            );
            if (
                request.method() === 'GET' &&
                url.pathname.startsWith('/api/v1/games/')
            ) {
                if (network.nextGame !== undefined) {
                    const old = network.nextGame;
                    network.nextGame = undefined;
                    await route.fulfill({
                        status: 200,
                        contentType: 'application/json',
                        body: JSON.stringify(old),
                    });
                } else {
                    network.lastGame = (await response.json()) as unknown;
                    await route.fulfill({ response });
                }
                network.gameReads++;
            } else await route.fulfill({ response });
            return;
        }
        const name = url.pathname.startsWith('/assets/')
            ? '.' + url.pathname
            : './index.html';
        const body = await readFile(resolve('dist', name));
        const contentType =
            (
                {
                    '.html': 'text/html',
                    '.js': 'text/javascript',
                    '.css': 'text/css',
                } as Record<string, string>
            )[extname(name)] ?? 'application/octet-stream';
        await route.fulfill({ status: 200, contentType, body });
    });
    await context.routeWebSocket(
        origin.replace('https:', 'wss:') + '/wss',
        (route: WebSocketRoute) => {
            if (network.offline) {
                void route.close();
                return;
            }
            const socket = new WebSocket(upstream.socket);
            const pending: Array<string | Buffer> = [];
            network.close = () => {
                socket.close();
                void route.close();
            };
            route.onMessage((message) => {
                if (
                    typeof message === 'string' &&
                    message.includes('"type":"ack"')
                )
                    network.ack++;
                if (socket.readyState === WebSocket.OPEN) socket.send(message);
                else pending.push(message);
            });
            socket.onopen = () => {
                for (const message of pending) socket.send(message);
                pending.length = 0;
            };
            socket.onmessage = (event: MessageEvent<string>) => {
                if (
                    !network.dropEvent ||
                    !event.data.includes('"type":"event"')
                )
                    route.send(event.data);
            };
            socket.onerror = () => {
                void route.close();
            };
            socket.onclose = () => {
                void route.close();
            };
            route.onClose(() => socket.close());
        },
    );
    return {
        page,
        context,
        email: 'web-' + crypto.randomUUID() + '@example.com',
        password: 'Qx8!' + crypto.randomUUID().slice(0, 8),
        network,
    };
}
export async function login(p: Player, register = true) {
    await p.page.goto('/');
    await expect(
        p.page.getByRole('heading', { name: 'Рады видеть вас' }),
    ).toBeVisible();
    if (register)
        await p.page
            .getByRole('button', { name: 'Регистрация', exact: true })
            .click();
    await p.page.getByLabel('Email', { exact: true }).fill(p.email);
    await p.page.getByLabel('Пароль', { exact: true }).fill(p.password);
    if (register) {
        await p.page
            .getByRole('button', { name: 'Создать аккаунт', exact: true })
            .click();
        await expect(p.page.getByRole('status')).toContainText(
            'Аккаунт создан',
        );
        await p.page.getByRole('button', { name: 'Вход', exact: true }).click();
    }
    await p.page.getByRole('button', { name: 'Войти', exact: true }).click();
}
export async function menu(p: Player) {
    await expect(
        p.page.getByRole('heading', { name: 'Выберите соперника' }),
    ).toBeVisible();
    await expect(
        p.page.getByRole('button', { name: 'Найти игру' }),
    ).toBeEnabled();
}
export async function lobbyPair(a: Player, b: Player) {
    await menu(a);
    await menu(b);
    await a.page
        .getByRole('button', { name: 'Создать лобби', exact: true })
        .click();
    const code = a.page.getByLabel('Код приглашения');
    await expect(code).toHaveText(/^[A-HJ-NP-Z2-9]{10}$/);
    await b.page.getByLabel('Есть приглашение?').fill(await code.innerText());
    await b.page.getByRole('button', { name: 'Войти по коду' }).click();
    await expect(
        a.page.getByRole('heading', { name: 'Начинаем?' }),
    ).toBeVisible();
    await expect(
        b.page.getByRole('heading', { name: 'Начинаем?' }),
    ).toBeVisible();
}
export async function ready(a: Player, b: Player) {
    await a.page.getByRole('button', { name: 'Я готов', exact: true }).click();
    await expect(
        b.page.getByText('Готовы: 1 из 2', { exact: true }),
    ).toBeVisible();
    await b.page.getByRole('button', { name: 'Я готов', exact: true }).click();
    await expect(
        a.page.getByRole('heading', { name: 'Игра скоро начнётся' }),
    ).toBeVisible();
    await expect(
        b.page.getByRole('heading', { name: 'Игра скоро начнётся' }),
    ).toBeVisible();
    await expect(
        a.page.getByRole('group', { name: /Игровое поле/ }),
    ).toBeVisible();
    await expect(
        b.page.getByRole('group', { name: /Игровое поле/ }),
    ).toBeVisible();
}
export const test = base.extend<{ pair: [Player, Player] }>({
    pair: async ({ browser }, use) => {
        const a = await player(browser);
        const b = await player(browser);
        try {
            await login(a);
            await login(b);
            await menu(a);
            await menu(b);
            await use([a, b]);
        } finally {
            await closePlayer(a);
            await closePlayer(b);
        }
    },
});
export { expect };

export async function closePlayer(p: Player) {
    await p.page.goto('about:blank');
    await p.context.unrouteAll({ behavior: 'wait' });
    await p.context.close();
}
