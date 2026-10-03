import { beforeEach, describe, expect, it, vi } from 'vitest';

const q = vi.fn(), q1 = vi.fn(), tx = vi.fn();
vi.mock('@/lib/db', () => ({ q: (...a: unknown[]) => q(...a), q1: (...a: unknown[]) => q1(...a), tx: (fn: any) => tx(fn) }));
vi.mock('@/lib/auth', () => ({ getUser: async () => ({ id: 1, username: 'rayka' }) }));

import { formatAddress, formatPickupLine, parsePickup } from '@/lib/pickup';
import { DELETE, PATCH } from '@/app/api/pickup-addresses/[id]/route';
import { POST } from '@/app/api/pickup-addresses/route';

const form = { label: 'Ateliê', cep: '01310-100', street: 'Av. Paulista', number: '1000', complement: 'sala 2', district: 'Bela Vista', city: 'São Paulo', instructions: 'Portão azul' };
const req = (body: unknown) => new Request('http://x/api', { method: 'POST', body: JSON.stringify(body) });
const ctx = (id: string) => ({ params: Promise.resolve({ id }) });

describe('formato do endereço', () => {
  it('endereço e instruções', () => {
    expect(formatAddress(form)).toBe('Av. Paulista, 1000 (sala 2) - Bela Vista, São Paulo');
    expect(formatAddress({ ...form, complement: '' })).toBe('Av. Paulista, 1000 - Bela Vista, São Paulo');
    expect(formatPickupLine(form)).toBe('Av. Paulista, 1000 (sala 2) - Bela Vista, São Paulo · Portão azul');
    expect(formatPickupLine({ ...form, instructions: '' })).toBe('Av. Paulista, 1000 (sala 2) - Bela Vista, São Paulo');
  });
});

describe('formulário do endereço de retirada', () => {
  it('aceita o endereço completo e limpa o CEP', () => {
    const r: any = parsePickup(form);
    expect(r.value).toMatchObject({ label: 'Ateliê', cep: '01310100', street: 'Av. Paulista', number: '1000', city: 'São Paulo', instructions: 'Portão azul' });
  });
  it('sem apelido, usa a rua; CEP é opcional', () => {
    const r: any = parsePickup({ ...form, label: '', cep: '' });
    expect(r.value.label).toBe('Av. Paulista');
    expect(r.value.cep).toBe('');
  });
  it('pede o que falta, com mensagem amigável', () => {
    expect((parsePickup({ ...form, street: '' }) as any).error).toMatch(/rua/i);
    expect((parsePickup({ ...form, number: ' ' }) as any).error).toMatch(/número/i);
    expect((parsePickup({ ...form, district: '' }) as any).error).toMatch(/bairro/i);
    expect((parsePickup({ ...form, city: '' }) as any).error).toMatch(/cidade/i);
    expect((parsePickup({ ...form, cep: '123' }) as any).error).toMatch(/CEP/);
  });
  it('corta textos grandes demais', () => {
    const r: any = parsePickup({ ...form, instructions: 'x'.repeat(500), label: 'y'.repeat(200) });
    expect(r.value.instructions.length).toBe(200);
    expect(r.value.label.length).toBe(40);
  });
});

describe('rotas dos endereços (painel)', () => {
  beforeEach(() => { q.mockReset(); q1.mockReset(); tx.mockReset(); q.mockResolvedValue([]); });

  it('o primeiro endereço vira o de retirada atual', async () => {
    q1.mockResolvedValueOnce({ n: 0 });
    const queries: string[] = [];
    tx.mockImplementation(async (fn: any) => fn({ query: vi.fn(async (sql: string, params?: unknown[]) => { queries.push(sql); return { rows: [{ ...form, id: 5, is_active: params?.[9] }] }; }) }));
    const res = await POST(req(form), ctx('') as any);
    expect(res.status).toBe(201);
    const j = await res.json();
    expect(j.is_active).toBe(true);
    expect(j.address).toBe('Av. Paulista, 1000 (sala 2) - Bela Vista, São Paulo');
  });
  it('o segundo só vira o atual se a confeiteira pedir', async () => {
    q1.mockResolvedValue({ n: 1 });
    tx.mockImplementation(async (fn: any) => fn({ query: vi.fn(async (_s: string, params?: unknown[]) => ({ rows: [{ ...form, id: 6, is_active: params?.[9] }] })) }));
    expect((await (await POST(req(form), ctx('') as any)).json()).is_active).toBe(false);
    expect((await (await POST(req({ ...form, active: true }), ctx('') as any)).json()).is_active).toBe(true);
  });
  it('limite de endereços guardados', async () => {
    q1.mockResolvedValue({ n: 20 });
    expect((await POST(req(form), ctx('') as any)).status).toBe(400);
    expect(tx).not.toHaveBeenCalled();
  });
  it('dados inválidos: 400 sem gravar', async () => {
    expect((await POST(req({ ...form, street: '' }), ctx('') as any)).status).toBe(400);
    expect(tx).not.toHaveBeenCalled();
  });
  it('escolher outro como atual desativa os demais, na mesma transação', async () => {
    q1.mockResolvedValue({ '?column?': 1 });
    const queries: string[] = [];
    tx.mockImplementation(async (fn: any) => fn({ query: vi.fn(async (sql: string) => { queries.push(sql); return { rows: [{ ...form, id: 9, is_active: true }] }; }) }));
    const res = await PATCH(req({ active: true }), ctx('9') as any);
    expect(res.status).toBe(200);
    expect(queries[0]).toMatch(/SET is_active = false/);
    expect(queries[1]).toMatch(/SET is_active = true/);
  });
  it('endereço de outra pessoa: 404', async () => {
    q1.mockResolvedValue(undefined);
    expect((await PATCH(req({ active: true }), ctx('9') as any)).status).toBe(404);
  });
  it('não exclui o endereço atual se houver outros (409); exclui o único', async () => {
    q1.mockResolvedValueOnce({ is_active: true }).mockResolvedValueOnce({ n: 2 });
    expect((await DELETE(req({}), ctx('9') as any)).status).toBe(409);
    expect(q).not.toHaveBeenCalled();
    q1.mockResolvedValueOnce({ is_active: true }).mockResolvedValueOnce({ n: 0 });
    expect((await DELETE(req({}), ctx('9') as any)).status).toBe(200);
    expect(q.mock.calls.some((c) => String(c[0]).startsWith('DELETE FROM pickup_addresses'))).toBe(true);
  });
  it('exclui um endereço que não é o atual', async () => {
    q1.mockResolvedValueOnce({ is_active: false });
    expect((await DELETE(req({}), ctx('3') as any)).status).toBe(200);
  });
});
