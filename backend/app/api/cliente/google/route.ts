import crypto from 'node:crypto';
import { NextResponse } from 'next/server';
import { safeNext } from '@shared/shop';
import { siteUrl } from '@/lib/site';

// Passo 1 do "Entrar com o Google": manda a pessoa para a tela de login do Google.
// Precisa de GOOGLE_CLIENT_ID e GOOGLE_CLIENT_SECRET no .env; a URI de redirecionamento cadastrada no Google é
//   {SITE_URL}/api/cliente/google/callback
export async function GET(req: Request) {
  const clientId = process.env.GOOGLE_CLIENT_ID;
  if (!clientId || !process.env.GOOGLE_CLIENT_SECRET) return NextResponse.redirect(siteUrl('/cliente/entrar?erro=google-indisponivel'));
  const next = safeNext(new URL(req.url).searchParams.get('next'));
  const state = crypto.randomBytes(24).toString('base64url');
  const url = new URL('https://accounts.google.com/o/oauth2/v2/auth');
  url.search = new URLSearchParams({
    client_id: clientId, redirect_uri: siteUrl('/api/cliente/google/callback'), response_type: 'code',
    scope: 'openid email profile', state, prompt: 'select_account',
  }).toString();
  const res = NextResponse.redirect(url);
  res.cookies.set('rk_oauth', JSON.stringify({ state, next }), { httpOnly: true, sameSite: 'lax', path: '/api/cliente/google', maxAge: 600, secure: process.env.COOKIE_SECURE === 'true' });
  return res;
}
