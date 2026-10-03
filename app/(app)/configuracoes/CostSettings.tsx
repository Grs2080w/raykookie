'use client';
import { AnimatePresence, motion } from 'motion/react';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { CategoryChip } from '@/components/CategoryIcon';
import { Badge } from '@/components/ui/Badge';
import { Button, Card, Field, Input, NumberInput, Select } from '@/components/ui/form';
import { ConfirmDialog, Drawer } from '@/components/ui/Modal';
import { Counter } from '@/components/ui/Money';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import { money, parseNum } from '@/lib/format';
import type { Category, FixedCost, Settings } from '@/lib/queries';

type Draft = { id?: number; name: string; category_id: string; kind: string; amount: string };

export function CostSettings({ settings, costs, categories }: { settings: Settings; costs: FixedCost[]; categories: Category[] }) {
  const router = useRouter();
  const [rate, setRate] = useState(String(settings.hourly_rate || ''));
  const [units, setUnits] = useState(String(settings.monthly_units));
  const [draft, setDraft] = useState<Draft | null>(null);
  const [del, setDel] = useState<FixedCost | null>(null);
  const [busy, setBusy] = useState(false);
  const catMap = new Map(categories.map((c) => [c.id, c]));
  const total = costs.reduce((s, c) => s + c.amount, 0);
  const perUnit = parseNum(units) > 0 ? total / parseNum(units) : 0;

  async function saveSettings() {
    setBusy(true);
    try { await api('/api/settings', 'PUT', { hourly_rate: rate, monthly_units: units }); toast.ok('Ajustes salvos'); router.refresh(); }
    catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }
  async function saveCost() {
    if (!draft) return;
    setBusy(true);
    try {
      if (draft.id) await api(`/api/fixed-costs/${draft.id}`, 'PATCH', draft); else await api('/api/fixed-costs', 'POST', draft);
      toast.ok(draft.id ? 'Gasto atualizado' : 'Gasto adicionado'); setDraft(null); router.refresh();
    } catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }
  async function removeCost() {
    if (!del) return;
    setBusy(true);
    try { await api(`/api/fixed-costs/${del.id}`, 'DELETE'); toast.ok('Gasto removido'); setDel(null); router.refresh(); }
    catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_360px]">
      <Card className="p-5">
        <div className="flex items-center justify-between gap-3">
          <h2 className="font-display text-xl font-semibold">Gastos fixos e variáveis do mês</h2>
          <Button size="sm" onClick={() => setDraft({ name: '', category_id: '', kind: 'fixed', amount: '' })}><Plus size={16} />Adicionar</Button>
        </div>
        {costs.length === 0 ? (
          <p className="mt-6 rounded-2xl border border-dashed border-line px-4 py-10 text-center text-sm text-muted">Nenhum gasto ainda. Some energia, gás, internet, embalagens de uso geral… O total é dividido entre as unidades que você produz.</p>
        ) : (
          <ul className="mt-4 divide-y divide-line">
            <AnimatePresence initial={false}>
              {costs.map((c) => {
                const cat = c.category_id ? catMap.get(c.category_id) : null;
                return (
                  <motion.li key={c.id} layout initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0, height: 0 }} className="flex items-center gap-3 py-3">
                    <div className="min-w-0 flex-1">
                      <p className="truncate font-semibold">{c.name}</p>
                      <div className="mt-1 flex flex-wrap items-center gap-2">{cat && <CategoryChip name={cat.name} icon={cat.icon} color={cat.color} size="sm" />}<Badge tone="neutral" dot={false}>{c.kind === 'fixed' ? 'Fixo' : 'Variável'}</Badge></div>
                    </div>
                    <span className="tnum font-semibold">{money(c.amount)}</span>
                    <Button variant="ghost" size="sm" aria-label={`Editar ${c.name}`} onClick={() => setDraft({ id: c.id, name: c.name, category_id: c.category_id ? String(c.category_id) : '', kind: c.kind, amount: String(c.amount) })}><Pencil size={16} /></Button>
                    <Button variant="danger" size="sm" aria-label={`Excluir ${c.name}`} onClick={() => setDel(c)}><Trash2 size={16} /></Button>
                  </motion.li>
                );
              })}
            </AnimatePresence>
          </ul>
        )}
        <div className="mt-2 flex items-baseline justify-between border-t border-line pt-4 font-semibold"><span>Total por mês</span><Counter value={total} className="font-display text-2xl" /></div>
      </Card>

      <div className="space-y-6">
        <Card className="p-5">
          <h2 className="font-display text-xl font-semibold">Base do cálculo</h2>
          <form className="mt-4 flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); saveSettings(); }}>
            <Field label="Valor da sua hora de trabalho (R$)" hint="Multiplicado pelo tempo de produção de cada receita.">{(id) => <NumberInput id={id} value={rate} onValueChange={(v) => setRate(v)} placeholder="Ex.: 20" />}</Field>
            <Field label="Unidades produzidas por mês" hint="Estimativa. Divide os gastos do mês entre as unidades.">{(id) => <NumberInput id={id} value={units} onValueChange={(v) => setUnits(v)} />}</Field>
            <Button type="submit" busy={busy}>Salvar ajustes</Button>
          </form>
        </Card>
        <Card className="bg-pink-tint p-5 text-pink-strong">
          <p className="text-sm font-medium">Rateio por unidade produzida</p>
          <p className="font-display mt-1 text-4xl font-semibold"><Counter value={perUnit} kind="precise" /></p>
          <p className="mt-2 text-sm opacity-85">{money(total)} ÷ {parseNum(units) || 0} unidades. Esse valor entra no custo de cada unidade de toda receita.</p>
        </Card>
      </div>

      <Drawer open={!!draft} onClose={() => setDraft(null)} title={draft?.id ? 'Editar gasto' : 'Novo gasto'}
        footer={<><Button variant="ghost" onClick={() => setDraft(null)}>Cancelar</Button><Button type="submit" form="cost-form" busy={busy}>Salvar</Button></>}>
        {draft && (
          <form id="cost-form" className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); saveCost(); }}>
            <Field label="Nome">{(id) => <Input maxLength={80} id={id} value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder="Ex.: Conta de luz" />}</Field>
            <Field label="Categoria">{(id) => (
              <Select id={id} value={draft.category_id} onChange={(e) => setDraft({ ...draft, category_id: e.target.value })}>
                <option value="">Sem categoria</option>{categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </Select>)}</Field>
            <Field label="Tipo">{(id) => <Select id={id} value={draft.kind} onChange={(e) => setDraft({ ...draft, kind: e.target.value })}><option value="fixed">Fixo (mesmo valor todo mês)</option><option value="variable">Variável (média do mês)</option></Select>}</Field>
            <Field label="Valor por mês (R$)">{(id) => <NumberInput id={id} value={draft.amount} onValueChange={(v) => setDraft({ ...draft, amount: v })} />}</Field>
          </form>
        )}
      </Drawer>
      <ConfirmDialog open={!!del} busy={busy} title={`Remover “${del?.name}”?`} message="O rateio por unidade e o preço das receitas serão recalculados." confirmLabel="Remover" onClose={() => setDel(null)} onConfirm={removeCost} />
    </div>
  );
}
