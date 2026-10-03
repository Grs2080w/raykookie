import { describe, expect, it } from 'vitest';
import { paymentState, refundCustomerLabel, refundEligibility, type EligibilityInput } from '@shared/refund';

const now = new Date('2026-10-10T12:00:00Z');
const base: EligibilityInput = { paid: true, paidAt: new Date('2026-10-09T12:00:00Z'), total: 100, refundedTotal: 0, itemStatuses: ['novo', 'novo'], deliveredAt: null, liveRequest: false, now };
const el = (o: Partial<EligibilityInput> = {}) => refundEligibility({ ...base, ...o });
const hoursAgo = (h: number) => new Date(now.getTime() - h * 3600_000);

describe('quem pode devolver o quê', () => {
  it('não pago: nada a devolver', () => expect(el({ paid: false, paidAt: null })).toMatchObject({ mode: 'nenhum', why_code: 'nao_pago' }));
  it('pago e nada iniciado: devolução automática do total', () => expect(el()).toMatchObject({ mode: 'automatico', max_amount: 100, photos_required: false }));
  it('itens cancelados não contam como "iniciado"', () => expect(el({ itemStatuses: ['novo', 'cancelado'] }).mode).toBe('automatico'));
  it('em produção ou pronto: solicitação, fotos opcionais', () => {
    for (const s of ['producao', 'pronto']) expect(el({ itemStatuses: ['novo', s] })).toMatchObject({ mode: 'solicitacao', photos_required: false, deadline: null });
  });
  it('entregue há menos de 48h: solicitação com fotos obrigatórias e prazo', () => {
    const e = el({ itemStatuses: ['entregue', 'entregue'], deliveredAt: hoursAgo(47) });
    expect(e).toMatchObject({ mode: 'solicitacao', photos_required: true });
    expect(new Date(e.deadline!).getTime()).toBe(hoursAgo(47).getTime() + 48 * 3600_000);
  });
  it('entregue há mais de 48h: fora do prazo', () => expect(el({ itemStatuses: ['entregue'], deliveredAt: hoursAgo(49) })).toMatchObject({ mode: 'nenhum', why_code: 'fora_prazo' }));
  it('já existe solicitação: não abre outra', () => expect(el({ itemStatuses: ['producao'], liveRequest: true })).toMatchObject({ mode: 'nenhum', why_code: 'solicitacao_existente' }));
  it('já devolvido por inteiro', () => expect(el({ refundedTotal: 100 })).toMatchObject({ mode: 'nenhum', why_code: 'devolvido' }));
  it('devolução parcial já feita reduz o máximo', () => expect(el({ itemStatuses: ['producao'], refundedTotal: 30 })).toMatchObject({ mode: 'solicitacao', max_amount: 70 }));
  it('Pix pago há mais de 85 dias: fale com a gente', () => expect(el({ paidAt: new Date('2026-07-01T00:00:00Z') })).toMatchObject({ mode: 'nenhum', why_code: 'prazo_pix' }));
  it('pedido todo cancelado: nada a pedir', () => expect(el({ itemStatuses: ['cancelado'] })).toMatchObject({ mode: 'nenhum', why_code: 'cancelado' }));
  it('entregue sem data de entrega registrada não bloqueia', () => expect(el({ itemStatuses: ['entregue'], deliveredAt: null }).mode).toBe('solicitacao'));
});

describe('estado do pagamento', () => {
  const st = (o: Partial<Parameters<typeof paymentState>[0]>) => paymentState({ paid: false, cancelled: false, pixExpired: false, total: 100, refundedTotal: 0, ...o });
  it('segue o ciclo do documento', () => {
    expect(st({})).toBe('pendente');
    expect(st({ pixExpired: true })).toBe('expirado');
    expect(st({ cancelled: true })).toBe('cancelado');
    expect(st({ paid: true })).toBe('pago');
    expect(st({ paid: true, refundedTotal: 40 })).toBe('devolucao_parcial');
    expect(st({ paid: true, refundedTotal: 100 })).toBe('devolvido');
  });
  it('o cliente nunca lê "falhou" nem "saldo": continua em processamento', () => {
    expect(refundCustomerLabel('falhou')).toBe(refundCustomerLabel('processando'));
  });
});
