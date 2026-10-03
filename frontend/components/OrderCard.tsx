import { CalendarDays, CreditCard, MapPin } from 'lucide-react';
import { Badge } from '@/components/ui/Badge';
import { Card } from '@/components/ui/form';
import type { ShopOrder } from '@shared/types';
import { dateBR, money } from '@/lib/format';
import { OrderTimeline } from '@/components/OrderTimeline';
import { CancelOrder } from '@/components/CancelOrder';
import { PixCopy } from '@/components/PixCopy';
import { CancelInfo, RefundBox, refundBadge } from '@/components/RefundBox';
import { CUSTOMER_STATUS, paymentLabel, TIME_WINDOWS } from '@shared/shop';

/** Endereços das ações do cartão: pela conta do cliente ou pelo link de pagamento (token). */
export type OrderLinks = { cancelUrl: string; refundApi: string; refundHref: string; backHref: string };

export function orderLinks(o: ShopOrder, token?: string): OrderLinks {
  if (token) {
    const t = encodeURIComponent(token);
    return { cancelUrl: `/api/pagar/${t}/cancelar`, refundApi: `/api/pagar/${t}/devolucao`, refundHref: `/pagar/${t}/devolucao`, backHref: `/pagar/${t}` };
  }
  return { cancelUrl: `/api/cliente/pedidos/${o.id}/cancelar`, refundApi: `/api/cliente/pedidos/${o.id}/devolucao`, refundHref: `/cliente/pedidos/${o.id}/devolucao`, backHref: '/cliente/pedidos' };
}

function PayBadge({ o }: { o: ShopOrder }) {
  switch (o.payment_state) {
    case 'pago': return <Badge tone="ok">Pago</Badge>;
    case 'devolucao_parcial': return <Badge tone="warn">Devolvido {money(o.refunded_total)}</Badge>;
    case 'devolvido': return <Badge tone="neutral">Devolvido</Badge>;
    case 'cancelado': return <Badge tone="neutral">Cancelado</Badge>;
    case 'expirado': return <Badge tone="bad">Pix vencido</Badge>;
    default: return <Badge tone="warn">Aguardando pagamento</Badge>;
  }
}

/** Cartão do pedido do cliente (usado em /cliente/pedidos e na página do link de pagamento). */
export function OrderCard({ o, links }: { o: ShopOrder; links: OrderLinks }) {
  const st = CUSTOMER_STATUS[o.status] ?? CUSTOMER_STATUS.novo;
  const pay = paymentLabel(o.payment_method);
  const win = TIME_WINDOWS.find((w) => w.id === o.time_window)?.label;
  return (
    <Card className="p-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="font-display text-xl font-semibold">Pedido #{o.code}</p>
          <p className="text-sm text-muted">Feito em {new Date(o.created_at).toLocaleDateString('pt-BR', { timeZone: 'America/Sao_Paulo' })}</p>
        </div>
        {(() => { const b = refundBadge(o); return b ? <Badge tone={b.tone}>{b.label}</Badge> : <Badge tone={st.tone}>{st.label}</Badge>; })()}
      </div>
      <div className="mt-4"><OrderTimeline status={o.status} /></div>
      <CancelInfo o={o} />
      <ul className="mt-4 divide-y divide-line border-y border-line text-[15px]">
        {o.items.map((i) => (
          <li key={i.id} className="flex items-baseline justify-between gap-3 py-2">
            <span><span className="tnum font-semibold">{i.qty}×</span> {i.name}</span>
            <span className="tnum text-muted">{money(i.qty * i.unit_price)}</span>
          </li>
        ))}
      </ul>
      <div className="mt-3 flex items-baseline justify-between"><span className="font-semibold">Total</span><span className="font-display text-2xl font-semibold tnum">{money(o.total)}</span></div>
      <div className="mt-3 grid gap-1.5 text-sm text-muted">
        {o.manual ? (
          <div className="flex items-start gap-2"><CalendarDays size={16} className="mt-0.5 shrink-0" /><span>Para {dateBR(o.scheduled_for)}</span></div>
        ) : (
          <>
            <div className="flex items-start gap-2"><CalendarDays size={16} className="mt-0.5 shrink-0" /><span>{dateBR(o.scheduled_for)} · {win}</span></div>
            <div className="flex items-start gap-2"><MapPin size={16} className="mt-0.5 shrink-0" /><span>{o.fulfillment === 'entrega' ? `Entrega: ${o.street}, ${o.number}${o.complement ? ` (${o.complement})` : ''} · ${o.district}, ${o.city}` : o.pickup_address ? `Retirada: ${o.pickup_address}` : 'Retirada no local'}</span></div>
          </>
        )}
        <div className="flex items-start gap-2"><CreditCard size={16} className="mt-0.5 shrink-0" /><span>Pagamento: {pay}</span>
          {o.payment_method === 'pix' && <PayBadge o={o} />}</div>
      </div>
      {o.can_cancel && <CancelOrder orderId={o.id} code={o.code} paid={o.paid} total={o.total} cancelUrl={links.cancelUrl} />}
      {!o.can_cancel && o.refund.mode === 'solicitacao' && o.refunds.length === 0 && <p className="mt-3 text-sm text-muted">A produção já começou, então o cancelamento é feito por um pedido de devolução, que passa por análise.</p>}
      <RefundBox o={o} links={links} />
      {o.pix && o.status !== 'cancelado' && <PixCopy orderId={o.id} code={o.pix.code} qr={o.pix.qr_base64} expiresIn={o.pix.expires_in} expired={o.pix.expired} />}
    </Card>
  );
}
