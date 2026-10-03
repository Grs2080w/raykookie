'use client';
import { Eye, EyeOff } from 'lucide-react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import clsx from 'clsx';
import { Button, Card, Field, Input } from '@/components/ui/form';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';

const ERROS: Record<string, string> = {
  'google-indisponivel': 'O login com o Google ainda não está disponível. Entre com e-mail e senha.',
  'google-cancelado': 'Você cancelou o login com o Google. Se quiser, tente de novo.',
  'google-invalido': 'Não foi possível confirmar o login com o Google. Tente de novo.',
  'google-email': 'O Google não confirmou o seu e-mail. Use outra conta ou entre com e-mail e senha.',
  'google-falhou': 'Algo deu errado ao entrar com o Google. Tente de novo em instantes.',
};

function GoogleG() {
  return (
    <svg viewBox="0 0 48 48" className="size-5" aria-hidden>
      <path fill="#EA4335" d="M24 9.5c3.5 0 6.6 1.2 9.1 3.6l6.8-6.8C35.8 2.4 30.3 0 24 0 14.6 0 6.5 5.4 2.6 13.2l7.9 6.1C12.4 13.6 17.7 9.5 24 9.5Z" />
      <path fill="#4285F4" d="M46.5 24.5c0-1.6-.1-3.1-.4-4.5H24v9h12.7c-.6 3-2.3 5.5-4.8 7.2l7.5 5.8c4.4-4.1 7.1-10.1 7.1-17.5Z" />
      <path fill="#FBBC05" d="M10.5 28.7a14.5 14.5 0 0 1 0-9.4l-7.9-6.1a24 24 0 0 0 0 21.6l7.9-6.1Z" />
      <path fill="#34A853" d="M24 48c6.5 0 11.9-2.1 15.9-5.8l-7.5-5.8c-2.1 1.4-4.9 2.3-8.4 2.3-6.3 0-11.6-4.1-13.5-9.8l-7.9 6.1C6.5 42.6 14.6 48 24 48Z" />
    </svg>
  );
}

export function CustomerAuth({ next, erro, googleEnabled }: { next: string; erro?: string; googleEnabled: boolean }) {
  const router = useRouter();
  const [mode, setMode] = useState<'entrar' | 'criar'>('entrar');
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const isNew = mode === 'criar';

  useEffect(() => { if (erro && ERROS[erro]) toast.error(ERROS[erro]); }, [erro]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await api(isNew ? '/api/cliente/cadastro' : '/api/cliente/login', 'POST', isNew ? { name, email, password } : { email, password });
      router.replace(next); router.refresh();
    } catch (err: any) { toast.error(err.message); setBusy(false); }
  }

  return (
    <div className="mx-auto max-w-md">
      <h1 className="font-display text-3xl font-semibold leading-tight">{isNew ? 'Crie sua conta' : 'Entre para fazer seu pedido'}</h1>
      <p className="mt-2 text-muted">{isNew ? 'Leva menos de um minuto. Depois você acompanha todos os seus pedidos aqui.' : 'Assim você acompanha seus pedidos e pede de novo sem digitar tudo outra vez.'}</p>

      <Card className="mt-6 p-5">
        {googleEnabled ? (
          <a href={`/api/cliente/google?next=${encodeURIComponent(next)}`} className="flex min-h-12 items-center justify-center gap-3 rounded-2xl border-2 border-line bg-white font-bold transition hover:border-muted/50 active:scale-[0.98]"><GoogleG />Continuar com o Google</a>
        ) : (
          <button type="button" onClick={() => toast.warn(ERROS['google-indisponivel'])} className="flex min-h-12 w-full items-center justify-center gap-3 rounded-2xl border-2 border-line bg-white font-bold transition hover:border-muted/50 active:scale-[0.98]"><GoogleG />Continuar com o Google</button>
        )}
        <div className="my-5 flex items-center gap-3 text-sm text-muted"><span className="h-px flex-1 bg-line" />ou com e-mail<span className="h-px flex-1 bg-line" /></div>

        <div role="tablist" className="mb-5 grid grid-cols-2 rounded-2xl bg-surface-2 p-1">
          {(['entrar', 'criar'] as const).map((m) => (
            <button key={m} type="button" role="tab" aria-selected={mode === m} onClick={() => setMode(m)}
              className={clsx('min-h-11 rounded-xl text-[15px] font-bold transition', mode === m ? 'bg-white shadow-card' : 'text-muted')}>{m === 'entrar' ? 'Já tenho conta' : 'Criar conta'}</button>
          ))}
        </div>

        <form onSubmit={submit} noValidate className="flex flex-col gap-4">
          {isNew && <Field label="Seu nome">{(id) => <Input id={id} autoComplete="name" maxLength={80} required value={name} onChange={(e) => setName(e.target.value)} />}</Field>}
          <Field label="E-mail">{(id) => <Input id={id} type="email" inputMode="email" autoComplete="email" autoCapitalize="none" maxLength={120} required value={email} onChange={(e) => setEmail(e.target.value)} />}</Field>
          <Field label="Senha" hint={isNew ? 'Mínimo de 8 caracteres.' : undefined}>
            {(id) => (
              <div className="relative">
                <Input id={id} type={show ? 'text' : 'password'} autoComplete={isNew ? 'new-password' : 'current-password'} required minLength={isNew ? 8 : undefined} maxLength={200} value={password} onChange={(e) => setPassword(e.target.value)} className="pr-12" />
                <button type="button" onClick={() => setShow((v) => !v)} aria-label={show ? 'Ocultar senha' : 'Mostrar senha'} className="absolute right-1 top-1/2 grid size-11 -translate-y-1/2 place-items-center rounded-lg text-muted hover:text-ink">{show ? <EyeOff size={18} /> : <Eye size={18} />}</button>
              </div>
            )}
          </Field>
          <Button type="submit" busy={busy} className="min-h-12 w-full text-base">{isNew ? 'Criar conta e continuar' : 'Entrar'}</Button>
          <p className="text-center text-xs leading-relaxed text-muted">
            {isNew ? 'Ao criar sua conta' : 'Ao entrar'}, você concorda com os{' '}
            <Link href="/termos" className="font-semibold text-pink-strong underline underline-offset-2">Termos de Serviço</Link> e com a{' '}
            <Link href="/privacidade" className="font-semibold text-pink-strong underline underline-offset-2">Política de Privacidade</Link>.
          </p>
        </form>
      </Card>
    </div>
  );
}
