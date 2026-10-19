import {
    test,
    expect,
    player,
    login,
    menu,
    lobbyPair,
    ready,
    closePlayer,
} from './fixture';

test('password policy, secure cookie, CSRF and second-client session rejection', async ({
    browser,
}) => {
    const a = await player(browser);
    const b = await player(browser);
    try {
        await a.page.goto('/');
        await a.page.screenshot({
            path: test.info().outputPath('auth.png'),
            fullPage: true,
        });
        await a.page
            .getByRole('button', { name: 'Регистрация', exact: true })
            .click();
        await a.page.getByLabel('Email', { exact: true }).fill(a.email);
        for (const password of [
            'abc',
            'abcdef1!',
            'ABCDEF1!',
            'Abcdef!!',
            'Abcdef12',
            'Abc1!é',
            'Abc123456789012!x',
        ]) {
            await a.page.getByLabel('Пароль', { exact: true }).fill(password);
            await a.page
                .getByRole('button', { name: 'Создать аккаунт' })
                .click();
            await expect(a.page.getByRole('alert')).toBeVisible();
            await expect(
                a.page.getByRole('heading', { name: 'Начнём знакомство' }),
            ).toBeVisible();
        }
        await login(a);
        await menu(a);
        const cookie = (await a.context.cookies()).find(
            (c) => c.name === 'SESSION',
        );
        expect(cookie).toMatchObject({
            secure: true,
            httpOnly: true,
            sameSite: 'Strict',
            path: '/api/v1',
        });
        expect(await a.page.evaluate(() => document.cookie)).not.toContain(
            'SESSION',
        );
        // An actual request through the browser, missing CSRF, must be rejected.
        expect(
            await a.page.evaluate(
                async () =>
                    (
                        await fetch('/api/v1/matchmaking/queue', {
                            method: 'POST',
                            headers: {
                                'Content-Type': 'application/json',
                                'Idempotency-Key': crypto.randomUUID(),
                            },
                            body: JSON.stringify({ expectedVersion: 0 }),
                        })
                    ).status,
            ),
        ).toBe(403);
        b.email = a.email;
        b.password = a.password;
        await login(b, false);
        await expect(b.page.getByRole('alert')).toContainText(
            'Сессия уже активна',
        );
        await a.page.reload();
        await menu(a); // restores cookie, no new login
        await a.page
            .getByRole('button', { name: 'Выйти', exact: true })
            .click();
        await expect(
            a.page.getByRole('heading', { name: 'Рады видеть вас' }),
        ).toBeVisible();
        await expect(async () => {
            const button = b.page.getByRole('button', {
                name: 'Войти',
                exact: true,
            });
            if (await button.isVisible()) await button.click();
            await expect(
                b.page.getByRole('heading', { name: 'Выберите соперника' }),
            ).toBeVisible({ timeout: 500 });
        }).toPass({ intervals: [500], timeout: 12000 });
        await menu(b);
    } finally {
        await closePlayer(a);
        await closePlayer(b);
    }
});

test('waiting lobby exit and queue cancellation return to menu', async ({
    pair: [a],
}) => {
    await a.page.screenshot({
        path: test.info().outputPath('menu.png'),
        fullPage: true,
    });
    await a.page
        .getByRole('button', { name: 'Создать лобби', exact: true })
        .click();
    await expect(a.page.getByLabel('Код приглашения')).toHaveText(
        /^[A-HJ-NP-Z2-9]{10}$/,
    );
    await a.page.getByRole('button', { name: 'Покинуть лобби' }).click();
    await menu(a);
    await a.page.getByRole('button', { name: 'Найти игру' }).click();
    await expect(
        a.page.getByRole('heading', { name: 'Ищем соперника' }),
    ).toBeVisible();
    await a.page.getByRole('button', { name: 'Отменить поиск' }).click();
    await menu(a);
});

test('private readiness timeout returns accepted player to rotated lobby and other to menu', async ({
    pair: [a, b],
}) => {
    await lobbyPair(a, b);
    await a.page.getByRole('button', { name: 'Я готов', exact: true }).click();
    await expect(
        a.page.getByRole('heading', { name: 'Ждём вашего друга' }),
    ).toBeVisible({ timeout: 16000 });
    await menu(b);
    await expect(a.page.getByLabel('Код приглашения')).toHaveText(
        /^[A-HJ-NP-Z2-9]{10}$/,
    );
    await a.page.getByRole('button', { name: 'Покинуть лобби' }).click();
    await menu(a);
});

test('FIFO readiness timeout preserves search for accepted player', async ({
    pair: [a, b],
}) => {
    await a.page.getByRole('button', { name: 'Найти игру' }).click();
    await b.page.getByRole('button', { name: 'Найти игру' }).click();
    await expect(
        a.page.getByRole('heading', { name: 'Начинаем?' }),
    ).toBeVisible();
    await a.page.getByRole('button', { name: 'Я готов', exact: true }).click();
    await expect(
        a.page.getByRole('heading', { name: 'Ищем соперника' }),
    ).toBeVisible({ timeout: 16000 });
    await menu(b);
    await a.page.getByRole('button', { name: 'Отменить поиск' }).click();
    await menu(a);
});

