'use client';
import { LogOut } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { toast } from '@/components/ui/Toast';
import { api } from '@/lib/client';

export function LogoutButton() {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  async function out() {
    setBusy(true);
    try { await api('/api/cliente/logout', 'POST'); router.replace('/'); router.refresh(); }
    catch (e: any) { toast.error(e.message); setBusy(false); }
  }
  // botão do cabeçalho: o estilo vem de .rk-header__links (mesmo visual dos links ao lado)
  return <button type="button" disabled={busy} onClick={out}><LogOut size={16} aria-hidden style={{ marginRight: 'var(--s-2)' }} />Sair</button>;
}
