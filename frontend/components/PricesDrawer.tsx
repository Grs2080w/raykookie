'use client';
import { Search, Sparkles } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { Badge } from '@/components/ui/Badge';
import { Button, Field, Input, NumberInput, Select } from '@/components/ui/form';
import { Drawer } from '@/components/ui/Modal';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import { dateBR, money, parseNum } from '@/lib/format';
import { pricePerLabel } from '@shared/pack';
import type { Unit } from '@shared/pricing';

type Quote = { id: number; supplier_id: number; supplier: string; kind: string; price: number; pack_size: number; pack_unit: Unit; source: string; at: string; per_base: number; product: string | null; url: string | null };
type Data = {
  ingredient: { id: number; name: string; unit: Unit; package_cost: number; package_size: number; price_updated_at: string | null };
  current_per_base: number; quotes: Quote[]; history: { price: number; pack_size: number; source: string; at: string; supplier: string | null }[];
};
type Product = { name: string; url: string; price: number; size: number | null; unit: Unit | null; available: boolean; per_base: number | null; match_unit: boolean };
type Search = { term: string; unit: Unit; results: { supplier_id: number; supplier: string; products: Product[] }[]; errors: { supplier_id: number; supplier: string; error: string }[] };
type Supplier = { id: number; name: string; kind: string };

const SOURCE: Record<string, string> = { scraper: 'site', manual: 'cotação', nota: 'nota fiscal', edicao: 'edição' };
const label = (price: number, size: number, unit: Unit) => pricePerLabel(price, size, unit);

