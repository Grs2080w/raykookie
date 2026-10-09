// Devolução de Pix: motivos, estados e a regra de quem pode devolver o quê. O servidor decide (refundEligibility) e devolve
// o resultado pronto para os apps; esta é a única fonte da regra e dos textos.
export const REFUND_WINDOW_HOURS = 48; // depois da entrega, o cliente ainda pode pedir devolução por este prazo
export const PIX_REFUND_LIMIT_DAYS = 85; // o Pix só pode ser devolvido até 90 dias depois; paramos 5 dias antes
export const MAX_REFUND_PHOTOS = 5;
export const MAX_REFUND_PHOTO_BYTES = 2_000_000;
export const MIN_REFUND_DESCRIPTION = 20;

export type Reason = { id: string; label: string };
export type RefundReason = Reason & { photos: 'sim' | 'opcional' | 'nao'; priority?: boolean; hint?: string };

/** Cancelamento pelo cliente, antes de a produção começar. */
export const CANCEL_REASONS: Reason[] = [
  { id: 'errei_pedido', label: 'Fiz o pedido errado (sabor, item ou quantidade)' },
  { id: 'data_horario', label: 'A data ou o horário não servem mais' },
  { id: 'retirada_entrega', label: 'Não vou conseguir retirar ou receber' },
  { id: 'pagamento', label: 'Tive problema com o pagamento ou o Pix' },
  { id: 'demora_confirmacao', label: 'A confirmação está demorando' },
  { id: 'preco', label: 'Achei caro ou encontrei outra opção' },
  { id: 'nao_preciso', label: 'Não preciso mais (o evento foi cancelado ou desisti)' },
  { id: 'outro', label: 'Outro motivo' },
];

/** Pedido de devolução depois de a produção ter começado. `photos` vale quando o produto já foi recebido. */
export const REFUND_REASONS: RefundReason[] = [
  { id: 'nao_recebi', label: 'Não recebi ou não ficou pronto no horário combinado', photos: 'nao' },
  { id: 'item_errado', label: 'Veio item, sabor ou recheio errado', photos: 'sim' },
  { id: 'faltou_item', label: 'Faltou item ou veio em menor quantidade', photos: 'sim' },
  { id: 'avariado', label: 'Chegou amassado, quebrado ou derretido', photos: 'sim' },
  { id: 'qualidade', label: 'Problema de qualidade (cru, queimado, seco, sabor estranho)', photos: 'sim' },
  { id: 'estragado', label: 'Estragado, com mofo ou com algo estranho dentro', photos: 'sim', priority: true },
  { id: 'alergia', label: 'Tinha ingrediente que não estava informado ou causou reação', photos: 'opcional', priority: true, hint: 'Se houver reação grave, procure atendimento médico.' },
  { id: 'desisti', label: 'Desisti do pedido (já está em produção)', photos: 'nao', hint: 'Como o pedido já foi feito só para você, a devolução nesse caso é improvável.' },
  { id: 'outro', label: 'Outro motivo', photos: 'sim' },
];

/** Cancelamento feito pela loja (o cliente vê o motivo). */
export const STORE_CANCEL_REASONS: Reason[] = [
  { id: 'falta_ingrediente', label: 'Faltou ingrediente' },
  { id: 'sem_agenda', label: 'Não conseguimos produzir a tempo' },
  { id: 'fora_area', label: 'Não entregamos nesse endereço' },
  { id: 'problema_producao', label: 'Tivemos um problema na produção' },
  { id: 'pedido_cliente', label: 'Cancelado a pedido do cliente' },
  { id: 'outro', label: 'Outro motivo' },
];

/** Motivos para negar uma devolução (o cliente vê). */
export const DENY_REASONS: Reason[] = [
  { id: 'fora_prazo', label: 'Fora do prazo' },
  { id: 'sem_comprovacao', label: 'As fotos não mostram o problema' },
  { id: 'produto_conforme', label: 'O produto estava conforme o pedido' },
  { id: 'consumido', label: 'O produto foi consumido em maior parte' },
  { id: 'desistencia_producao', label: 'Desistência com o pedido já produzido' },
  { id: 'outro', label: 'Outro motivo' },
];

