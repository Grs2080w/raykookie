import { describe, expect, it } from 'vitest';
import { checkTokenInfo } from '@/lib/google';

const ID = 'cliente-123.apps.googleusercontent.com';
const ok = { aud: ID, iss: 'https://accounts.google.com', exp: '2000000000', email_verified: 'true', sub: '1099', email: 'Joana@Example.com', name: 'Joana Prado' };

describe('checkTokenInfo (login Google pelo app)', () => {
  it('aceita um token válido e normaliza o e-mail', () => {
    const r = checkTokenInfo(ok, ID, 1_900_000_000);
    expect(r.error).toBeUndefined();
    expect(r.claims).toEqual({ sub: '1099', email: 'joana@example.com', name: 'Joana Prado' });
  });
  it('recusa token de outro aplicativo, emissor errado, expirado ou e-mail não confirmado', () => {
    expect(checkTokenInfo({ ...ok, aud: 'outro' }, ID, 1_900_000_000).error).toMatch(/outro aplicativo/);
    expect(checkTokenInfo({ ...ok, iss: 'https://evil.com' }, ID, 1_900_000_000).error).toMatch(/inválida/);
    expect(checkTokenInfo({ ...ok, exp: '1000' }, ID, 1_900_000_000).error).toMatch(/expirou/);
    expect(checkTokenInfo({ ...ok, email_verified: 'false' }, ID, 1_900_000_000).error).toMatch(/confirmou/);
  });
  it('recusa resposta de erro do Google ou vazia', () => {
    expect(checkTokenInfo({ error: 'invalid_token' }, ID).error).toBeDefined();
    expect(checkTokenInfo(null, ID).error).toBeDefined();
  });
});
