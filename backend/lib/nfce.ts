// Leitura de NFC-e (cupom fiscal eletrônico): interpreta o HTML da página de consulta aberta pelo QR code
// e casa cada item com o estoque. Puro (sem banco nem rede): o app baixa a página e manda o HTML pra cá.
import { createHash } from 'node:crypto';

export type NfceItem = { name: string; code: string; qty: number; unit: string; unit_price: number; total: number };
export type NfceParsed = { key: string; store: string; date: string | null; total: number | null; items: NfceItem[] };

// ---- texto ----------------------------------------------------------------------------------------
const ENTITIES: Record<string, string> = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', ccedil: 'ç', Ccedil: 'Ç', atilde: 'ã', Atilde: 'Ã', otilde: 'õ', Otilde: 'Õ',
  aacute: 'á', Aacute: 'Á', eacute: 'é', Eacute: 'É', iacute: 'í', Iacute: 'Í', oacute: 'ó', Oacute: 'Ó', uacute: 'ú', Uacute: 'Ú',
  acirc: 'â', Acirc: 'Â', ecirc: 'ê', Ecirc: 'Ê', ocirc: 'ô', Ocirc: 'Ô', agrave: 'à', Agrave: 'À', uuml: 'ü', ordm: 'º', ordf: 'ª', deg: '°',
};
export function decodeEntities(s: string) {
  return s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, e: string) => {
    if (e[0] === '#') {
      const n = e[1].toLowerCase() === 'x' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
      return Number.isFinite(n) && n > 0 && n < 0x110000 ? String.fromCodePoint(n) : m;
    }
    return ENTITIES[e] ?? m;
  });
}
const squash = (s: string) => decodeEntities(s).replace(/\s+/g, ' ').trim();
const stripTags = (html: string) => squash(html.replace(/<[^>]*>/g, ' '));

/** "1.234,56" / "4,9900" / "4.99" -> número. As notas sempre usam vírgula decimal; ponto só aparece como milhar ou decimal simples. */
export function brNumber(v: string): number {
  const s = String(v ?? '').replace(/[^\d.,-]/g, '');
  if (!s) return NaN;
  if (s.includes(',')) return Number(s.replace(/\./g, '').replace(',', '.'));
  const dots = s.split('.').length - 1;
  return dots > 1 ? Number(s.replace(/\./g, '')) : Number(s);
}
const round = (n: number, d: number) => Math.round(n * 10 ** d) / 10 ** d;

// ---- chave de acesso ------------------------------------------------------------------------------
/** Os 44 dígitos da chave da nota, que vêm dentro do link do QR code (`?p=CHAVE|2|1|1|hash`). */
export function accessKeyFromText(text: string): string | null {
  let t = text;
  try { t = decodeURIComponent(text); } catch { /* link com % solto: usa como veio */ }
  return /(?<!\d)\d{44}(?!\d)/.exec(t)?.[0] ?? null;
}

/** Só aceita link http(s) de portal do governo (*.gov.br): é o que os QR codes de nota fiscal trazem. */
export function isNfceUrl(raw: string): boolean {
  try {
    const u = new URL(raw);
    return (u.protocol === 'https:' || u.protocol === 'http:') && (u.hostname === 'gov.br' || u.hostname.endsWith('.gov.br'));
  } catch {
    return false;
  }
}

// ---- interpretação da página ----------------------------------------------------------------------
const span = (html: string, cls: string) => new RegExp(`<(?:span|div|td|label)[^>]*class=["'][^"']*\\b${cls}\\b[^"']*["'][^>]*>([\\s\\S]*?)</(?:span|div|td|label)>`, 'i').exec(html)?.[1];