/** Encerramentos feitos pelo próprio sistema (ninguém escolhe; o cliente e a loja veem o motivo). */
export const FULL_REFUND_CODE = 'devolucao_total';
export const SYSTEM_REASONS: Reason[] = [
  { id: FULL_REFUND_CODE, label: 'Todo o valor foi devolvido' },
];

export const reasonLabel = (list: Reason[], id: string) => list.find((r) => r.id === id)?.label ?? id;
export const anyReasonLabel = (id: string) => reasonLabel([...CANCEL_REASONS, ...REFUND_REASONS, ...STORE_CANCEL_REASONS, ...DENY_REASONS, ...SYSTEM_REASONS], id);

export type RefundStatus = 'solicitado' | 'desistiu' | 'negado' | 'aprovado' | 'processando' | 'concluido' | 'falhou';
export type RefundKind = 'automatico' | 'solicitado' | 'loja' | 'sistema';
export type FailReason = 'saldo' | 'prazo' | 'erro';

/** O que o cliente lê em cada estado da devolução (falha de saldo nunca aparece para ele: continua "em processamento"). */
export function refundCustomerLabel(status: RefundStatus): string {
  switch (status) {
    case 'solicitado': return 'Em análise';
    case 'aprovado': case 'processando': case 'falhou': return 'Devolução aprovada, em processamento';
    case 'concluido': return 'Devolvido';
    case 'negado': return 'Devolução negada';
    case 'desistiu': return 'Solicitação cancelada';
  }
}

export type RefundMode = 'automatico' | 'solicitacao' | 'nenhum';
export type Eligibility = {
  mode: RefundMode; max_amount: number; photos_required: boolean; deadline: string | null; why_not: string; why_code: string;
};

export type EligibilityInput = {
  paid: boolean; paidAt: Date | null; total: number; refundedTotal: number;
  itemStatuses: string[]; deliveredAt: Date | null; liveRequest: boolean; now: Date;
};

const none = (why_code: string, why_not: string, max = 0): Eligibility => ({ mode: 'nenhum', max_amount: max, photos_required: false, deadline: null, why_not, why_code });

/** Quem pode devolver o quê (regras de negócio fechadas com a confeiteira). */
export function refundEligibility(i: EligibilityInput): Eligibility {
  const left = Math.max(0, Math.round((i.total - i.refundedTotal) * 100) / 100);
  if (!i.paid) return none('nao_pago', 'O pedido ainda não foi pago.');
  if (left <= 0) return none('devolvido', 'Este pedido já foi devolvido.');
  if (i.liveRequest) return none('solicitacao_existente', 'Já existe uma solicitação de devolução para este pedido.');
  if (i.paidAt && i.now.getTime() - i.paidAt.getTime() > PIX_REFUND_LIMIT_DAYS * 864e5) return none('prazo_pix', 'O prazo do Pix para devolução acabou. Fale com a gente.', left);
  const live = i.itemStatuses.filter((s) => s && s !== 'cancelado');
  if (!live.length) return none('cancelado', 'O pedido foi cancelado.', left);
  if (live.every((s) => s === 'novo')) return { mode: 'automatico', max_amount: left, photos_required: false, deadline: null, why_not: '', why_code: '' };
  const delivered = live.every((s) => s === 'entregue');
  if (delivered && i.deliveredAt) {
    const deadline = new Date(i.deliveredAt.getTime() + REFUND_WINDOW_HOURS * 3600_000);
    if (i.now > deadline) return none('fora_prazo', 'O prazo para pedir devolução já acabou.', left);
    return { mode: 'solicitacao', max_amount: left, photos_required: true, deadline: deadline.toISOString(), why_not: '', why_code: '' };
  }
  return { mode: 'solicitacao', max_amount: left, photos_required: delivered, deadline: null, why_not: '', why_code: '' };
}

export type PaymentState = 'pendente' | 'expirado' | 'cancelado' | 'pago' | 'devolucao_parcial' | 'devolvido';
export function paymentState(o: { paid: boolean; cancelled: boolean; pixExpired: boolean; total: number; refundedTotal: number }): PaymentState {
  if (o.paid) {
    if (o.refundedTotal >= o.total && o.total > 0) return 'devolvido';
    if (o.refundedTotal > 0) return 'devolucao_parcial';
    return 'pago';
  }
  if (o.cancelled) return 'cancelado';
  return o.pixExpired ? 'expirado' : 'pendente';
}
