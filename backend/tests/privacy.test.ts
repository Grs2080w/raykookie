import { beforeEach, describe, expect, it, vi } from 'vitest';

const q = vi.fn(), q1 = vi.fn(), tx = vi.fn();
vi.mock('@/lib/db', () => ({ q: (...a: unknown[]) => q(...a), q1: (...a: unknown[]) => q1(...a), tx: (fn: any) => tx(fn) }));
vi.mock('@/lib/shop-queries', () => ({ shopOwnerId: async () => 1 }));
import { hashPassword } from '@/lib/auth';
import { createPrivacyRequest, deleteAccount, unlinkGoogle, updateProfile } from '@/lib/customer-privacy';

beforeEach(() => { q.mockReset(); q1.mockReset(); tx.mockReset(); q.mockResolvedValue([]); });

describe('excluir conta (LGPD)', () => {
  it('exige digitar EXCLUIR', async () => {
    expect(await deleteAccount(5, 'sim', '')).toMatchObject({ ok: false, status: 400 });
    expect(tx).not.toHaveBeenCalled();
  });
  it('conta com senha: senha errada é recusada', async () => {
    q1.mockResolvedValueOnce({ password_hash: hashPassword('certa123') });
    expect(await deleteAccount(5, 'EXCLUIR', 'errada')).toMatchObject({ ok: false, status: 403 });
    expect(tx).not.toHaveBeenCalled();
  });
  it('com pedido em andamento: 409 e nada é apagado', async () => {
    q1.mockResolvedValueOnce({ password_hash: null }).mockResolvedValueOnce({ n: 2 });
    const r = await deleteAccount(5, 'excluir', '');
    expect(r).toMatchObject({ ok: false, status: 409 });
    expect(tx).not.toHaveBeenCalled();
  });
  it('anonimiza pessoa e pedidos, mas não apaga pedidos', async () => {
    q1.mockResolvedValueOnce({ password_hash: hashPassword('certa123') }).mockResolvedValueOnce({ n: 0 });
    const sqls: string[] = [];
    tx.mockImplementation(async (fn: any) => fn({ query: vi.fn(async (sql: string) => { sqls.push(sql); return { rows: [] }; }) }));
    expect(await deleteAccount(5, 'EXCLUIR', 'certa123')).toEqual({ ok: true });
    const all = sqls.join('\n');
    expect(all).toMatch(/UPDATE shop_orders SET contact_name = 'Cliente excluído', phone = ''/);
    expect(all).toMatch(/DELETE FROM customer_sessions/);
    expect(all).toMatch(/email = 'excluido-' \|\| id \|\| '@invalid'/);
    expect(all).not.toMatch(/DELETE FROM (shop_orders|orders|customers)\b/);
  });
});

describe('demais direitos', () => {
  it('correção: valida nome, telefone e CEP', async () => {
    expect(await updateProfile(1, { name: 'A' })).toMatchObject({ error: expect.any(String) });
    expect(await updateProfile(1, { name: 'Ana', phone: '123' })).toMatchObject({ error: expect.stringContaining('Telefone') });
    expect(await updateProfile(1, { name: 'Ana', cep: '123' })).toMatchObject({ error: expect.stringContaining('CEP') });
    expect(q).not.toHaveBeenCalled();
    expect(await updateProfile(1, { name: 'Ana', phone: '(18) 99750-3322', cep: '19000-000' })).toEqual({});
    expect(q.mock.calls[0][1].slice(0, 4)).toEqual([1, 'Ana', '18997503322', '19000000']);
  });
  it('desvincular o Google exige ter senha', async () => {
    q1.mockResolvedValueOnce({ has_google: true, has_password: false });
    expect(await unlinkGoogle(1)).toMatchObject({ error: expect.stringContaining('só entra pelo Google') });
    q1.mockResolvedValueOnce({ has_google: true, has_password: true });
    expect(await unlinkGoogle(1)).toEqual({});
  });
  it('solicitação: tipo válido; "outro" pede texto', async () => {
    expect(await createPrivacyRequest(1, 'xyz', '')).toMatchObject({ error: expect.any(String) });
    expect(await createPrivacyRequest(1, 'outro', 'curto')).toMatchObject({ error: expect.any(String) });
    q1.mockResolvedValueOnce({ id: 9 });
    expect(await createPrivacyRequest(1, 'anonimizacao', 'Quero que apaguem meu endereço')).toEqual({ id: 9 });
  });
});
