'use client';
import { referrerKind, type EngagementEvent, type EngagementName, type EngagementProps } from '@shared/engagement';

/*
 * Medição anônima do site (docs/superpowers/plans/2026-10-13-engajamento-clientes.md). Só existe um código aleatório
 * de visita (`sessionStorage`: some ao fechar a aba, não liga a conta nem a aparelho); não há cookie, conta, IP, código
 * fixo nem texto digitado. Desligada por padrão (`NEXT_PUBLIC_ENGAGEMENT_ENABLED=true` liga), nos previews da Vercel e
 * para quem recusou ("Não medir"), usa Do Not Track ou Global Privacy Control.
 */

const ENDPOINT = '/api/medicao/eventos';
const MAX_BATCH = 20;
const FLUSH_MS = 5000;
const IDLE_MS = 30 * 60 * 1000;
const CLICK_GAP_MS = 3000;
const OPT_OUT_KEY = 'rk_no_track';
const SESSION_KEY = 'rk_sid';

let queue: EngagementEvent[] = [];
let timer: ReturnType<typeof setTimeout> | undefined;
let wired = false;
let memorySession: { id: string; t: number } | null = null;
const onceKeys = new Set<string>();
const lastSeen = new Map<string, number>();

/** A medição está ligada no build (a chave de produção só é ligada depois da política nova). */
export function trackingConfigured(): boolean {
  return process.env.NEXT_PUBLIC_ENGAGEMENT_ENABLED === 'true';
}

/** A pessoa pediu para não medir (ou o navegador pede por Do Not Track / Global Privacy Control). */
export function isOptedOut(): boolean {
  if (typeof window === 'undefined') return true;
  try { if (localStorage.getItem(OPT_OUT_KEY) === '1') return true; } catch { /* sem armazenamento: segue */ }
  const nav = navigator as Navigator & { globalPrivacyControl?: boolean };
  return nav.doNotTrack === '1' || nav.globalPrivacyControl === true;
}

/** Guarda a escolha "não medir". Ao recusar, o que estava na fila é descartado. */
export function setOptOut(value: boolean): void {
  try { if (value) localStorage.setItem(OPT_OUT_KEY, '1'); else localStorage.removeItem(OPT_OUT_KEY); } catch { /* sem armazenamento */ }
  if (value) queue = [];
}

function enabled(): boolean {
  return typeof window !== 'undefined' && trackingConfigured() && !/\.vercel\.app$/.test(location.hostname) && !isOptedOut();
}

function uuid(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/** Código da visita atual; uma visita nova começa depois de 30 minutos parado. `fresh` diz que acabou de começar. */
function session(): { id: string; fresh: boolean } {
  const now = Date.now();
  let current: { id: string; t: number } | null = memorySession;
  try {
    const raw = sessionStorage.getItem(SESSION_KEY);
    if (raw) current = JSON.parse(raw);
  } catch { /* sem armazenamento: vale a memória da página */ }
  const fresh = !current || now - current.t >= IDLE_MS;
  const next = fresh ? { id: uuid(), t: now } : { id: current!.id, t: now };
  if (fresh) { onceKeys.clear(); lastSeen.clear(); }
  memorySession = next;
  try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(next)); } catch { /* sem armazenamento */ }
  return { id: next.id, fresh };
}

/** Envia o que está na fila (em `pagehide`/aba oculta usa sendBeacon, que sobrevive ao fechamento). */
export function flush(leaving = false): void {
  clearTimeout(timer);
  timer = undefined;
  while (queue.length > 0) {
    const events = queue.splice(0, MAX_BATCH);
    const body = JSON.stringify({ session_id: session().id, platform: 'site', events });
    if (leaving && typeof navigator.sendBeacon === 'function') navigator.sendBeacon(ENDPOINT, new Blob([body], { type: 'application/json' }));
    else fetch(ENDPOINT, { method: 'POST', headers: { 'content-type': 'application/json' }, body, keepalive: true }).catch(() => { /* a medição nunca atrapalha o site */ });
  }
}

function wire(): void {
  if (wired) return;
  wired = true;
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') flush(true); });
  window.addEventListener('pagehide', () => flush(true));
}

/**
 * Registra um evento. Vista e clique de um item contam uma vez por visita; o mesmo evento repetido em menos de 3 s
 * (duplo toque) é ignorado. O servidor garante o mesmo, então perder o controle aqui não duplica nada.
 */
export function track(name: EngagementName, props: EngagementProps = {}): void {
  if (!enabled()) return;
  wire();
  session();
  const key = `${name}|${props.route ?? ''}|${props.item_id ?? ''}|${props.detail ?? ''}`;
  if (name === 'menu_item_view' || name === 'menu_item_click') {
    const once = `${name}|${props.item_id ?? ''}`;
    if (onceKeys.has(once)) return;
    onceKeys.add(once);
  }
  const now = Date.now();
  if (now - (lastSeen.get(key) ?? 0) < CLICK_GAP_MS && name !== 'cart_add' && name !== 'cart_remove') return;
  lastSeen.set(key, now);
  queue.push({ name, at: now, ...props });
  if (queue.length >= MAX_BATCH) flush();
  else if (timer === undefined) timer = setTimeout(() => flush(), FLUSH_MS);
}

/** Rotas do site e do cliente (a gestão não é medida). */
export function isPublicRoute(path: string): boolean {
  const first = path.split('/')[1] ?? '';
  return ['', 'cardapio', 'cliente', 'termos', 'privacidade', 'pagar', 'excluir-conta'].includes(first);
}

/** Troca o que identificaria alguém no caminho (token do link de pagamento); o servidor troca os ids numéricos. */
export function routeOf(path: string): string {
  return path.split(/[?#]/)[0].replace(/^\/pagar\/[^/]+/, '/pagar/:token');
}

/** Uma tela vista; na primeira da visita leva a origem da entrada (só a categoria, nunca o endereço). */
export function trackScreen(path: string): void {
  if (!enabled() || !isPublicRoute(path)) return;
  const fresh = session().fresh;
  track('screen_view', { route: routeOf(path), ...(fresh ? { detail: referrerKind(document.referrer) } : {}) });
}
