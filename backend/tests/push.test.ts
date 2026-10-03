import { beforeEach, describe, expect, it, vi } from 'vitest';

const q = vi.fn();
vi.mock('@/lib/db', () => ({ q: (...a: unknown[]) => q(...a) }));

import { newOrderMessage, notifyNewOrder, notifyPaid, paymentMessage, setPushSender } from '@/lib/push';
import { isOrderSound, orderChannelId } from '@shared/push';

const order = { id: 7, customer: 'Joana', total: 45.5, items: 3 };

describe('mensagem de novo pedido', () => {
  it('monta título, texto e o canal do som escolhido', () => {
    const m = newOrderMessage('tok', 'caixa', order);
    expect(m.notification).toEqual({ title: 'Novo pedido #7', body: 'Joana · R$ 45,50 · 3 itens' });
    expect(m.android.notification).toEqual({ channelId: 'pedido_caixa', sound: 'pedido_caixa' });
    expect(m.android.priority).toBe('high');
    expect(m.data).toEqual({ type: 'novo_pedido', orderId: '7' });
  });
  it('usa singular para 1 item e cai no som padrão se o som for desconhecido', () => {
    const m = newOrderMessage('tok', 'inexistente', { ...order, items: 1 });
    expect(m.notification.body).toContain('1 item');
    expect(m.android.notification.channelId).toBe('pedido_sino');
  });
  it('valida ids de som', () => {
    expect(isOrderSound('alerta')).toBe(true);
    expect(isOrderSound('../etc')).toBe(false);
    expect(orderChannelId('x')).toBe('pedido_sino');
  });
});

describe('notifyNewOrder', () => {
  beforeEach(() => { q.mockReset(); setPushSender(undefined); });

  it('manda uma mensagem por celular, cada uma com o seu som', async () => {
    q.mockResolvedValueOnce([{ token: 'a', sound: 'sino' }, { token: 'b', sound: 'alerta' }]);
    const send = vi.fn().mockResolvedValue([{ token: 'a', ok: true, dead: false }, { token: 'b', ok: true, dead: false }]);
    setPushSender(send);
    await notifyNewOrder(1, order);
    const sent = send.mock.calls[0][0];
    expect(sent.map((m: any) => [m.token, m.android.notification.channelId])).toEqual([['a', 'pedido_sino'], ['b', 'pedido_alerta']]);
    expect(q).toHaveBeenCalledTimes(1); // nada foi apagado
  });

  it('apaga tokens que o Firebase disse que não existem mais', async () => {
    q.mockResolvedValueOnce([{ token: 'a', sound: 'sino' }, { token: 'b', sound: 'sino' }]).mockResolvedValueOnce([]);
    setPushSender(vi.fn().mockResolvedValue([{ token: 'a', ok: true, dead: false }, { token: 'b', ok: false, dead: true }]));
    await notifyNewOrder(1, order);
    expect(q).toHaveBeenLastCalledWith(expect.stringContaining('DELETE FROM device_tokens'), [['b']]);
  });

  it('não faz nada (nem consulta o banco) com o push desligado', async () => {
    setPushSender(null);
    await notifyNewOrder(1, order);
    expect(q).not.toHaveBeenCalled();
  });

  it('nunca lança, mesmo se o envio falhar', async () => {
    q.mockResolvedValueOnce([{ token: 'a', sound: 'sino' }]);
    setPushSender(vi.fn().mockRejectedValue(new Error('rede')));
    const err = vi.spyOn(console, 'error').mockImplementation(() => {});
    await expect(notifyNewOrder(1, order)).resolves.toBeUndefined();
    err.mockRestore();
  });
});

describe('aviso de pagamento Pix', () => {
  beforeEach(() => { q.mockReset(); setPushSender(undefined); });
  it('monta a mensagem com pedido, cliente e valor, no canal caixa', () => {
    const m = paymentMessage('tok', order);
    expect(m.notification).toEqual({ title: 'Pix recebido · Pedido #7', body: 'Joana pagou R$ 45,50 · 3 itens' });
    expect(m.data).toEqual({ type: 'pagamento', orderId: '7' });
    expect(m.android.notification.channelId).toBe('pedido_caixa');
  });
  it('manda para todos os celulares da dona, independente do som escolhido', async () => {
    q.mockResolvedValueOnce([{ token: 'a', sound: 'sino' }, { token: 'b', sound: 'alerta' }]);
    const send = vi.fn().mockResolvedValue([{ token: 'a', ok: true, dead: false }, { token: 'b', ok: true, dead: false }]);
    setPushSender(send);
    await notifyPaid(1, order);
    expect(send.mock.calls[0][0].map((m: any) => [m.token, m.android.notification.channelId])).toEqual([['a', 'pedido_caixa'], ['b', 'pedido_caixa']]);
  });
});