function itemsFromPortal(html: string): NfceItem[] {
  // Layout padrão do "Consulta NFC-e" (tabela #tabResult; usado por SP, PR, MS e vários outros estados).
  const rows = html.match(/<tr[^>]*>[\s\S]*?<\/tr>/gi) ?? [];
  const out: NfceItem[] = [];
  for (const row of rows) {
    const name = span(row, 'txtTit2?');
    if (!name) continue;
    const qty = brNumber(stripTags(span(row, 'Rqtd') ?? '').replace(/^.*?qtde\.?:?/i, ''));
    const unit = stripTags(span(row, 'RUN') ?? '').replace(/^.*?UN:?/i, '').trim().toUpperCase();
    const unitPrice = brNumber(stripTags(span(row, 'RvlUnit') ?? '').replace(/^.*?unit\.?:?/i, ''));
    const total = brNumber(stripTags(span(row, 'valor') ?? ''));
    const code = /\(?\s*c[oó]digo:?\s*([^)<\s]+)/i.exec(stripTags(span(row, 'RCod') ?? ''))?.[1] ?? '';
    if (![qty, total].every(Number.isFinite) || qty <= 0) continue;
    out.push({ name: squash(name.replace(/<[^>]*>/g, ' ')), code, qty, unit: unit || 'UN', unit_price: Number.isFinite(unitPrice) ? unitPrice : round(total / qty, 4), total });
  }
  return out;
}

function itemsFromText(html: string): NfceItem[] {
  // Plano B para layouts diferentes: procura o padrão "NOME (Código: 1) Qtde.: 1 UN: UN Vl. Unit.: 4,99 Vl. Total 4,99" no texto corrido.
  const text = decodeEntities(html.replace(/<(script|style)[\s\S]*?<\/\1>/gi, ' ').replace(/<\/?(tr|li|p|div|br|table|tbody|h\d)[^>]*>/gi, '\n').replace(/<[^>]*>/g, ' ')).replace(/[ \t ]+/g, ' ');
  const out: NfceItem[] = [];
  const re = /([^\n]{2,120}?)\s*(?:\(\s*c[oó]digo:?\s*([^)\s]+)\s*\))?\s*qtde\.?:?\s*([\d.,]+)\s*(?:un\.?:?\s*)?([a-z]{1,4})?\s*vl\.?\s*unit\.?:?\s*(?:r\$)?\s*([\d.,]+)\s*(?:vl\.?\s*total:?\s*)?(?:r\$)?\s*([\d.,]+)/gi;
  for (const m of text.matchAll(re)) {
    const qty = brNumber(m[3]), unitPrice = brNumber(m[5]), total = brNumber(m[6]);
    const name = squash(m[1]).replace(/^(item|\d+)\s+/i, '');
    if (!name || !Number.isFinite(qty) || qty <= 0 || !Number.isFinite(total)) continue;
    out.push({ name, code: m[2] ?? '', qty, unit: (m[4] ?? 'UN').toUpperCase(), unit_price: Number.isFinite(unitPrice) ? unitPrice : round(total / qty, 4), total });
  }
  return out;
}

function storeName(html: string): string {
  const s = span(html, 'txtTopo') ?? span(html, 'txtTit') ?? '';
  return stripTags(s).slice(0, 80);
}
function issueDate(html: string): string | null {
  const m = /emiss[aã]o:?[^0-9]{0,40}(\d{2})\/(\d{2})\/(\d{4})/i.exec(stripTags(html)) ?? /(\d{2})\/(\d{2})\/(\d{4})\s+\d{2}:\d{2}/.exec(stripTags(html));
  return m ? `${m[3]}-${m[2]}-${m[1]}` : null;
}
function grandTotal(html: string): number | null {
  const t = stripTags(html);
  const m = /valor\s+a\s+pagar\s*(?:r\$)?\s*:?\s*([\d.,]+)/i.exec(t) ?? /valor\s+total\s*(?:r\$)?\s*:?\s*([\d.,]+)/i.exec(t);
  const n = m ? brNumber(m[1]) : NaN;
  return Number.isFinite(n) ? n : null;
}

