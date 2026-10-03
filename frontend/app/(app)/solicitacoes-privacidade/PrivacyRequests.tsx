'use client';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { Badge } from '@/components/ui/Badge';
import { Button, Card, Textarea } from '@/components/ui/form';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import { dateBR } from '@/lib/format';
import { privacyLabel } from '@shared/lgpd';

export type Req = { id: number; type: string; details: string; status: 'aberta' | 'concluida'; answer: string; created_at: string; due_at: string; customer: string; email: string };

export function PrivacyRequests({ list }: { list: Req[] }) {
  const router = useRouter();
  const [open, setOpen] = useState<number | null>(null);
  const [answer, setAnswer] = useState('');
  const [busy, setBusy] = useState(false);

  async function done(id: number) {
    setBusy(true);
    try { await api(`/api/privacy-requests/${id}`, 'POST', { answer }); toast.ok('Resposta enviada ao cliente'); setOpen(null); setAnswer(''); router.refresh(); }
    catch (e: any) { toast.error(e.message); } finally { setBusy(false); }
  }
  if (!list.length) return <Card className="p-6 text-muted">Nenhuma solicitação até agora.</Card>;
  return (
    <ul className="space-y-3">
      {list.map((r) => {
        const late = r.status === 'aberta' && new Date(r.due_at).getTime() < Date.now();
        return (
          <li key={r.id}>
            <Card className="p-4">
              <div className="flex flex-wrap items-center gap-2">
                <p className="font-bold">{privacyLabel(r.type)}</p>
                <Badge tone={r.status === 'concluida' ? 'ok' : late ? 'bad' : 'warn'}>{r.status === 'concluida' ? 'Respondida' : late ? 'Prazo vencido' : `Responder até ${dateBR(r.due_at)}`}</Badge>
              </div>
              <p className="text-sm text-muted">{r.customer} · {r.email} · {dateBR(r.created_at)}</p>
              {r.details && <p className="mt-2 text-[15px]">{r.details}</p>}
              {r.answer && <p className="mt-2 text-[15px]"><b>Resposta:</b> {r.answer}</p>}
              {r.status === 'aberta' && (open === r.id ? (
                <div className="mt-3 grid gap-2">
                  <Textarea value={answer} onChange={(e) => setAnswer(e.target.value)} rows={3} maxLength={1000} placeholder="Escreva a resposta que o cliente vai ver" />
                  <div className="flex gap-2"><Button busy={busy} onClick={() => done(r.id)}>Concluir</Button><Button variant="ghost" onClick={() => setOpen(null)}>Cancelar</Button></div>
                </div>
              ) : <Button variant="soft" className="mt-3" onClick={() => { setOpen(r.id); setAnswer(''); }}>Responder</Button>)}
            </Card>
          </li>
        );
      })}
    </ul>
  );
}
