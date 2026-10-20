'use client';
// Guarda a Prospecção no navegador (localStorage): o rascunho atual, que sobrevive a trocar de aba, e as simulações salvas.
// Nada disso vai para o servidor nem altera o cadastro de receitas.
import { DEFAULT_GLOBALS, type ProspectGlobals, type ProspectItem } from '@shared/prospection';

export type Draft = { items: ProspectItem[]; globals: ProspectGlobals; name: string; savedId: string | null };
export type Simulation = { id: string; name: string; updatedAt: string; items: ProspectItem[]; globals: ProspectGlobals };

const DRAFT_KEY = 'rk:prospeccao:rascunho';
const SAVED_KEY = 'rk:prospeccao:simulacoes';

export const emptyDraft = (): Draft => ({ items: [], globals: { ...DEFAULT_GLOBALS }, name: '', savedId: null });
export const uid = () => (globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`);

function read<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch { return fallback; } // navegação privada ou dado estragado: começa do zero
}
function write(key: string, value: unknown) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* sem espaço ou bloqueado: segue sem salvar */ }
}

/** Completa campos que faltam (versões antigas ou JSON estragado) com os padrões. */
function sanitizeGlobals(g: Partial<ProspectGlobals> | undefined): ProspectGlobals {
  return { ...DEFAULT_GLOBALS, ...g, tiers: Array.isArray(g?.tiers) ? g.tiers : [] };
}
const sanitizeItems = (l: unknown): ProspectItem[] => (Array.isArray(l) ? l.filter((i) => i && typeof i.id === 'string') : []);

export function loadDraft(): Draft {
  const d = read<Partial<Draft>>(DRAFT_KEY, {});
  return { items: sanitizeItems(d.items), globals: sanitizeGlobals(d.globals), name: typeof d.name === 'string' ? d.name : '', savedId: d.savedId ?? null };
}
export const saveDraft = (d: Draft) => write(DRAFT_KEY, d);

export function loadSimulations(): Simulation[] {
  const l = read<Partial<Simulation>[]>(SAVED_KEY, []);
  return (Array.isArray(l) ? l : [])
    .filter((s) => s && typeof s.id === 'string')
    .map((s) => ({ id: s.id!, name: s.name ?? 'Sem nome', updatedAt: s.updatedAt ?? '', items: sanitizeItems(s.items), globals: sanitizeGlobals(s.globals) }))
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
}
export const saveSimulations = (l: Simulation[]) => write(SAVED_KEY, l);
