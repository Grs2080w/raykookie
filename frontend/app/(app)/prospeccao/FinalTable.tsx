'use client';
// Modo de visualização para print: esconde os controles e deixa só a tabela. No celular cada produto vira um bloco (tudo cabe na tela).
import clsx from 'clsx';
import { ArrowLeft, Download, Printer } from 'lucide-react';
import { useEffect } from 'react';
import { Button } from '@/components/ui/form';
import { money, number, percent2 } from '@/lib/format';
import type { Level, ProspectRow, ProspectTotals } from '@shared/prospection';

const tone: Record<Level, string> = { ok: 'text-ok', low: 'text-warn', negative: 'text-bad' };
const qtyText = (n: number) => number(n);

// Ao imprimir (Ctrl+P / "Salvar como PDF"): some o menu do painel e sobra só a tabela.
const PRINT_CSS = `@media print{html.pp-on aside,html.pp-on header,html.pp-on nav{display:none!important}html.pp-on .min-h-dvh{padding-left:0!important}html.pp-on main{padding:0!important}.pp-hide{display:none!important}.pp-root{position:static!important;overflow:visible!important}}`;

export function FinalTable({ rows, totals, title, date, onBack, onCsv }: { rows: ProspectRow[]; totals: ProspectTotals; title: string; date: string; onBack: () => void; onCsv: () => void }) {
  useEffect(() => {
    document.documentElement.classList.add('pp-on');
    return () => document.documentElement.classList.remove('pp-on');
  }, []);
  return (
    <div className="pp-root fixed inset-0 z-50 overflow-auto bg-surface text-ink">
      <style>{PRINT_CSS}</style>
      <div className="mx-auto w-full max-w-5xl px-4 py-5 sm:px-8 sm:py-8">
        <div className="pp-hide mb-5 flex flex-wrap items-center justify-between gap-2">
          <Button variant="soft" size="sm" onClick={onBack}><ArrowLeft size={16} />Voltar e editar</Button>
          <div className="flex gap-2">
            <Button variant="ghost" size="sm" onClick={onCsv}><Download size={16} />CSV</Button>
            <Button size="sm" onClick={() => window.print()}><Printer size={16} />Imprimir / PDF</Button>
          </div>
        </div>
        <header className="mb-5 border-b-2 border-ink pb-3">
          <h1 className="font-display text-2xl font-semibold sm:text-3xl">{title}</h1>
          <p className="mt-0.5 text-sm text-muted">Gerada em {date}</p>
        </header>

        {/* ≥ sm: tabela */}
        <table className="hidden w-full border-collapse text-[15px] sm:table">
          <thead>
            <tr className="border-b-2 border-ink text-left text-xs font-bold uppercase tracking-wide">
              <th className="py-2 pr-3">Produto</th>
              <th className="px-2 py-2 text-right">Qtd</th>
              <th className="px-2 py-2 text-right">Preço unit.</th>
              <th className="px-2 py-2 text-right">Desconto</th>
              <th className="px-2 py-2 text-right">Preço final unit.</th>
              <th className="px-2 py-2 text-right">Total</th>
              <th className="px-2 py-2 text-right">Lucro</th>
              <th className="py-2 pl-2 text-right">Margem</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id} className="border-b border-line align-top">
                <td className="py-2.5 pr-3 font-semibold">{r.name}</td>
                <td className="tnum px-2 py-2.5 text-right">{qtyText(r.qty)}</td>
                <td className="tnum px-2 py-2.5 text-right">{money(r.unitPrice)}</td>
                <td className="tnum px-2 py-2.5 text-right">{r.discountValue > 0 ? <>{percent2(r.discountPct)}<span className="block text-xs text-muted">− {money(r.discountValue)}</span></> : '—'}</td>
                <td className="tnum px-2 py-2.5 text-right font-semibold">{money(r.finalUnit)}</td>
                <td className="tnum px-2 py-2.5 text-right font-semibold">{money(r.net)}</td>
                <td className={clsx('tnum px-2 py-2.5 text-right font-semibold', tone[r.level])}>{money(r.profit)}</td>
                <td className={clsx('tnum py-2.5 pl-2 text-right font-semibold', tone[r.level])}>{percent2(r.marginPct)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr className="border-t-2 border-ink font-bold">
              <td className="py-3 pr-3">TOTAL</td>
              <td className="tnum px-2 py-3 text-right">{qtyText(totals.qty)}</td>
              <td className="px-2 py-3" />
              <td className="tnum px-2 py-3 text-right">{totals.discountValue > 0 ? <>{percent2(totals.discountPct)}<span className="block text-xs font-normal text-muted">− {money(totals.discountValue)}</span></> : '—'}</td>
              <td className="px-2 py-3" />
              <td className="tnum px-2 py-3 text-right">{money(totals.net)}</td>
              <td className={clsx('tnum px-2 py-3 text-right', tone[totals.level])}>{money(totals.profit)}</td>
              <td className={clsx('tnum py-3 pl-2 text-right', tone[totals.level])}>{percent2(totals.marginPct)}</td>
            </tr>
          </tfoot>
        </table>

        {/* < sm: um bloco por produto */}
        <div className="space-y-3 sm:hidden">
          {rows.map((r) => (
            <section key={r.id} className="rounded-2xl border border-line p-3.5">
              <h2 className="text-base font-bold">{r.name}</h2>
              <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1.5 text-sm">
                <Cell k="Qtd" v={qtyText(r.qty)} />
                <Cell k="Preço unit." v={money(r.unitPrice)} />
                <Cell k="Desconto" v={r.discountValue > 0 ? `${percent2(r.discountPct)} (− ${money(r.discountValue)})` : '—'} />
                <Cell k="Preço final unit." v={money(r.finalUnit)} strong />
                <Cell k="Total" v={money(r.net)} strong />
                <Cell k="Lucro" v={money(r.profit)} cls={tone[r.level]} strong />
                <Cell k="Margem" v={percent2(r.marginPct)} cls={tone[r.level]} strong />
              </dl>
            </section>
          ))}
          <section className="rounded-2xl border-2 border-ink p-3.5">
            <h2 className="text-base font-bold">TOTAL</h2>
            <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1.5 text-sm">
              <Cell k="Qtd" v={qtyText(totals.qty)} />
              <Cell k="Desconto" v={totals.discountValue > 0 ? `${percent2(totals.discountPct)} (− ${money(totals.discountValue)})` : '—'} />
              <Cell k="Total" v={money(totals.net)} strong />
              <Cell k="Lucro" v={money(totals.profit)} cls={tone[totals.level]} strong />
              <Cell k="Margem" v={percent2(totals.marginPct)} cls={tone[totals.level]} strong />
            </dl>
          </section>
        </div>
      </div>
    </div>
  );
}

function Cell({ k, v, strong, cls }: { k: string; v: string; strong?: boolean; cls?: string }) {
  return (
    <div>
      <dt className="text-xs text-muted">{k}</dt>
      <dd className={clsx('tnum', strong && 'font-semibold', cls)}>{v}</dd>
    </div>
  );
}
