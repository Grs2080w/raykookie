import { isoDate, num, str } from './api';

export const STATUS = ['novo', 'producao', 'pronto', 'entregue', 'cancelado'];
export const PAYMENT = ['pendente', 'sinal', 'pago'];
const date = isoDate;

export function parseOrder(b: any) {
  const customer = str(b.customer), product = str(b.product);
  if (!customer) return { error: 'Informe o nome do cliente.' };
  if (!product) return { error: 'Informe o produto.' };
  const qty = num(b.qty, 1);
  if (qty <= 0) return { error: 'A quantidade precisa ser maior que zero.' };
  if ((str(b.order_date) && !date(b.order_date)) || (str(b.delivery_date) && !date(b.delivery_date))) return { error: 'Data inválida.' };
  return {
    value: [
      date(b.order_date) ?? new Date().toISOString().slice(0, 10), customer.slice(0, 80),
      b.recipe_id ? Number(b.recipe_id) : null, product.slice(0, 100), qty, Math.max(0, num(b.unit_price)),
      date(b.delivery_date), STATUS.includes(str(b.status)) ? str(b.status) : 'novo',
      PAYMENT.includes(str(b.payment)) ? str(b.payment) : 'pendente', str(b.notes).slice(0, 300),
    ],
  };
}

