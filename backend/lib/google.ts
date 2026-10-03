// Validação do id_token do Google (login pelo app mobile). O app manda o token; aqui conferimos com o Google.

export type GoogleClaims = { sub: string; email: string; name: string };

/** Confere as informações devolvidas pelo endpoint tokeninfo do Google. Puro (testável). */
export function checkTokenInfo(j: any, clientId: string, nowSec = Math.floor(Date.now() / 1000)): { claims?: GoogleClaims; error?: string } {
  if (!j || typeof j !== 'object' || j.error) return { error: 'O Google não reconheceu essa identificação.' };
  if (j.aud !== clientId) return { error: 'Identificação do Google emitida para outro aplicativo.' };
  if (j.iss !== 'accounts.google.com' && j.iss !== 'https://accounts.google.com') return { error: 'Identificação do Google inválida.' };
  if (Number(j.exp) < nowSec) return { error: 'A identificação do Google expirou. Tente entrar de novo.' };
  if (!(j.email_verified === true || j.email_verified === 'true')) return { error: 'O Google não confirmou o seu e-mail. Use outra conta ou entre com e-mail e senha.' };
  if (!j.sub || !j.email) return { error: 'O Google não devolveu os dados da conta.' };
  return { claims: { sub: String(j.sub), email: String(j.email).toLowerCase(), name: String(j.name ?? '').slice(0, 80) } };
}

export async function verifyGoogleIdToken(idToken: string, clientId: string) {
  const res = await fetch(`https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(idToken)}`, { signal: AbortSignal.timeout(8000) });
  return checkTokenInfo(await res.json().catch(() => null), clientId);
}
