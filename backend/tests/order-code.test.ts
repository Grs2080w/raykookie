import { describe, expect, it } from 'vitest';
import { allocateOrderCode, CODE_MAX, CODE_MIN } from '@/lib/order-code';
import { newOrderMessage, paymentMessage } from '@/lib/push';

describe('código do pedido (o que o cliente vê)', () => {
  it('é aleatório, de 4 ou 5 dígitos, e não reaproveita um código em uso', async () => {
    const seen = new Set<number>();
    for (let i = 0; i < 400; i++) {
      const c = await allocateOrderCode(async (n) => seen.has(n));
      expect(Number.isInteger(c)).toBe(true);
      expect(c).toBeGreaterThanOrEqual(CODE_MIN);
      expect(c).toBeLessThanOrEqual(CODE_MAX);
      expect(String(c).length).toBeGreaterThanOrEqual(4);
      expect(String(c).length).toBeLessThanOrEqual(5);
      expect(seen.has(c)).toBe(false);
      seen.add(c);
    }
    expect(seen.size).toBe(400);
  });
  it('não é sequencial: dois pedidos seguidos não têm números vizinhos', async () => {
    const codes: number[] = [];
    for (let i = 0; i < 50; i++) codes.push(await allocateOrderCode(async () => false));
    const consecutive = codes.slice(1).filter((c, i) => Math.abs(c - codes[i]) === 1).length;
    expect(consecutive).toBeLessThan(3);
    expect(new Set(codes).size).toBeGreaterThan(40);
  });
  it('tenta de novo quando o código sorteado já existe, e desiste se tudo estiver ocupado', async () => {
    let calls = 0;
    const c = await allocateOrderCode(async () => ++calls < 4);
    expect(c).toBeGreaterThanOrEqual(CODE_MIN);
    expect(calls).toBe(4);
    await expect(allocateOrderCode(async () => true, 5)).rejects.toThrow();
  });
  it('os avisos para a confeiteira mostram o código, e não o id interno', () => {
    expect(newOrderMessage('t', 'sino', { id: 17, code: 4821, customer: 'Maria', total: 10, items: 1 }).notification.title).toBe('Novo pedido #4821');
    expect(paymentMessage('t', { id: 17, code: 4821, customer: 'Maria', total: 10, items: 1 }).notification.title).toBe('Pix recebido · Pedido #4821');
    expect(newOrderMessage('t', 'sino', { id: 17, code: 4821, customer: 'Maria', total: 10, items: 1 }).data.orderId).toBe('17'); // o id interno segue nos dados
  });
});
