'use client';
import { AnimatePresence, motion } from 'motion/react';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import clsx from 'clsx';
import { CategoryChip, CategoryIcon } from '@/components/CategoryIcon';
import { ICON_NAMES } from '@/components/icon-registry';
import { Button, Card, Field, Input } from '@/components/ui/form';
import { ConfirmDialog, Drawer } from '@/components/ui/Modal';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import type { Category } from '@shared/types';

const KINDS = [
  { id: 'ingredient', label: 'Ingredientes' },
  { id: 'recipe', label: 'Receitas' },
  { id: 'expense', label: 'Gastos' },
] as const;
const COLORS = ['#d42a76', '#e5484d', '#f0a04b', '#d9a441', '#5f9d6e', '#2fa4a9', '#5b8def', '#9b6cf0', '#c2438a', '#8a5a44', '#7a8899'];

type Draft = { id?: number; name: string; icon: string; color: string };

export function CategoriesManager({ categories }: { categories: Category[] }) {
  const router = useRouter();
  const [kind, setKind] = useState<(typeof KINDS)[number]['id']>('ingredient');
  const [draft, setDraft] = useState<Draft | null>(null);
  const [del, setDel] = useState<Category | null>(null);
  const [busy, setBusy] = useState(false);
  const list = categories.filter((c) => c.kind === kind);

  async function save() {
    if (!draft) return;
    setBusy(true);
    try {
      if (draft.id) await api(`/api/categories/${draft.id}`, 'PATCH', draft);
      else await api('/api/categories', 'POST', { ...draft, kind });
      toast.ok(draft.id ? 'Categoria atualizada' : 'Categoria criada');
      setDraft(null); router.refresh();
    } catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }
  async function remove() {
    if (!del) return;
    setBusy(true);
    try { await api(`/api/categories/${del.id}`, 'DELETE'); toast.ok('Categoria removida'); setDel(null); router.refresh(); }
    catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }

  return (
    <>
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div role="tablist" className="relative inline-flex rounded-2xl border border-line bg-surface p-1">
          {KINDS.map((k) => (
            <button key={k.id} role="tab" aria-selected={kind === k.id} onClick={() => setKind(k.id)}
              className={clsx('relative rounded-xl px-4 py-2 text-sm font-semibold transition-colors', kind === k.id ? 'text-on-pink' : 'text-muted hover:text-ink')}>
              {kind === k.id && <motion.span layoutId="cat-tab" className="absolute inset-0 rounded-xl bg-pink" transition={{ type: 'spring', stiffness: 500, damping: 38 }} />}
              <span className="relative">{k.label}</span>
            </button>
          ))}
        </div>
        <Button onClick={() => setDraft({ name: '', icon: 'tag', color: COLORS[0] })}><Plus size={18} />Nova categoria</Button>
      </div>

      <motion.div layout className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <AnimatePresence mode="popLayout">
          {list.map((c) => (
            <motion.div key={c.id} layout initial={{ opacity: 0, scale: 0.95 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: 0.92 }} transition={{ type: 'spring', stiffness: 420, damping: 32 }}>
              <Card className="group flex items-center gap-3.5 p-3.5">
                <span className="grid size-12 shrink-0 place-items-center rounded-2xl" style={{ background: `color-mix(in oklab, ${c.color} 16%, transparent)`, color: c.color }}>
                  <CategoryIcon name={c.icon} size={23} />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="truncate font-semibold">{c.name}</p>
                  <p className="text-sm text-muted">{c.uses === 0 ? 'Sem itens' : c.uses === 1 ? '1 item' : `${c.uses} itens`}</p>
                </div>
                <div className="flex gap-0.5 md:opacity-0 md:transition-opacity md:group-hover:opacity-100 md:group-focus-within:opacity-100">
                  <Button variant="ghost" size="sm" aria-label={`Editar ${c.name}`} onClick={() => setDraft({ id: c.id, name: c.name, icon: c.icon, color: c.color })}><Pencil size={16} /></Button>
                  <Button variant="danger" size="sm" aria-label={`Excluir ${c.name}`} onClick={() => setDel(c)}><Trash2 size={16} /></Button>
                </div>
              </Card>
            </motion.div>
          ))}
        </AnimatePresence>
      </motion.div>
      {list.length === 0 && <p className="rounded-3xl border border-dashed border-line px-6 py-12 text-center text-muted">Nenhuma categoria aqui ainda. Crie a primeira.</p>}

      <Drawer open={!!draft} onClose={() => setDraft(null)} title={draft?.id ? 'Editar categoria' : 'Nova categoria'}
        footer={<><Button variant="ghost" onClick={() => setDraft(null)}>Cancelar</Button><Button type="submit" form="category-form" busy={busy} disabled={!draft?.name.trim()}>Salvar</Button></>}>
        {draft && (
          <form id="category-form" className="flex flex-col gap-6" onSubmit={(e) => { e.preventDefault(); save(); }}>
            <div className="flex items-center gap-4 rounded-2xl bg-surface-2 p-4">
              <span className="grid size-14 place-items-center rounded-2xl" style={{ background: `color-mix(in oklab, ${draft.color} 18%, transparent)`, color: draft.color }}><CategoryIcon name={draft.icon} size={28} /></span>
              <CategoryChip name={draft.name || 'Nome da categoria'} icon={draft.icon} color={draft.color} />
            </div>
            <Field label="Nome">{(id) => <Input id={id} maxLength={40} value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder="Ex.: Massas folhadas" />}</Field>
            <fieldset>
              <legend className="mb-2 text-sm font-medium">Cor</legend>
              <div className="flex flex-wrap gap-2.5">
                {COLORS.map((c) => (
                  <button type="button" key={c} onClick={() => setDraft({ ...draft, color: c })} aria-label={`Cor ${c}`} aria-pressed={draft.color === c}
                    className={clsx('size-8 rounded-full transition-transform hover:scale-110', draft.color === c && 'ring-2 ring-ink ring-offset-2 ring-offset-surface')} style={{ background: c }} />
                ))}
              </div>
            </fieldset>
            <fieldset>
              <legend className="mb-2 text-sm font-medium">Ícone</legend>
              <div className="grid grid-cols-7 gap-1.5 sm:grid-cols-8">
                {ICON_NAMES.map((n) => (
                  <motion.button type="button" key={n} onClick={() => setDraft({ ...draft, icon: n })} aria-label={n} aria-pressed={draft.icon === n} whileTap={{ scale: 0.85 }}
                    className={clsx('grid aspect-square place-items-center rounded-xl border transition-colors', draft.icon === n ? 'border-pink bg-pink-tint text-pink-strong' : 'border-transparent text-muted hover:bg-surface-2 hover:text-ink')}>
                    <CategoryIcon name={n} size={20} />
                  </motion.button>
                ))}
              </div>
            </fieldset>
          </form>
        )}
      </Drawer>

      <ConfirmDialog open={!!del} busy={busy} title={`Excluir “${del?.name}”?`} onClose={() => setDel(null)} onConfirm={remove}
        message={del && del.uses > 0 ? `${del.uses} item(ns) usam esta categoria e ficarão sem categoria. Nada mais é apagado.` : 'Esta categoria não está em uso.'} />
    </>
  );
}
