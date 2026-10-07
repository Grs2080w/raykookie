export const ORDER_STATUS = [
  { id: 'novo', label: 'Novo', tone: 'pink' },
  { id: 'producao', label: 'Em produção', tone: 'warn' },
  { id: 'pronto', label: 'Pronto', tone: 'ok' },
  { id: 'entregue', label: 'Entregue', tone: 'neutral' },
  { id: 'cancelado', label: 'Cancelado', tone: 'bad' },
] as const;
export const ORDER_PAYMENT = [
  { id: 'pendente', label: 'Pendente', tone: 'bad' },
  { id: 'sinal', label: 'Sinal pago', tone: 'warn' },
  { id: 'pago', label: 'Pago', tone: 'ok' },
] as const;
