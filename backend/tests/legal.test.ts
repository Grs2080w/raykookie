import { describe, expect, it } from 'vitest';
import { GET } from '@/app/api/legal/[doc]/route';
import { LEGAL_DOCS, LEGAL_IDS, LEGAL_PATH, isLegalId } from '@shared/legal';

const call = (doc: string) => GET(new Request(`http://x/api/legal/${doc}`), { params: Promise.resolve({ doc }) });

describe('documentos legais', () => {
  it('Termos e Privacidade têm título, data e seções preenchidas', () => {
    expect(LEGAL_IDS).toEqual(['termos', 'privacidade']);
    for (const id of LEGAL_IDS) {
      const d = LEGAL_DOCS[id];
      expect(d.id).toBe(id);
      expect(d.title.length).toBeGreaterThan(5);
      expect(d.updated).toMatch(/\d{4}/);
      expect(d.sections.length).toBeGreaterThan(8);
      for (const s of d.sections) {
        expect(s.h.length).toBeGreaterThan(3);
        expect((s.p?.length ?? 0) + (s.ul?.length ?? 0)).toBeGreaterThan(0);
      }
    }
  });
  it('não deixa espaço vazio nem "undefined" no texto quando os dados da empresa estão em branco', () => {
    const all = JSON.stringify(LEGAL_DOCS);
    expect(all).not.toMatch(/undefined|\{\{|\[\s*\]|,\s*,|\(\s*\)/);
    expect(all).not.toMatch(/ ,|\s{2,}/);
  });
  it('cada documento tem a sua URL no site', () => {
    expect(LEGAL_PATH).toEqual({ termos: '/termos', privacidade: '/privacidade' });
  });
  it('a regra de antecedência e de quantidade do texto acompanha a loja', () => {
    expect(JSON.stringify(LEGAL_DOCS.termos)).toMatch(/1 dia de antecedência/);
    expect(JSON.stringify(LEGAL_DOCS.termos)).toMatch(/50 unidades/);
  });
  it('só aceita documentos conhecidos', () => {
    expect(isLegalId('termos')).toBe(true);
    expect(isLegalId('../etc/passwd')).toBe(false);
  });
});

describe('GET /api/legal/[doc]', () => {
  it('devolve o documento em JSON, sem exigir login', async () => {
    const r = await call('privacidade');
    expect(r.status).toBe(200);
    const j = await r.json();
    expect(j.id).toBe('privacidade');
    expect(j.sections.length).toBeGreaterThan(8);
  });
  it('404 para documento desconhecido', async () => {
    expect((await call('xyz')).status).toBe(404);
  });
});

import { LGPD_RIGHTS } from '@shared/lgpd';
describe('LGPD e escopo dos textos', () => {
  const all = (id: 'termos' | 'privacidade') => LEGAL_DOCS[id].sections.flatMap((s) => [s.h, ...(s.p ?? []), ...(s.ul ?? [])]).join('\n');
  it('os textos valem só para o site e o app do cliente: sem menção à gestão', () => {
    for (const id of ['termos', 'privacidade'] as const) expect(all(id)).not.toMatch(/gest[ãa]o|painel|estoque|biometria/i);
  });
  it('cada direito do §7 tem um lugar no app (LGPD_RIGHTS) e aparece no texto', () => {
    const sec = LEGAL_DOCS.privacidade.sections.find((s) => s.h.startsWith('7.'))!;
    const items = (sec.ul ?? []).map((u) => u.replace(/[;.]\s*(Alguns dados.*)?$/, '').trim());
    expect(items.length).toBe(LGPD_RIGHTS.length);
    for (const [i, it] of items.entries()) expect(it.startsWith(LGPD_RIGHTS[i].right.slice(0, 25))).toBe(true);
    for (const r of LGPD_RIGHTS) expect(r.where).toMatch(/^Minha conta → Meus dados/);
  });
});
