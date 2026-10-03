'use client';
import { Download, Trash2 } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { Button, Field, Input, Select, Textarea } from '@/components/ui/form';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';
import { dateBR } from '@/lib/format';

export type MyDataT = {
  email: string; name: string; phone: string; cep: string; street: string; number: string; complement: string; district: string; city: string;
  created_at: string; has_password: boolean; has_google: boolean; orders: number; open_orders: number;
  requests: { id: number; type: string; details: string; status: string; answer: string; created_at: string }[];
  request_types: { id: string; label: string }[]; deadline_days: number;
};

const card = 'mt-6 rounded-2xl border border-line bg-white p-5';
const maskCep = (v: string) => { const d = v.replace(/\D/g, '').slice(0, 8); return d.length > 5 ? `${d.slice(0, 5)}-${d.slice(5)}` : d; };

export function MyData({ data }: { data: MyDataT }) {
  const router = useRouter();
  const [f, setF] = useState({ name: data.name, phone: data.phone, cep: maskCep(data.cep), street: data.street, number: data.number, complement: data.complement, district: data.district, city: data.city });
  const [busy, setBusy] = useState('');
  const [reqType, setReqType] = useState(data.request_types[0]?.id ?? 'outro');
  const [reqText, setReqText] = useState('');
  const [confirm, setConfirm] = useState('');
  const [password, setPassword] = useState('');
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value });

  async function run(key: string, fn: () => Promise<void>) {
    setBusy(key);
    try { await fn(); } catch (e: any) { toast.error(e.message); } finally { setBusy(''); }
  }
  const save = () => run('save', async () => { await api('/api/cliente/me', 'PATCH', f); toast.ok('Dados atualizados'); router.refresh(); });
  const sendRequest = () => run('req', async () => { await api('/api/cliente/me/privacidade', 'POST', { type: reqType, details: reqText }); setReqText(''); toast.ok('Solicitação enviada'); router.refresh(); });
  const unlink = () => run('google', async () => { await api('/api/cliente/me/google', 'DELETE'); toast.ok('Google desvinculado'); router.refresh(); });
  const remove = () => run('del', async () => { await api('/api/cliente/me', 'DELETE', { confirm, password }); location.href = '/'; });

  return (
    <div>
      <section className={card} aria-labelledby="d-conta">
        <h2 id="d-conta" className="font-bold">O que guardamos sobre você</h2>
        <dl className="mt-3 grid gap-x-6 gap-y-1 text-[15px] sm:grid-cols-2">
          <div><dt className="inline text-muted">E-mail: </dt><dd className="inline">{data.email}</dd></div>
          <div><dt className="inline text-muted">Login: </dt><dd className="inline">{[data.has_password && 'e-mail e senha', data.has_google && 'Google'].filter(Boolean).join(' e ')}</dd></div>
          <div><dt className="inline text-muted">Conta criada em: </dt><dd className="inline">{dateBR(data.created_at)}</dd></div>
          <div><dt className="inline text-muted">Pedidos: </dt><dd className="inline">{data.orders}</dd></div>
        </dl>
        <p className="mt-3 text-sm text-muted">Também guardamos o histórico dos seus pedidos (itens, valores, datas e pagamento) e, se você pediu devolução, as fotos enviadas.</p>
        <a href="/api/cliente/me/exportar" download className="mt-4 inline-flex min-h-12 items-center gap-2 rounded-xl border-2 border-line bg-white px-4 font-semibold hover:border-pink">
          <Download size={17} />Baixar meus dados
        </a>
      </section>

      <section className={card} aria-labelledby="d-corrigir">
        <h2 id="d-corrigir" className="font-bold">Corrigir meus dados</h2>
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          <Field label="Nome">{(id) => <Input id={id} value={f.name} onChange={set('name')} maxLength={80} autoComplete="name" />}</Field>
          <Field label="Telefone">{(id) => <Input id={id} value={f.phone} onChange={set('phone')} inputMode="tel" autoComplete="tel" />}</Field>
          <Field label="CEP">{(id) => <Input id={id} value={f.cep} onChange={(e) => setF({ ...f, cep: maskCep(e.target.value) })} inputMode="numeric" />}</Field>
          <Field label="Rua">{(id) => <Input id={id} value={f.street} onChange={set('street')} />}</Field>
          <Field label="Número">{(id) => <Input id={id} value={f.number} onChange={set('number')} />}</Field>
          <Field label="Complemento">{(id) => <Input id={id} value={f.complement} onChange={set('complement')} />}</Field>
          <Field label="Bairro">{(id) => <Input id={id} value={f.district} onChange={set('district')} />}</Field>
          <Field label="Cidade">{(id) => <Input id={id} value={f.city} onChange={set('city')} />}</Field>
        </div>
        <p className="mt-2 text-xs text-muted">Para trocar o e-mail, faça uma solicitação abaixo, porque é com ele que você entra.</p>
        <Button className="mt-4 min-h-12 w-full sm:w-auto" busy={busy === 'save'} onClick={save}>Salvar</Button>
      </section>

      <section className={card} aria-labelledby="d-share">
        <h2 id="d-share" className="font-bold">Com quem compartilhamos</h2>
        <ul className="mt-2 list-disc space-y-1 pl-5 text-[15px] marker:text-pink">
          <li><b>Mercado Pago:</b> processa o pagamento por Pix (valor, seu e-mail e primeiro nome).</li>
          <li><b>Google:</b> login com a conta Google, só se você escolher essa opção (nome e e-mail), e as notificações do aplicativo (Firebase Cloud Messaging: identificador do aparelho e texto do aviso).</li>
          <li><b>ViaCEP:</b> consulta o CEP para preencher o endereço (só o CEP é enviado).</li>
          <li><b>Hospedagem:</b> provedores que mantêm o sistema no ar, com dever de confidencialidade.</li>
        </ul>
        <p className="mt-2 text-sm text-muted">Não vendemos seus dados. Veja a <a href="/privacidade" className="font-semibold text-pink-strong underline">Política de Privacidade</a>.</p>
        {data.has_google && (
          <div className="mt-4 rounded-xl bg-rk-claro p-3 text-sm">
            <p className="font-semibold">Login com Google</p>
            <p className="mt-1 text-muted">Você pode retirar a ligação da sua conta com o Google. {data.has_password ? 'Depois disso você entra só com e-mail e senha.' : 'Como esta conta não tem senha, a ligação só pode ser retirada se você excluir a conta ou fizer uma solicitação.'}</p>
            {data.has_password && <Button variant="soft" className="mt-2 min-h-11" busy={busy === 'google'} onClick={unlink}>Desvincular o Google</Button>}
            <p className="mt-2 text-muted">Para revogar também a permissão no Google: myaccount.google.com/permissions.</p>
          </div>
        )}
      </section>

      <section className={card} aria-labelledby="d-req">
        <h2 id="d-req" className="font-bold">Fazer uma solicitação sobre meus dados</h2>
        <p className="mt-1 text-sm text-muted">Anonimização, bloqueio ou eliminação de dados, revogação de consentimento ou qualquer outra dúvida. Respondemos em até {data.deadline_days} dias.</p>
        <div className="mt-3 grid gap-3">
          <Field label="Tipo">{(id) => <Select id={id} value={reqType} onChange={(e) => setReqType(e.target.value)}>{data.request_types.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}</Select>}</Field>
          <Field label="Detalhes">{(id) => <Textarea id={id} value={reqText} onChange={(e) => setReqText(e.target.value)} maxLength={1000} rows={3} />}</Field>
        </div>
        <Button className="mt-3 min-h-12 w-full sm:w-auto" busy={busy === 'req'} onClick={sendRequest}>Enviar solicitação</Button>
        {data.requests.length > 0 && (
          <ul className="mt-4 space-y-2 text-sm">
            {data.requests.map((r) => (
              <li key={r.id} className="rounded-xl border border-line p-3">
                <p className="font-semibold">{data.request_types.find((t) => t.id === r.type)?.label ?? r.type} · {dateBR(r.created_at)} · {r.status === 'aberta' ? 'em análise' : 'respondida'}</p>
                {r.details && <p className="text-muted">{r.details}</p>}
                {r.answer && <p className="mt-1">Resposta: {r.answer}</p>}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="mt-6 rounded-2xl border border-bad/30 bg-bad/5 p-5" aria-labelledby="d-del">
        <h2 id="d-del" className="font-bold text-bad">Excluir minha conta</h2>
        <p className="mt-1 text-sm">Apagamos seu nome, e-mail, telefone, endereço e login. O registro dos pedidos e pagamentos é mantido, sem identificar você, porque a lei exige. Não dá para desfazer.</p>
        {data.open_orders > 0
          ? <p className="mt-2 text-sm font-semibold">Você tem {data.open_orders} pedido(s) em andamento. Depois que forem entregues (ou cancelados), é só voltar aqui.</p>
          : (
            <div className="mt-3 grid gap-3 sm:max-w-sm">
              <Field label="Digite EXCLUIR para confirmar">{(id) => <Input id={id} value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="off" />}</Field>
              {data.has_password && <Field label="Sua senha">{(id) => <Input id={id} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />}</Field>}
              <Button variant="danger" className="min-h-12" busy={busy === 'del'} disabled={confirm.trim().toUpperCase() !== 'EXCLUIR'} onClick={remove}><Trash2 size={17} />Excluir minha conta</Button>
            </div>
          )}
      </section>
    </div>
  );
}
