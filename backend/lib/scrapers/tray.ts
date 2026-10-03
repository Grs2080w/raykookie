// Lojas virtuais da plataforma Tray (ex.: Romafele). A busca devolve HTML já pronto, com nome, preço e link de cada produto
// em atributos data-ga4-*, então basta ler a página: sem navegador. Respeita o robots.txt (só busca de produtos).
import { parsePack } from '@shared/pack';
import type { Unit } from '@shared/pricing';
import { assertPublicUrl } from './safe-url';

export type ScrapedProduct = { name: string; url: string; price: number; available: boolean; size: number | null; unit: Unit | null };

const decode = (s: string) => s
  .replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#0?39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&nbsp;/g, ' ')
  .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)));

/** Lê os produtos da página de busca. Blocos sem nome ou sem preço são ignorados. */
export function parseTraySearch(html: string): ScrapedProduct[] {
  const out: ScrapedProduct[] = [];
  const seen = new Set<string>();
  for (const block of html.split(/<div class="product\b/).slice(1)) {
    const head = block.slice(0, 6000);
    const name = decode((head.match(/data-ga4-name="([^"]*)"/)?.[1] ?? '').replace(/\s+/g, ' ').trim());
    const price = Number((head.match(/data-ga4-price="([\d.]+)"/)?.[1] ?? ''));
    const url = head.match(/class="product-info"\s+href="([^"]+)"/)?.[1] ?? head.match(/<a href="([^"]+)" class="space-image"/)?.[1] ?? '';
    if (!name || !(price > 0) || !url || seen.has(url)) continue;
    seen.add(url);
    const pack = parsePack(name);
    out.push({ name, url, price, available: !/indispon[ií]vel|esgotado/i.test(head), size: pack?.size ?? null, unit: pack?.unit ?? null });
  }
  return out;
}

const norm = (s: string) => s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

/** Fica só com o que tem todas as palavras buscadas (a busca da loja traz itens de outras áreas). */
export function matchesQuery(name: string, term: string): boolean {
  const n = norm(name);
  return norm(term).split(/\s+/).filter((w) => w.length > 1).every((w) => n.includes(w.replace(/s$/, '')));
}

// Educação com o site: 1 pedido por segundo por loja e cache de 12 h por busca.
const cache = new Map<string, { at: number; list: ScrapedProduct[] }>();
const last = new Map<string, number>();
const TTL = 12 * 3600_000;

export async function fetchHtml(url: string): Promise<string> {
  const host = new URL(url).host;
  const wait = (last.get(host) ?? 0) + 1000 - Date.now();
  if (wait > 0) await new Promise((r) => setTimeout(r, wait));
  last.set(host, Date.now());
  let res: Response | undefined;
  let target = url;
  for (let hop = 0; hop < 3; hop++) { // redirecionamentos conferidos um a um (não segue para a rede interna)
    await assertPublicUrl(target);
    res = await fetch(target, { headers: { 'User-Agent': 'RaykookieBot/1.0 (comparador de precos da propria confeitaria)', Accept: 'text/html' }, signal: AbortSignal.timeout(15_000), redirect: 'manual' });
    const loc = res.status >= 300 && res.status < 400 ? res.headers.get('location') : null;
    if (!loc) break;
    target = new URL(loc, target).toString();
  }
  if (!res || !res.ok) throw new Error(`O site respondeu ${res?.status ?? 'sem resposta'}`);
  const buf = Buffer.from(await res.arrayBuffer());
  const charset = /charset=([\w-]+)/i.exec(res.headers.get('content-type') ?? '')?.[1] ?? /<meta[^>]+charset=["']?([\w-]+)/i.exec(buf.subarray(0, 4000).toString('latin1'))?.[1] ?? 'utf-8';
  return new TextDecoder(/iso-8859-1|latin1/i.test(charset) ? 'iso-8859-1' : 'utf-8').decode(buf);
}

/** Busca `term` na loja `baseUrl` (ex.: https://www.romafele.com.br). */
export async function traySearch(baseUrl: string, term: string): Promise<ScrapedProduct[]> {
  const base = baseUrl.replace(/\/+$/, '');
  const key = `${base}|${norm(term)}`;
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < TTL) return hit.list;
  const html = await fetchHtml(`${base}/loja/busca.php?palavra_busca=${encodeURIComponent(term)}`);
  const all = parseTraySearch(html);
  if (!all.length && !/Busca na|palavra_busca|class="product/.test(html)) throw new Error('A página não parece ser de uma loja Tray (o site pode ter mudado).');
  const list = all.filter((p) => matchesQuery(p.name, term));
  cache.set(key, { at: Date.now(), list });
  return list;
}

/** Descobre se um endereço é de uma loja Tray (o rodapé/head traz `data-tray-theme`). */
export async function detectScraper(url: string): Promise<'tray' | null> {
  try { return /data-tray-theme|Tray Tecnologia/i.test(await fetchHtml(url)) ? 'tray' : null; } catch { return null; }
}

export function _clearScraperCache() { cache.clear(); last.clear(); }