/** Junta itens repetidos da mesma nota (mesmo produto bipado duas vezes) e descarta lixo. */
function mergeItems(items: NfceItem[]): NfceItem[] {
  const map = new Map<string, NfceItem>();
  for (const it of items) {
    if (!it.name || it.name.length > 160) continue;
    const k = `${it.code}|${it.name.toLowerCase()}|${it.unit}|${it.unit_price}`;
    const prev = map.get(k);
    if (prev) { prev.qty = round(prev.qty + it.qty, 4); prev.total = round(prev.total + it.total, 2); } else map.set(k, { ...it });
  }
  return [...map.values()];
}

export function parseNfceHtml(html: string, url = ''): NfceParsed | null {
  const page = html.length > 3_000_000 ? html.slice(0, 3_000_000) : html;
  let items = itemsFromPortal(page);
  if (items.length === 0) items = itemsFromText(page);
  items = mergeItems(items);
  if (items.length === 0) return null;
  const key = accessKeyFromText(url) ?? accessKeyFromText(stripTags(page).replace(/(?<=\d)\s+(?=\d)/g, ''))
    ?? `h:${createHash('sha256').update(JSON.stringify([storeName(page), issueDate(page), items.map((i) => [i.name, i.qty, i.total])])).digest('hex').slice(0, 40)}`;
  return { key, store: storeName(page), date: issueDate(page), total: grandTotal(page), items };
}

// ---- casamento com o estoque ----------------------------------------------------------------------
const STOP = new Set(['de', 'da', 'do', 'das', 'dos', 'e', 'com', 'sem', 'em', 'para', 'tipo', 'tp', 'un', 'und', 'unid', 'cx', 'pct', 'pc', 'pac', 'sc', 'kg', 'g', 'gr', 'ml', 'l', 'lt', 'litro', 'lata', 'pote', 'sache', 'sch']);
const ABBR: Record<string, string> = { cond: 'condensado', choc: 'chocolate', mant: 'manteiga', marg: 'margarina', ac: 'acucar', refin: 'refinado', cr: 'creme', leit: 'leite', farin: 'farinha', trig: 'trigo', integr: 'integral', desn: 'desnatado', semi: 'semi', mor: 'morango', bau: 'baunilha', ess: 'essencia', fermen: 'fermento', gran: 'granulado', confeit: 'confeiteiro', am: 'amendoa' };

export const plain = (s: string) => s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
export function nameTokens(name: string): string[] {
  return plain(name).replace(/(\d+)[.,](\d+)/g, '$1$2').split(/[^a-z0-9]+/).filter((t) => t && !/^\d+(g|gr|kg|ml|l|lt|un|und)?$/.test(t) && !STOP.has(t)).map((t) => ABBR[t] ?? t);
}
const same = (a: string, b: string) => a === b || (Math.min(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a)));

type Ing = { id: number; name: string; unit: string; package_size: number; package_cost: number };
export type Match = { ingredient_id: number; name: string; exact: boolean };

/** Acha o ingrediente do estoque que corresponde ao item da nota. `exact` = todas as palavras do ingrediente aparecem no item
 *  (com 2+ palavras no nome); senão é só uma sugestão, que o app pergunta antes de somar (e lembra a resposta). */
export function matchIngredient(itemName: string, list: Ing[]): Match | null {
  const nt = nameTokens(itemName);
  if (nt.length === 0) return null;
  let best: { ing: Ing; hit: number; total: number; score: number } | null = null;
  for (const ing of list) {
    const it = nameTokens(ing.name);
    if (it.length === 0) continue;
    const hit = it.filter((t) => nt.some((n) => same(n, t))).length;
    if (hit === 0) continue;
    const score = (hit / it.length) * 0.75 + (hit / nt.length) * 0.25 + hit * 0.01; // mais palavras em comum desempata
    if (!best || score > best.score) best = { ing, hit, total: it.length, score };
  }
  if (!best) return null;
  const full = best.hit === best.total;
  // Ingrediente de uma palavra só ("Leite") só soma direto se o item da nota for essa palavra e nada mais; "CREME DE LEITE" ou
  // "LEITE CONDENSADO" viram pergunta. Metade das palavras ou menos não serve nem de sugestão ("Creme de leite" x "Leite condensado").
  const exact = full && (best.total >= 2 || nt.length === 1);
  return full || best.hit / best.total > 0.5 ? { ingredient_id: best.ing.id, name: best.ing.name, exact } : null;
}

