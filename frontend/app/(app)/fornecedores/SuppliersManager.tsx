'use client';
import { Globe, Plus, Store, Trash2 } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { Badge } from '@/components/ui/Badge';
import { Button, Card, Field, Input, Select } from '@/components/ui/form';
import { ConfirmDialog, Drawer } from '@/components/ui/Modal';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import { dateBR } from '@/lib/format';

export type Supplier = { id: number; name: string; kind: 'site' | 'loja'; url: string; address: string; notes: string; scraper: string | null; last_ok_at: string | null; last_error: string };
const blank = { name: '', kind: 'site', url: '', address: '', notes: '' };

export function SuppliersManager({ list }: { list: Supplier[] }) {
  const router = useRouter();
  const [draft, setDraft] = useState<typeof blank | null>(null);
  const [del, setDel] = useState<Supplier | null>(null);
  const [busy, setBusy] = useState(false);

  async function save() {
    if (!draft) return;
    setBusy(true);
    try { await api('/api/suppliers', 'POST', draft); toast.ok('Fornecedor cadastrado'); setDraft(null); router.refresh(); }
    catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }
  async function remove() {
    if (!del) return;
    setBusy(true);
    try { await api(`/api/suppliers/${del.id}`, 'DELETE'); toast.ok('Fornecedor removido'); setDel(null); router.refresh(); }
    catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }

  return (
    <>
      <div className="mb-4"><Button onClick={() => setDraft({ ...blank })}><Plus size={18} />Adicionar fornecedor</Button></div>
      {list.length === 0 ? <Card className="p-6 text-muted">Nenhum fornecedor ainda. Experimente cadastrar o site da Romafele: https://www.romafele.com.br</Card> : (
        <ul className="grid gap-3">
          {list.map((s) => (
            <li key={s.id}>
              <Card className="flex items-start gap-3 p-4">
                <span className="mt-1 text-pink-strong">{s.kind === 'site' ? <Globe size={20} /> : <Store size={20} />}</span>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="font-bold">{s.name}</p>
                    {s.kind === 'site' && (s.scraper ? <Badge tone="ok">Busca de preços ligada</Badge> : <Badge tone="neutral">Só cotação manual</Badge>)}
                    {s.last_error && <Badge tone="bad">Precisa de revisão</Badge>}
                  </div>
                  <p className="truncate text-sm text-muted">{s.kind === 'site' ? s.url : s.address || 'Loja física'}</p>
                  {s.last_error && <p role="alert" className="mt-1 text-sm text-bad">{s.last_error}</p>}
                  {s.last_ok_at && <p className="text-xs text-muted">Última leitura com sucesso: {dateBR(s.last_ok_at)}</p>}
                </div>
                <Button variant="danger" size="sm" aria-label={`Remover ${s.name}`} onClick={() => setDel(s)}><Trash2 size={16} /></Button>
              </Card>
            </li>
          ))}
        </ul>
      )}
      <Drawer open={!!draft} onClose={() => setDraft(null)} title="Novo fornecedor"
        footer={<><Button variant="ghost" onClick={() => setDraft(null)}>Cancelar</Button><Button busy={busy} onClick={save}>Salvar</Button></>}>
        {draft && (
          <div className="grid gap-4">
            <Field label="Nome">{(id) => <Input id={id} value={draft.name} maxLength={80} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />}</Field>
            <Field label="Tipo">{(id) => <Select id={id} value={draft.kind} onChange={(e) => setDraft({ ...draft, kind: e.target.value })}><option value="site">Site (com busca de preços)</option><option value="loja">Loja física (cotação à mão)</option></Select>}</Field>
            {draft.kind === 'site'
              ? <Field label="Endereço do site" hint="Só sites públicos, com https. Se for uma loja da plataforma Tray (como a Romafele), a busca de preços liga sozinha.">{(id) => <Input id={id} value={draft.url} placeholder="https://www.romafele.com.br" onChange={(e) => setDraft({ ...draft, url: e.target.value })} />}</Field>
              : <Field label="Endereço (opcional)">{(id) => <Input id={id} value={draft.address} maxLength={200} onChange={(e) => setDraft({ ...draft, address: e.target.value })} />}</Field>}
          </div>
        )}
      </Drawer>
      <ConfirmDialog open={!!del} title={`Remover “${del?.name}”?`} message="As cotações antigas deste fornecedor continuam no histórico, sem o nome dele." confirmLabel="Remover" busy={busy} onConfirm={remove} onClose={() => setDel(null)} />
    </>
  );
}
