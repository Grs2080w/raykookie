import { defineConfig } from 'vitest/config';
export default defineConfig({ resolve: { alias: { '@': __dirname, '@shared': `${__dirname}/../shared` } }, test: { include: ['tests/**/*.test.ts'] } });