// ---- quantidades ----------------------------------------------------------------------------------
/** Tamanho escrito no nome ("395G", "1,5 KG", "200ML", "1L") -> { unit: 'g'|'ml', size }. */
export function sizeInName(name: string): { unit: 'g' | 'ml'; size: number } | null {
  const m = /(?<![a-z\d])(\d+(?:[.,]\d+)?)\s*(kg|g|gr|ml|l|lt)(?![a-z])/i.exec(plain(name));
  if (!m) return null;
  const v = brNumber(m[1]);
  if (!Number.isFinite(v) || v <= 0) return null;
  const u = m[2].toLowerCase();
  return u === 'kg' ? { unit: 'g', size: v * 1000 } : u === 'l' || u === 'lt' ? { unit: 'ml', size: v * 1000 } : { unit: u === 'ml' ? 'ml' : 'g', size: v };
}

/** Quanto entra no estoque (em embalagens) e o preço da embalagem, olhando a unidade da nota e o cadastro do ingrediente. */
export function planAdd(item: NfceItem, ing: Pick<Ing, 'unit' | 'package_size' | 'package_cost'>): { add_qty: number; package_cost: number; note: string } {
  const u = item.unit.toUpperCase();
  const bulk = u === 'KG' ? { unit: 'g', f: 1000 } : u === 'G' || u === 'GR' ? { unit: 'g', f: 1 } : u === 'L' || u === 'LT' ? { unit: 'ml', f: 1000 } : u === 'ML' ? { unit: 'ml', f: 1 } : null;
  const size = ing.package_size > 0 ? ing.package_size : 1;
  // vendido a granel (kg/L) e o estoque é em g/ml: converte para embalagens do cadastro
  if (bulk && bulk.unit === ing.unit) {
    const amount = item.qty * bulk.f;
    return { add_qty: round(amount / size, 3), package_cost: round((item.unit_price / bulk.f) * size, 2), note: `${round(amount, 3)} ${ing.unit} ≈ ${round(amount / size, 3)} embalagem(ns)` };
  }
  // vendido por embalagem, com o tamanho escrito no nome: ajusta se for diferente do cadastro
  const named = sizeInName(item.name);
  if (named && named.unit === ing.unit && Math.abs(named.size - size) > 0.001) {
    const packs = (item.qty * named.size) / size;
    return { add_qty: round(packs, 3), package_cost: round((item.unit_price / named.size) * size, 2), note: `${item.qty} × ${named.size} ${ing.unit} = ${round(packs, 3)} embalagem(ns) de ${size} ${ing.unit}` };
  }
  return { add_qty: round(item.qty, 3), package_cost: round(item.unit_price, 2), note: `${round(item.qty, 3)} embalagem(ns)` };
}

/** Sugestão de cadastro para um item que ainda não está no estoque. */
export function planCreate(item: NfceItem): { name: string; unit: 'g' | 'ml' | 'un'; package_size: number; package_cost: number; stock_qty: number } {
  const u = item.unit.toUpperCase();
  const named = sizeInName(item.name);
  const base = squash(item.name).toLowerCase().replace(/^./, (c) => c.toUpperCase()).slice(0, 80);
  if (u === 'KG' || u === 'L' || u === 'LT') return { name: base, unit: u === 'KG' ? 'g' : 'ml', package_size: 1000, package_cost: round(item.unit_price, 2), stock_qty: round(item.qty, 3) };
  if (named) return { name: base, unit: named.unit, package_size: named.size, package_cost: round(item.unit_price, 2), stock_qty: round(item.qty, 3) };
  return { name: base, unit: 'un', package_size: 1, package_cost: round(item.unit_price, 2), stock_qty: round(item.qty, 3) };
}