/** Preços de um ingrediente: cotações de fornecedores (da mais barata para a mais cara), pesquisa nos sites e histórico. */
export function PricesDrawer({ ingredientId, name, onClose }: { ingredientId: number | null; name: string; onClose: () => void }) {
  const router = useRouter();
  const [data, setData] = useState<Data | null>(null);
  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [found, setFound] = useState<Search | null>(null);
  const [term, setTerm] = useState('');
  const [busy, setBusy] = useState('');
  const [sizes, setSizes] = useState<Record<string, string>>({});
  const [man, setMan] = useState({ supplier_id: '', price: '', pack_size: '' });

  async function load() {
    if (!ingredientId) return;
    try { setData(await api<Data>(`/api/ingredients/${ingredientId}/prices`, 'GET')); setSuppliers(await api<Supplier[]>('/api/suppliers', 'GET')); }
    catch (e: any) { toast.error(e.message); }
  }
  useEffect(() => { setData(null); setFound(null); setTerm(name); setMan({ supplier_id: '', price: '', pack_size: '' }); void load(); }, [ingredientId]); // eslint-disable-line react-hooks/exhaustive-deps

  const run = async (key: string, fn: () => Promise<void>) => { setBusy(key); try { await fn(); } catch (e: any) { toast.error(e.message); } finally { setBusy(''); } };
  const search = () => run('search', async () => setFound(await api<Search>('/api/prices/search', 'POST', { ingredient_id: ingredientId, term })));
  const use = (q: Quote) => run(`use${q.id}`, async () => {
    const r = await api<{ alerts: number }>(`/api/ingredients/${ingredientId}/use-price`, 'POST', { quote_id: q.id });
    toast.ok(r.alerts ? 'Preço atualizado. Veja os alertas de margem no início.' : 'Preço do ingrediente atualizado');
    await load(); router.refresh();
  });
  const link = (supplier_id: number, p: Product) => run(`link${p.url}`, async () => {
    const size = p.size && p.unit === data?.ingredient.unit ? p.size : parseNum(sizes[p.url] ?? '');
    await api('/api/prices/link', 'POST', { ingredient_id: ingredientId, supplier_id, name: p.name, url: p.url, price: p.price, size, unit: data?.ingredient.unit });
    toast.ok('Produto ligado: o preço é conferido todo dia'); await load();
  });
  const addQuote = () => run('quote', async () => {
    await api('/api/prices/quote', 'POST', { ingredient_id: ingredientId, supplier_id: Number(man.supplier_id), price: parseNum(man.price), pack_size: parseNum(man.pack_size) });
    toast.ok('Cotação guardada'); setMan({ ...man, price: '', pack_size: '' }); await load();
  });

  const d = data?.ingredient;
  const cheapest = data?.quotes[0]?.id;
  return (
    <Drawer open={ingredientId != null} onClose={onClose} title={`Preços · ${name}`}>
      {!data || !d ? <p className="text-muted">Carregando…</p> : (
        <div className="grid gap-6 text-sm">
          <p>Hoje no estoque: <b className="tnum">{money(d.package_cost)}</b> por {d.package_size} {d.unit} · {label(d.package_cost, d.package_size, d.unit)}{d.price_updated_at ? ` (atualizado em ${dateBR(d.price_updated_at)})` : ''}</p>

          <section aria-label="Cotações">
            <h3 className="font-bold">Cotações por fornecedor</h3>
            {data.quotes.length === 0 ? <p className="mt-1 text-muted">Nenhuma cotação ainda. Pesquise nos sites ou cadastre uma abaixo.</p> : (
              <ul className="mt-2 grid gap-2">
                {data.quotes.map((q) => (
                  <li key={q.id} className="rounded-xl border border-line p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <b>{q.supplier}</b>{q.id === cheapest && <Badge tone="ok">Mais barato</Badge>}<span className="text-muted">{SOURCE[q.source]} · {dateBR(q.at)}</span>
                    </div>
                    <p className="tnum">{money(q.price)} por {q.pack_size} {q.pack_unit} · <b>{label(q.price, q.pack_size, q.pack_unit)}</b></p>
                    {q.product && <p className="truncate text-muted">{q.product}</p>}
                    <Button size="sm" variant="soft" className="mt-2" busy={busy === `use${q.id}`} onClick={() => use(q)}>Usar este preço</Button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section aria-label="Pesquisar nos sites">
            <h3 className="font-bold">Pesquisar nos sites dos fornecedores</h3>
            <div className="mt-2 flex gap-2">
              <Input value={term} onChange={(e) => setTerm(e.target.value)} aria-label="O que procurar" maxLength={80} />
              <Button busy={busy === 'search'} onClick={search}><Search size={16} />Buscar</Button>
            </div>
            {found?.errors.map((e) => <p key={e.supplier_id} role="alert" className="mt-2 rounded-xl bg-warn/10 p-2">{e.supplier} precisa de revisão: {e.error}</p>)}
            {found && found.results.length === 0 && found.errors.length === 0 && <p className="mt-2 text-muted">Nenhum fornecedor com busca cadastrado. Cadastre um site em Fornecedores.</p>}
            {found?.results.map((r) => (
              <div key={r.supplier_id} className="mt-3">
                <p className="font-semibold">{r.supplier}</p>
                {r.products.length === 0 && <p className="text-muted">Nada encontrado para “{found.term}”.</p>}
                <ul className="mt-1 grid gap-2">
                  {r.products.map((p) => (
                    <li key={p.url} className="rounded-xl border border-line p-3">
                      <p>{p.name}</p>
                      <p className="tnum">{money(p.price)}{p.size && p.unit ? ` · ${p.size} ${p.unit} · ${label(p.price, p.size, p.unit)}` : ''}</p>
                      {(!p.size || p.unit !== d.unit) && (
                        <Field label={`Tamanho da embalagem em ${d.unit}`} className="mt-2">{(id) => <NumberInput id={id} value={sizes[p.url] ?? ''} onValueChange={(v) => setSizes({ ...sizes, [p.url]: v })} />}</Field>
                      )}
                      <Button size="sm" variant="soft" className="mt-2" busy={busy === `link${p.url}`} onClick={() => link(r.supplier_id, p)}>Ligar ao ingrediente</Button>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </section>

          <section aria-label="Cotação manual">
            <h3 className="font-bold">Cadastrar cotação à mão</h3>
            {suppliers.length === 0 ? <p className="mt-1 text-muted">Cadastre um fornecedor em Fornecedores para guardar cotações.</p> : (
              <div className="mt-2 grid grid-cols-2 gap-3">
                <Field label="Fornecedor" className="col-span-2">{(id) => <Select id={id} value={man.supplier_id} onChange={(e) => setMan({ ...man, supplier_id: e.target.value })}><option value="">Escolha</option>{suppliers.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}</Select>}</Field>
                <Field label="Preço (R$)">{(id) => <NumberInput id={id} value={man.price} onValueChange={(v) => setMan({ ...man, price: v })} />}</Field>
                <Field label={`Tamanho (${d.unit})`}>{(id) => <NumberInput id={id} value={man.pack_size} onValueChange={(v) => setMan({ ...man, pack_size: v })} />}</Field>
                <Button className="col-span-2" busy={busy === 'quote'} onClick={addQuote} disabled={!man.supplier_id}><Sparkles size={16} />Guardar cotação</Button>
              </div>
            )}
          </section>

          <section aria-label="Histórico">
            <h3 className="font-bold">Histórico</h3>
            {data.history.length === 0 ? <p className="mt-1 text-muted">Sem histórico ainda.</p> : (
              <ol className="mt-1 grid gap-1 text-muted">{data.history.map((h, i) => <li key={i} className="tnum">{dateBR(h.at)} · {money(h.price)} por {h.pack_size} {d.unit} · {h.supplier ?? SOURCE[h.source]}</li>)}</ol>
            )}
          </section>
        </div>
      )}
    </Drawer>
  );
}
