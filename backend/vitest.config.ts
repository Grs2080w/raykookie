import { defineConfig } from 'vitest/config';
// *.sandbox.test.ts fica de fora do `npm test`/CI (chama a API de verdade do Mercado Pago, precisa de
// credenciais de usuário de teste) — roda só sob pedido: `npx vitest run tests/<nome>.sandbox.test.ts`.
export default defineConfig({ resolve: { alias: { '@': __dirname, '@shared': `${__dirname}/../shared` } }, test: { include: ['tests/**/*.test.ts'], exclude: ['**/*.sandbox.test.ts'] } });
