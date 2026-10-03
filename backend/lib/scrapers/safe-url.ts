// O servidor busca preços em endereços que a confeiteira cadastra. Para isso não virar uma porta para a rede interna
// (SSRF), só aceita https para sites públicos: nada de localhost, IP, rede privada ou endereços internos.
import dns from 'node:dns/promises';
import net from 'node:net';

export class UnsafeUrl extends Error {}

const privateV4 = (ip: string) => {
  const [a, b] = ip.split('.').map(Number);
  return a === 10 || a === 127 || a === 0 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127) || a >= 224;
};
const privateV6 = (ip: string) => /^(::1?$|f[cd]|fe[89ab]|::ffff:)/i.test(ip);

/** Sintaxe: https, com nome de domínio (não IP, não localhost, não .local/.internal). */
export function parsePublicUrl(raw: string): URL {
  let u: URL;
  try { u = new URL(/^https?:\/\//i.test(raw.trim()) ? raw.trim() : `https://${raw.trim()}`); } catch { throw new UnsafeUrl('Endereço inválido.'); }
  if (u.protocol !== 'https:') throw new UnsafeUrl('Use o endereço do site com https.');
  const h = u.hostname.toLowerCase();
  if (u.username || u.password) throw new UnsafeUrl('Endereço inválido.');
  if (net.isIP(h) || h === 'localhost' || !h.includes('.') || /\.(local|internal|localhost|lan|home|corp)$/.test(h)) throw new UnsafeUrl('Use o endereço de um site público.');
  return u;
}

/** Confere também o IP para onde o nome aponta (um domínio pode apontar para a rede interna). */
export async function assertPublicUrl(raw: string): Promise<URL> {
  const u = parsePublicUrl(raw);
  const addrs = await dns.lookup(u.hostname, { all: true }).catch(() => { throw new UnsafeUrl('Não encontrei esse site.'); });
  if (!addrs.length || addrs.some((a) => (a.family === 4 ? privateV4(a.address) : privateV6(a.address)))) throw new UnsafeUrl('Use o endereço de um site público.');
  return u;
}
