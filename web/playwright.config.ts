import { defineConfig } from '@playwright/test';
export default defineConfig({
    testDir: './test',
    fullyParallel: false,
    workers: 1,
    timeout: 60000,
    expect: { timeout: 12000 },
    use: { baseURL: 'https://127.0.0.1:3000', headless: true },
    // The fixture is a synthetic browser network boundary backed by real services.
    // No certificate validation is disabled, no product HTTP/WS fallback is enabled.
    webServer: {
        command: 'python3 tool/service.py',
        url: 'http://127.0.0.1:19090/healthz',
        timeout: 120000,
        reuseExistingServer: false,
    },
});
