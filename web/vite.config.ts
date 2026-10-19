import { defineConfig } from 'vite';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// Only the local development proxy terminates TLS here. Production uses Istio.
export default defineConfig(({ command }) => {
    if (command === 'build') return {};
    const local = Object.fromEntries(
        readFileSync('local.env', 'utf8')
            .split('\n')
            .filter((line) => line && !line.startsWith('#'))
            .map((line) => {
                const at = line.indexOf('=');
                return [line.slice(0, at), line.slice(at + 1)];
            }),
    );
    const env = (key: string) => {
        const value = process.env[key] ?? local[key];
        if (!value) throw new Error(`Missing ${key}`);
        return value;
    };
    const server = {
        host: '127.0.0.1',
        port: Number(env('WEB_PORT')),
        strictPort: true,
        https: {
            cert: readFileSync(resolve(env('WEB_TLS_CERT'))),
            key: readFileSync(resolve(env('WEB_TLS_KEY'))),
        },
        proxy: {
            '/api/v1/': { target: env('GATEWAY_URL'), changeOrigin: false },
            '/wss': {
                target: env('REALTIME_URL'),
                ws: true,
                changeOrigin: false,
            },
        },
    };
    return { server, preview: server };
});
