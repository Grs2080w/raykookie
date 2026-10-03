import fs from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { _clearScraperCache, matchesQuery, parseTraySearch, traySearch } from '@/lib/scrapers/tray';

vi.mock('@/lib/scrapers/safe-url', () => ({ assertPublicUrl: async (u: string) => new URL(u) })); // sem rede nos testes
const html = fs.readFileSync(path.join(__dirname, 'fixtures/tray-search.html'), 'utf8');

describe('scraper Tray (Romafele)', () => {
  it('lê nome, preço, link e embalagem de cada produto', () => {
    const list = parseTraySearch(html);
    expect(list.length).toBeGreaterThanOrEqual(2);
    const jazam = list.find((p) => /JAZAM/.test(p.name))!;
    expect(jazam).toMatchObject({ price: 44.99, url: 'https://www.romafele.com.br/doces/jazam-coloreti-1-kg', size: 1000, unit: 'g', available: true });
    const acucar = list.find((p) => /IMPALPAVEL/.test(p.name))!;
    expect(acucar).toMatchObject({ price: 15.5, size: 1000, unit: 'g' });
    expect(list.find((p) => /Vela/.test(p.name))).toMatchObject({ price: 7.99, size: null, unit: null }); // sem tamanho no nome: a confeiteira informa
  });
  it('página sem produtos: lista vazia, sem erro', () => expect(parseTraySearch('<html><body>nada</body></html>')).toEqual([]));
  it('o filtro mantém só o que tem todas as palavras buscadas (a loja traz itens de outras áreas)', () => {
    expect(matchesQuery('ACUCAR IMPALPAVEL CONFEITEIRO 1KG', 'açúcar impalpável')).toBe(true);
    expect(matchesQuery('Chocolates granulados', 'chocolate')).toBe(true);
    expect(matchesQuery('Vela Balão Branca', 'chocolate')).toBe(false);
  });

  afterEach(() => { vi.unstubAllGlobals(); _clearScraperCache(); });
  it('busca na loja, lê a página em latin1 e guarda em cache por 12 h', async () => {
    const latin = Buffer.from(html.replace('JAZAM COLORETI 1 KG', 'JAZAM COLORÉTI 1 KG'), 'latin1');
    const fetchMock = vi.fn(async (_url: string) => new Response(latin, { status: 200, headers: { 'content-type': 'text/html; charset=iso-8859-1' } }));
    vi.stubGlobal('fetch', fetchMock);
    const a = await traySearch('https://www.romafele.com.br/', 'coloréti');
    expect(a.map((p) => p.name)).toContain('JAZAM COLORÉTI 1 KG');
    expect(String(fetchMock.mock.calls[0][0])).toBe('https://www.romafele.com.br/loja/busca.php?palavra_busca=color%C3%A9ti');
    await traySearch('https://www.romafele.com.br', 'coloréti');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it('site mudou (sem a estrutura esperada): erro claro', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html><body>Em manutenção</body></html>', { status: 200 })));
    await expect(traySearch('https://loja.exemplo.com', 'chocolate')).rejects.toThrow(/Tray|mudado/);
  });
  it('site fora do ar: erro com o código', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('x', { status: 503 })));
    await expect(traySearch('https://loja.exemplo.com', 'leite')).rejects.toThrow(/503/);
  });
});
