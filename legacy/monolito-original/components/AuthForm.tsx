'use client';
import { AnimatePresence, motion } from 'motion/react';
import { Cake, CakeSlice, Candy, Cookie, Egg, Eye, EyeOff, IceCreamCone, Milk, Wheat } from 'lucide-react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { api } from '@/lib/client';
import { Logo } from './Logo';
import { ThemeToggle } from './ThemeToggle';
import { Button, Field, Input } from './ui/form';
import { toast } from './ui/Toast';

const FLOATERS = [
  { I: Cake, x: '8%', y: '27%', s: 54, r: '-10deg', d: '8s' },
  { I: Cookie, x: '70%', y: '10%', s: 44, r: '12deg', d: '6.5s' },
  { I: Candy, x: '82%', y: '46%', s: 50, r: '-6deg', d: '7.5s' },
  { I: Egg, x: '14%', y: '58%', s: 40, r: '8deg', d: '9s' },
  { I: IceCreamCone, x: '74%', y: '80%', s: 52, r: '-14deg', d: '7s' },
  { I: Milk, x: '84%', y: '66%', s: 38, r: '6deg', d: '8.5s' },
  { I: Wheat, x: '44%', y: '34%', s: 42, r: '-4deg', d: '10s' },
];

export function AuthForm({ mode }: { mode: 'login' | 'register' }) {
  const router = useRouter();
  const isLogin = mode === 'login';
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setError('');
    try {
      await api(`/api/auth/${mode}`, 'POST', { username, password });
      router.replace('/dashboard');
      router.refresh();
    } catch (err: any) { setError(err.message); setBusy(false); }
  }

  return (
    <div className="grid min-h-dvh lg:grid-cols-[1.05fr_1fr]">
      {/* Painel de marca */}
      <div className="relative hidden overflow-hidden bg-pink text-on-pink lg:block">
        <div className="absolute inset-0 opacity-[0.16]" style={{ backgroundImage: 'radial-gradient(circle at 1px 1px, currentColor 1.4px, transparent 0)', backgroundSize: '26px 26px' }} />
        {FLOATERS.map(({ I, x, y, s, r, d }, i) => (
          <span key={i} className="float absolute opacity-90" style={{ left: x, top: y, ['--r' as any]: r, ['--d' as any]: d, animationDelay: `${i * -1.1}s` }}><I size={s} strokeWidth={1.4} /></span>
        ))}
        <div className="relative flex h-full flex-col justify-between p-14">
          <Logo variant="compacta" tone="sobre-rosa" imgClassName="h-20" />
          <div>
            <h2 className="font-display max-w-md text-5xl font-semibold leading-[1.05]">Saiba quanto cobrar por cada doce.</h2>
            <p className="mt-5 max-w-sm text-lg opacity-90">Estoque, receitas, pedidos e metas num lugar só, com o preço calculado enquanto você digita.</p>
          </div>
        </div>
      </div>

      {/* Formulário */}
      <div className="relative flex flex-col items-center justify-center px-6 py-12">
        <div className="absolute right-5 top-5"><ThemeToggle /></div>
        <div className="mb-10 lg:hidden"><Logo imgClassName="h-10" /></div>
        <AnimatePresence mode="wait">
          <motion.form key={mode} onSubmit={submit} className="w-full max-w-sm"
            initial={{ opacity: 0, x: isLogin ? -24 : 24 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: isLogin ? 24 : -24 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}>
            <h1 className="font-display text-4xl font-semibold">{isLogin ? 'Bem-vinda de volta' : 'Crie sua conta'}</h1>
            <p className="mt-2 text-muted">{isLogin ? 'Entre para ver seu estoque e seus preços.' : 'Leva menos de um minuto. Só usuário e senha.'}</p>
            <div className="mt-8 flex flex-col gap-4">
              <Field label="Usuário">{(id) => <Input id={id} autoComplete="username" autoCapitalize="none" maxLength={32} required value={username} onChange={(e) => {
                const clean = e.target.value.replace(/[^A-Za-z0-9._-]/g, '');
                if (clean !== e.target.value) toast.warn('No usuário use só letras, números, ponto, hífen ou sublinhado (sem espaços).');
                setUsername(clean);
              }} />}</Field>
              <Field label="Senha" hint={isLogin ? undefined : 'Mínimo de 8 caracteres.'}>
                {(id) => (
                  <div className="relative">
                    <Input id={id} type={show ? 'text' : 'password'} autoComplete={isLogin ? 'current-password' : 'new-password'} required minLength={isLogin ? undefined : 8} value={password} onChange={(e) => setPassword(e.target.value)} className="pr-11" />
                    <button type="button" onClick={() => setShow((v) => !v)} aria-label={show ? 'Ocultar senha' : 'Mostrar senha'} className="absolute right-2 top-1/2 grid size-8 -translate-y-1/2 place-items-center rounded-lg text-muted hover:text-ink">{show ? <EyeOff size={18} /> : <Eye size={18} />}</button>
                  </div>
                )}
              </Field>
              <AnimatePresence>
                {error && <motion.p role="alert" initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }} exit={{ opacity: 0, height: 0 }} className="rounded-xl bg-bad/10 px-3.5 py-2.5 text-sm text-bad">{error}</motion.p>}
              </AnimatePresence>
              <Button type="submit" busy={busy} className="mt-1 w-full py-3">{isLogin ? 'Entrar' : 'Criar conta'}</Button>
            </div>
            <p className="mt-7 text-center text-sm text-muted">
              {isLogin ? 'Ainda não tem conta? ' : 'Já tem conta? '}
              <Link href={isLogin ? '/register' : '/login'} className="font-semibold text-pink-strong underline-offset-4 hover:underline">{isLogin ? 'Criar conta' : 'Entrar'}</Link>
            </p>
          </motion.form>
        </AnimatePresence>
      </div>
    </div>
  );
}