test('private game keyboard move and surrender; dropped terminal event repaired by polling', async ({
    pair: [a, b],
}) => {
    await lobbyPair(a, b);
    await ready(a, b);
    const first = (await a.page
        .getByRole('heading', { name: 'Ваш ход', exact: true })
        .isVisible())
        ? a
        : b;
    const other = first === a ? b : a;
    const oldSnapshot = first.network.lastGame;
    expect(oldSnapshot).toBeDefined();
    const cell = first.page.getByRole('button', {
        name: 'Ряд 1, столбец 1: пусто',
        exact: true,
    });
    await cell.focus();
    await first.page.keyboard.press('ArrowRight');
    await expect(
        first.page.getByRole('button', {
            name: 'Ряд 1, столбец 2: пусто',
            exact: true,
        }),
    ).toBeFocused();
    await first.page.keyboard.press('Enter');
    await expect(
        other.page.getByRole('button', {
            name: 'Ряд 1, столбец 2: X',
            exact: true,
        }),
    ).toBeVisible();
    await expect(
        first.page.getByRole('button', {
            name: 'Ряд 1, столбец 2: X',
            exact: true,
        }),
    ).toBeVisible();
    const read = first.network.gameReads;
    first.network.nextGame = oldSnapshot;
    await first.page.evaluate(() =>
        document.dispatchEvent(new Event('visibilitychange')),
    );
    await expect.poll(() => first.network.gameReads).toBeGreaterThan(read);
    await first.page.evaluate(
        () =>
            new Promise((resolve) =>
                requestAnimationFrame(() => requestAnimationFrame(resolve)),
            ),
    );
    await expect(
        first.page.getByRole('button', {
            name: 'Ряд 1, столбец 2: X',
            exact: true,
        }),
    ).toBeVisible();
    first.network.dropEvent = true;
    await other.page
        .getByRole('button', { name: 'Сдаться', exact: true })
        .click();
    await menu(other);
    await menu(first);
    await expect(first.page.getByRole('status')).toContainText('Вы победили');
    await expect(other.page.getByRole('status')).toContainText('Вы проиграли');
    expect(first.network.ack + other.network.ack).toBeGreaterThan(0);
});

test('FIFO countdown, game and turn deadline return both players to menu', async ({
    pair: [a, b],
}) => {
    await a.page.getByRole('button', { name: 'Найти игру' }).click();
    await b.page.getByRole('button', { name: 'Найти игру' }).click();
    await expect(
        a.page.getByRole('heading', { name: 'Начинаем?' }),
    ).toBeVisible();
    await ready(a, b);
    await menu(a);
    await menu(b);
    await expect(a.page.getByRole('status')).toContainText(
        'Время хода истекло',
    );
});

test('disconnect pauses game; reconnect and foreground preserve remaining turn', async ({
    pair: [a, b],
}) => {
    await lobbyPair(a, b);
    await ready(a, b);
    await expect
        .poll(async () =>
            Number(
                (await b.page.getByLabel('Остаток хода').innerText())
                    .replace('сек', '')
                    .trim(),
            ),
        )
        .toBeLessThan(4);
    a.network.offline = true;
    a.network.close();
    await expect(
        b.page.getByRole('heading', { name: 'Партия на паузе' }),
    ).toBeVisible();
    const remaining = Number(
        await b.page
            .getByLabel('Остаток хода')
            .innerText()
            .then((text) => text.replace('сек', '').trim()),
    );
    expect(remaining).toBeGreaterThan(0);
    expect(remaining).toBeLessThanOrEqual(5);
    a.network.offline = false;
    await a.page.evaluate(() => {
        window.dispatchEvent(new Event('online'));
        document.dispatchEvent(new Event('visibilitychange'));
    });
    await expect(
        b.page.getByRole('heading', { name: 'Партия на паузе' }),
    ).not.toBeVisible();
    await expect(
        a.page.getByRole('group', { name: /Игровое поле/ }),
    ).toBeVisible();
    const resumed = Number(
        await b.page
            .getByLabel('Остаток хода')
            .innerText()
            .then((text) => text.replace('сек', '').trim()),
    );
    expect(resumed).toBeLessThanOrEqual(remaining + 0.1);
    await a.page.getByRole('button', { name: 'Сдаться' }).click();
    await menu(a);
    await menu(b);
});

test('disconnect in countdown cancels without defeat and returns connected player to lobby', async ({
    pair: [a, b],
}) => {
    await lobbyPair(a, b);
    await a.page.getByRole('button', { name: 'Я готов', exact: true }).click();
    await expect(
        b.page.getByText('Готовы: 1 из 2', { exact: true }),
    ).toBeVisible();
    await b.page.getByRole('button', { name: 'Я готов', exact: true }).click();
    await expect(
        a.page.getByRole('heading', { name: 'Игра скоро начнётся' }),
    ).toBeVisible();
    b.network.offline = true;
    b.network.close();
    await expect(
        a.page.getByRole('heading', { name: 'Ждём вашего друга' }),
    ).toBeVisible();
    b.network.offline = false;
    await b.page.evaluate(() => window.dispatchEvent(new Event('online')));
    await menu(b);
    await expect(b.page.getByRole('status')).not.toBeVisible();
    await a.page.getByRole('button', { name: 'Покинуть лобби' }).click();
    await menu(a);
});

test('unverified session after a network failure is retried before showing login', async ({
    browser,
}) => {
    const p = await player(browser);
    try {
        p.network.offline = true;
        await p.page.goto('/');
        await expect(
            p.page.getByRole('button', { name: 'Повторить проверку сессии' }),
        ).toBeVisible();
        await expect(
            p.page.getByRole('heading', { name: 'Рады видеть вас' }),
        ).not.toBeVisible();
        p.network.offline = false;
        await p.page
            .getByRole('button', { name: 'Повторить проверку сессии' })
            .click();
        await expect(
            p.page.getByRole('heading', { name: 'Рады видеть вас' }),
        ).toBeVisible();
        await p.page.setViewportSize({ width: 390, height: 844 });
        expect(
            await p.page.evaluate(
                () => document.documentElement.scrollWidth <= window.innerWidth,
            ),
        ).toBe(true);
        await p.page.screenshot({
            path: test.info().outputPath('mobile-auth.png'),
            fullPage: true,
        });
    } finally {
        await closePlayer(p);
    }
});
