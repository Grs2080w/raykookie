import { CalendarDays, CheckCircle2, CreditCard, MapPin, Plus, ShoppingBag } from 'lucide-react';
import Link from 'next/link';
import { EmptyState } from '@/components/PageHeader';
import { Badge } from '@/components/ui/Badge';
import { Card } from '@/components/ui/form';
import { requireCustomer } from '@/lib/customer-auth';
import { dateBR, money } from '@/lib/format';
import { CUSTOMER_STATUS, PAYMENT_METHODS, TIME_WINDOWS } from '@/lib/shop';
import { getCustomerOrders } from '@/lib/shop-queries';

export const metadata = { title: 'Meus pedidos' };

const newBtn = 'inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-2xl bg-pink px-6 text-base font-bold text-on-pink shadow-[0_10px_24px_-10px_var(--pink)] transition hover:bg-pink-strong active:scale-[0.98] sm:w-auto';

export default async function Page({ searchParams }: { searchParams: Promise<{ novo?: string }> }) {
  const { novo } = await searchParams;
  const customer = await requireCustomer('/cliente/pedidos');
  const orders = await getCustomerOrders(customer.id);
  const justCreated = novo && orders.find((o) => String(o.id) === novo);

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-3xl font-semibold leading-tight">Meus pedidos</h1>
          <p className="text-muted">Olá, {customer.name.split(' ')[0]}! Acompanhe tudo por aqui.</p>
        </div>
        <Link href="/cliente/pedidos/novo" className={newBtn}><Plus size={19} />Novo pedido</Link>
      </div>

      {justCreated && (
        <div role="status" className="mt-5 flex items-start gap-3 rounded-2xl border border-ok/30 bg-ok/10 p-4">
          <CheckCircle2 className="mt-0.5 shrink-0 text-ok" size={22} />
          <div>
            <p className="font-bold">Pedido #{justCreated.id} recebido!</p>
            <p className="text-sm text-muted">Vamos entrar em contato pelo telefone que você informou para confirmar os detalhes{justCreated.payment_method === 'pix' ? ' e enviar a chave Pix' : ''}.</p>
          </div>
        </div>
      )}

      <div className="mt-6">
        {orders.length === 0 ? (
          <EmptyState icon={<ShoppingBag size={26} />} title="Você ainda não fez pedidos" text="Escolha o que quer no cardápio e diga para quando precisa. Leva só alguns minutos." action={<Link href="/cliente/pedidos/novo" className={newBtn}><Plus size={19} />Fazer meu primeiro pedido</Link>} />
        ) : (
          <ul className="flex flex-col gap-4">
            {orders.map((o) => {
              const st = CUSTOMER_STATUS[o.status] ?? CUSTOMER_STATUS.novo;
              const pay = PAYMENT_METHODS.find((p) => p.id === o.payment_method)?.label;
              const win = TIME_WINDOWS.find((w) => w.id === o.time_window)?.label;
              return (
                <li key={o.id}>
                  <Card className="p-4">
                    <div className="flex items-start justify-between gap-3">
                      <div>
                        <p className="font-display text-xl font-semibold">Pedido #{o.id}</p>
                        <p className="text-sm text-muted">Feito em {new Date(o.created_at).toLocaleDateString('pt-BR', { timeZone: 'America/Sao_Paulo' })}</p>
                      </div>
                      <Badge tone={st.tone}>{st.label}</Badge>
                    </div>
                    <ul className="mt-3 divide-y divide-line border-y border-line text-[15px]">
                      {o.items.map((i) => (
                        <li key={i.id} className="flex items-baseline justify-between gap-3 py-2">
                          <span><span className="tnum font-semibold">{i.qty}×</span> {i.name}</span>
                          <span className="tnum text-muted">{money(i.qty * i.unit_price)}</span>
                        </li>
                      ))}
                    </ul>
                    <div className="mt-3 flex items-baseline justify-between"><span className="font-semibold">Total</span><span className="font-display text-2xl font-semibold tnum">{money(o.total)}</span></div>
                    <div className="mt-3 grid gap-1.5 text-sm text-muted">
                      <div className="flex items-start gap-2"><CalendarDays size={16} className="mt-0.5 shrink-0" /><span>{dateBR(o.scheduled_for)} · {win}</span></div>
                      <div className="flex items-start gap-2"><MapPin size={16} className="mt-0.5 shrink-0" /><span>{o.fulfillment === 'entrega' ? `Entrega: ${o.street}, ${o.number}${o.complement ? ` (${o.complement})` : ''} · ${o.district}, ${o.city}` : 'Retirada no local'}</span></div>
                      <div className="flex items-start gap-2"><CreditCard size={16} className="mt-0.5 shrink-0" /><span>Pagamento: {pay}</span></div>
                    </div>
                  </Card>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </>
  );
}
