import { NextResponse } from 'next/server';
import { createCustomerSession, upsertGoogleCustomer } from '@/lib/customer-auth';
import { safeNext } from '@shared/shop';
import { siteUrl } from '@/lib/site';

// Passo 2: o Google devolve `code` + `state`. Conferimos o state, trocamos o code por um token e lemos o perfil.
export async function GET(req: Request) {
  const fail = (code: string) => { const r = NextResponse.redirect(siteUrl(`/cliente/entrar?erro=${code}`)); r.cookies.delete('rk_oauth'); return r; };
  const params = new URL(req.url).searchParams;
  const code = params.get('code');
  if (params.get('error')) return fail('google-cancelado');
  let saved: { state?: string; next?: string } = {};
  try { saved = JSON.parse(decodeURIComponent(req.headers.get('cookie')?.match(/(?:^|;\s*)rk_oauth=([^;]+)/)?.[1] ?? '{}')); } catch {}
  if (!code || !saved.state || saved.state !== params.get('state')) return fail('google-invalido');
  const { GOOGLE_CLIENT_ID: id, GOOGLE_CLIENT_SECRET: secret } = process.env;
  if (!id || !secret) return fail('google-indisponivel');
  try {
    const tokenRes = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ code, client_id: id, client_secret: secret, redirect_uri: siteUrl('/api/cliente/google/callback'), grant_type: 'authorization_code' }),
    });
    const token = await tokenRes.json();
    if (!tokenRes.ok || !token.access_token) throw new Error(`token: ${token.error ?? tokenRes.status}`);
    const infoRes = await fetch('https://openidconnect.googleapis.com/v1/userinfo', { headers: { authorization: `Bearer ${token.access_token}` } });
    const info = await infoRes.json();
    if (!infoRes.ok || !info.sub || !info.email) throw new Error('userinfo');
    if (info.email_verified === false) return fail('google-email');
    const customer = await upsertGoogleCustomer({ sub: String(info.sub), email: String(info.email).toLowerCase(), name: String(info.name ?? '').slice(0, 80) });
    await createCustomerSession(customer.id);
    const res = NextResponse.redirect(siteUrl(safeNext(saved.next)));
    res.cookies.delete('rk_oauth');
    return res;
  } catch (e) {
    console.error('google oauth', e);
    return fail('google-falhou');
  }
}
