import { describe, expect, it } from 'vitest';
import { cartTotal, formatCep, formatPhone, overallStatus, parseShopOrder, photoKind, safeNext, type MenuItem } from '@shared/shop';

const menu: MenuItem[] = [
  { id: '1', name: 'Cookie tradicional', price: 9, category: 'Cookies e biscoitos' },
  { id: '2', name: 'Caixa com 6 brownies', price: 62, category: 'Brigadeiros e docinhos' },
  { id: '3', name: 'Bolo de chocolate', price: 98, category: 'Bolos' },
];

const today = '2026-10-01';
const base = {
  items: [{ id: '1', qty: 4 }, { id: '2', qty: 1 }],
  contact_name: 'Maria Souza', phone: '(11) 91234-5678', fulfillment: 'retirada',
  scheduled_for: '2026-10-05', time_window: 'tarde', payment_method: 'pix', notes: '',
};

describe('parseShopOrder', () => {
  it('calcula o total pelo cardápio do servidor, ignorando preço vindo do cliente', () => {
    const r = parseShopOrder({ ...base, items: [{ id: '1', qty: 4, unit_price: 0.01 }] }, today, menu);
    expect(r.error).toBeUndefined();
    expect(r.value!.total).toBe(36);
    expect(r.value!.phone).toBe('11912345678');
  });
  it('junta itens repetidos', () => {
    const r = parseShopOrder({ ...base, items: [{ id: '1', qty: 2 }, { id: '1', qty: 3 }] }, today, menu);
    expect(r.value!.items).toEqual([{ id: '1', name: 'Cookie tradicional', qty: 5, unit_price: 9 }]);
  });
  it('recusa item inexistente, quantidade inválida e carrinho vazio', () => {
    expect(parseShopOrder({ ...base, items: [{ id: 'x', qty: 1 }] }, today, menu).error).toMatch(/cardápio/);
    expect(parseShopOrder({ ...base, items: [{ id: '1', qty: 0 }] }, today, menu).error).toMatch(/quantidade/);
    expect(parseShopOrder({ ...base, items: [{ id: '1', qty: 1.5 }] }, today, menu).error).toMatch(/quantidade/);
    expect(parseShopOrder({ ...base, items: [] }, today, menu).error).toMatch(/pelo menos um item/);
  });
  it('exige antecedência mínima e data real', () => {
    expect(parseShopOrder({ ...base, scheduled_for: '2026-10-01' }, today, menu).error).toMatch(/antecedência/);
    expect(parseShopOrder({ ...base, scheduled_for: '2026-10-02' }, today, menu).error).toBeUndefined();
    expect(parseShopOrder({ ...base, scheduled_for: '2026-02-31' }, today, menu).error).toMatch(/data/i);
  });
  it('entrega exige endereço completo; retirada descarta o endereço', () => {
    expect(parseShopOrder({ ...base, fulfillment: 'entrega' }, today, menu).error).toMatch(/CEP/);
    const addr = { cep: '01310-100', street: 'Av. Paulista', number: '1000', district: 'Bela Vista', city: 'São Paulo' };
    expect(parseShopOrder({ ...base, fulfillment: 'entrega', ...addr }, today, menu).value!.cep).toBe('01310100');
    expect(parseShopOrder({ ...base, ...addr }, today, menu).value!.street).toBe('');
  });
  it('valida telefone, pagamento e troco', () => {
    expect(parseShopOrder({ ...base, phone: '123' }, today, menu).error).toMatch(/telefone/);
    expect(parseShopOrder({ ...base, payment_method: 'fiado' }, today, menu).error).toMatch(/pagamento/);
    expect(parseShopOrder({ ...base, payment_method: 'dinheiro', change_for: '50' }, today, menu).error).toMatch(/troco/); // total 98
    expect(parseShopOrder({ ...base, payment_method: 'dinheiro', change_for: '100,00' }, today, menu).value!.change_for).toBe(100);
    expect(parseShopOrder({ ...base, payment_method: 'pix', change_for: '100' }, today, menu).value!.change_for).toBeNull();
  });
  it('limpa caracteres de controle e limita tamanhos', () => {
    const r = parseShopOrder({ ...base, contact_name: 'Ma\u0000ria', notes: 'x'.repeat(500) }, today, menu);
    expect(r.value!.contact_name).toBe('Maria');
    expect(r.value!.notes).toHaveLength(300);
  });
});

describe('auxiliares', () => {
  it('máscaras', () => {
    expect(formatPhone('11912345678')).toBe('(11) 91234-5678');
    expect(formatPhone('1133334444')).toBe('(11) 3333-4444');
    expect(formatPhone('11a9')).toBe('(11) 9');
    expect(formatCep('01310100')).toBe('01310-100');
  });
  it('ilustração padrão pelo nome ou categoria', () => {
    expect(photoKind({ name: 'Cookie de Ninho', category: 'Outros' })).toBe('cookie');
    expect(photoKind({ name: 'Tarte de limão', category: 'Outros' })).toBe('torta');
    expect(photoKind({ name: 'Delícia', category: 'Bolos no pote' })).toBe('bolo');
    expect(photoKind({ name: 'Pão de mel', category: 'Outros' })).toBe('bancada');
  });
  it('total do carrinho', () => { expect(cartTotal([{ id: '1', qty: 2 }, { id: 'nao-existe', qty: 9 }], menu)).toBe(18); });
  it('situação geral usa o item menos adiantado e ignora cancelados', () => {
    expect(overallStatus(['pronto', 'producao'])).toBe('producao');
    expect(overallStatus(['entregue', 'cancelado'])).toBe('entregue');
    expect(overallStatus(['cancelado'])).toBe('cancelado');
    expect(overallStatus([])).toBe('cancelado');
  });
  it('redirecionamento só para dentro da área do cliente', () => {
    expect(safeNext('/cliente/pedidos/novo?item=x')).toBe('/cliente/pedidos/novo?item=x');
    expect(safeNext('https://evil.com')).toBe('/cliente/pedidos');
    expect(safeNext('//evil.com')).toBe('/cliente/pedidos');
    expect(safeNext('/dashboard')).toBe('/cliente/pedidos');
  });
});

describe('weightLabel', () => {
  it('formata gramatura da unidade', async () => {
    const { weightLabel } = await import('../../shared/shop');
    expect(weightLabel(80)).toBe('80 g');
    expect(weightLabel(62.5)).toBe('62,5 g');
    expect(weightLabel(1500)).toBe('1,5 kg');
    expect(weightLabel(0)).toBe('');
    expect(weightLabel(undefined)).toBe('');
  });
});
