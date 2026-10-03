import Link from 'next/link';
import { money } from '@/lib/format';
import type { CustomerRefund, ShopOrder } from '@shared/types';
import { WithdrawRefund } from './WithdrawRefund';
import type { OrderLinks } from './OrderCard';
import { FULL_REFUND_CODE } from '@shared/refund';

const when = (iso: string) => new Date(iso).toLocaleString('pt-BR', { timeZone: 'America/Sao_Paulo', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }).replace(', ', ' às ');

/** Selo do topo do card quando há cancelamento ou devolução (ganha do status do pedido). */
export function refundBadge(o: ShopOrder): { label: string; tone: 'ok' | 'warn' | 'bad' | 'neutral' } | null {
  const r = o.refunds[0];
  if (r && r.kind === 'solicitado') {
    if (r.status === 'solicitado') return { label: 'Devolução em análise', tone: 'warn' };
    if (r.status === 'negado') return { label: 'Devolução negada', tone: 'bad' };
    if (r.status === 'concluido') return { label: `Devolvido ${money(r.approved_amount ?? 0)}`, tone: 'ok' };
    return { label: 'Devolução aprovada', tone: 'ok' };
  }
  if (o.cancel?.code === FULL_REFUND_CODE) return { label: `Devolvido ${money(o.refunded_total)}`, tone: 'ok' };
  if (o.cancel || o.status === 'cancelado') return { label: 'Cancelado', tone: 'neutral' };
  if (o.refunded_total > 0) return { label: `Devolvido ${money(o.refunded_total)}`, tone: 'ok' };
  return null;
}

/** Andamento em bolinhas, como no app. */
function Steps({ labels, done, finalOk }: { labels: string[]; done: number; finalOk?: boolean }) {
  return (
    <ol aria-label={`Andamento da devolução: ${labels[Math.max(0, done - 1)]}`} className="mt-2 grid gap-1 text-center text-[11.5px]" style={{ gridTemplateColumns: `repeat(${labels.length}, minmax(0, 1fr))` }}>
      {labels.map((l, i) => (
        <li key={l} aria-current={i === done - 1 ? 'step' : undefined} className={`flex flex-col items-center gap-1 ${i <= done ? 'font-bold' : 'text-muted'}`}>
          <span className={`size-[22px] rounded-full border-2 ${i < done ? (finalOk && i === labels.length - 1 ? 'border-ok bg-ok' : 'border-pink bg-pink') : i === done ? 'border-pink bg-white' : 'border-line bg-white'}`} />
          {l}
        </li>
      ))}
    </ol>
  );
}

/** Quem cancelou, o motivo e a mensagem. */
export function CancelInfo({ o }: { o: ShopOrder }) {
  const c = o.cancel;
  if (!c) return null;
  if (c.code === FULL_REFUND_CODE) {
    return (
      <section aria-label="Cancelamento" className="mt-4 rounded-2xl bg-surface-2 p-3.5">
        <p className="text-xs font-extrabold uppercase tracking-wide text-muted">Pedido encerrado</p>
        <p className="mt-1 font-extrabold">{c.reason}</p>
        <p className="mt-1 text-sm text-muted">Como o valor voltou para você, a loja não vai mais produzir este pedido.</p>
      </section>
    );
  }
  const byStore = c.by !== 'cliente';
  return (
    <section aria-label="Cancelamento" className={`mt-4 rounded-2xl p-3.5 ${byStore ? 'bg-bad/5' : 'bg-surface-2'}`}>
      <p className={`text-xs font-extrabold uppercase tracking-wide ${byStore ? 'text-bad' : 'text-muted'}`}>{c.by === 'cliente' ? 'Você cancelou' : c.by === 'sistema' ? 'Cancelado automaticamente' : 'Cancelado pela loja'}</p>
      {c.reason && <p className="mt-1 font-extrabold">Motivo: {c.reason}</p>}
      {c.note && <p className="mt-1 text-sm"><span className="text-muted">{byStore ? 'Mensagem da loja: ' : 'Você escreveu: '}</span>{c.note}</p>}
    </section>
  );
}

function One({ o, r, links }: { o: ShopOrder; r: CustomerRefund; links?: OrderLinks }) {
  const denied = r.status === 'negado';
  const done = r.status === 'concluido';
  const amount = r.approved_amount ?? r.requested_amount;
  const note = r.decision_note ? <p className="text-sm"><span className="text-muted">Mensagem da loja: </span>{r.decision_note}</p> : null;
  let body: React.ReactNode;
  if (r.kind === 'solicitado') {
    const partial = r.approved_amount != null && r.approved_amount < r.requested_amount;
    body = (
      <>
        <p className="font-extrabold">
          {denied ? `Motivo da loja: ${r.decision_reason || 'devolução negada'}`
            : r.status === 'solicitado' ? `Você pediu ${money(r.requested_amount)} de volta`
            : partial ? `Devolução aprovada em parte: ${money(r.approved_amount!)} de ${money(r.requested_amount)}`
            : `Devolução aprovada: ${money(amount)}`}
        </p>
        {r.status === 'solicitado' && r.reason && <p className="text-sm text-muted">Motivo: {r.reason}{r.photos > 0 ? ` · ${r.photos} foto${r.photos > 1 ? 's' : ''}` : ''}</p>}
        {note}
        {!denied && <Steps labels={['Solicitada', 'Em análise', 'Aprovada', 'Pix devolvido']} done={r.status === 'solicitado' ? 1 : done ? 4 : 3} finalOk={done} />}
        {r.status === 'solicitado' && <p className="mt-1 text-sm text-muted">Respondemos em até 1 dia útil.</p>}
      </>
    );
  } else {
    const storeOnly = r.kind === 'loja' && !o.cancel;
    body = (
      <>
        {storeOnly && <p className="text-xs font-extrabold uppercase tracking-wide text-pink-strong">Devolução da loja</p>}
        <p className="font-extrabold">{done ? `Pix devolvido: ${money(amount)}` : `Devolução de ${money(amount)} em processamento`}</p>
        {storeOnly && note}
        <Steps labels={['Devolução iniciada', 'Em processamento', 'Pix devolvido']} done={done ? 3 : 2} finalOk={done} />
      </>
    );
  }
  return (
    <section aria-label="Devolução" className={`mt-3 rounded-2xl p-3.5 ${denied ? 'bg-bad/5' : 'bg-pink-tint/60'}`}>
      {body}
      {done && r.refunded_at && <p className="mt-2 text-sm font-semibold text-ok">O valor voltou para a conta que pagou, em {when(r.refunded_at)}.</p>}
      {r.kind === 'solicitado' && r.status === 'solicitado' && <WithdrawRefund orderId={o.id} api={links?.refundApi} />}
    </section>
  );
}

export function RefundBox({ o, links }: { o: ShopOrder; links?: OrderLinks }) {
  const refundable = o.refund.mode === 'solicitacao';
  if (!o.refunds.length && !refundable) return null;
  return (
    <div>
      {o.refunds.map((r) => <One key={r.id} o={o} r={r} links={links} />)}
      {refundable && (
        <div className="mt-3">
          <Link href={links?.refundHref ?? `/cliente/pedidos/${o.id}/devolucao`} className="inline-flex min-h-12 w-full items-center justify-center rounded-xl border-2 border-line bg-white px-4 font-semibold hover:border-pink">Pedir devolução</Link>
          <p className="mt-1.5 text-xs text-muted">
            {o.refund.deadline ? `Você pode pedir até ${when(o.refund.deadline)}. ` : ''}A devolução depende de análise e não é garantida.
          </p>
        </div>
      )}
    </div>
  );
}
