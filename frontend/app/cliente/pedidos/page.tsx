import { CheckCircle2, Plus, ShoppingBag } from 'lucide-react';
import Link from 'next/link';
import { EmptyState } from '@/components/PageHeader';
import { apiGet, requireCustomer } from '@/lib/server-api';
import type { ShopOrder } from '@shared/types';
import { OrderCard, orderLinks } from '@/components/OrderCard';
import { FeedbackForm } from '@/components/site/FeedbackForm';

export const metadata = { title: 'Meus pedidos', description: 'Acompanhe suas encomendas na Raykookie.' };

const newBtn = 'rk-btn rk-btn--lg w-full sm:w-auto';

export default async function Page({ searchParams }: { searchParams: Promise<{ novo?: string }> }) {
  const { novo } = await searchParams;
  const customer = await requireCustomer('/cliente/pedidos');
  const orders = await apiGet<ShopOrder[]>('/api/cliente/pedidos', '/cliente/entrar');
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
            <p className="font-bold">Pedido #{justCreated.code} recebido!</p>
            <p className="text-sm text-muted">
              {justCreated.pix ? 'Pague com o Pix abaixo. ' : justCreated.payment_method === 'pix' ? 'Estamos gerando o seu Pix; se ele não aparecer aqui, falamos com você pelo WhatsApp. ' : ''}
              Todo o acompanhamento do pedido é feito aqui, pelo site e pelo aplicativo. Só entraremos em contato pelo WhatsApp se for necessário.
            </p>
          </div>
        </div>
      )}

      <div className="mt-6">
        {orders.length === 0 ? (
          <EmptyState icon={<ShoppingBag size={26} />} title="Você ainda não fez pedidos" text="Escolha o que quer no cardápio e diga para quando precisa. Leva só alguns minutos." action={<Link href="/cliente/pedidos/novo" className={newBtn}><Plus size={19} />Fazer meu primeiro pedido</Link>} />
        ) : (
          <ul className="flex flex-col gap-4">
            {orders.map((o) => <li key={o.id}><OrderCard o={o} links={orderLinks(o)} /></li>)}
          </ul>
        )}
      </div>

      <div className="mt-10">
        <FeedbackForm />
      </div>
    </>
  );
}
