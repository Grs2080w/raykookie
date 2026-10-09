'use client';
import { LogOut } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { Button } from '@/components/ui/form';
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
  return <Button variant="ghost" size="sm" busy={busy} onClick={out} className="min-h-10"><LogOut size={16} />Sair</Button>;
}
