import { describe, expect, it, vi } from 'vitest';

const lookup = vi.fn();
vi.mock('node:dns/promises', () => ({ default: { lookup: (...a: unknown[]) => lookup(...a) }, lookup: (...a: unknown[]) => lookup(...a) }));
import { assertPublicUrl, parsePublicUrl } from '@/lib/scrapers/safe-url';

describe('endereços de fornecedores (contra SSRF)', () => {
  it('aceita só site público com https', () => {
    expect(parsePublicUrl('https://www.romafele.com.br/').hostname).toBe('www.romafele.com.br');
    expect(parsePublicUrl('romafele.com.br').protocol).toBe('https:'); // sem "https://": completa
  });
  it('recusa localhost, IP, http, credenciais e domínios internos', () => {
    for (const bad of ['https://localhost:10005/api', 'https://127.0.0.1/', 'https://10.0.0.5/', 'https://[::1]/', 'http://loja.com.br', 'https://user:pw@loja.com.br', 'https://servidor.internal/', 'https://intranet/', 'file:///etc/passwd', '']) {
      expect(() => parsePublicUrl(bad), bad).toThrow();
    }
  });
  it('recusa um domínio que aponta para a rede interna', async () => {
    lookup.mockResolvedValueOnce([{ address: '192.168.0.10', family: 4 }]);
    await expect(assertPublicUrl('https://evil.example.com')).rejects.toThrow(/público/);
    lookup.mockResolvedValueOnce([{ address: '127.0.0.1', family: 4 }]);
    await expect(assertPublicUrl('https://evil2.example.com')).rejects.toThrow();
    lookup.mockResolvedValueOnce([{ address: '::1', family: 6 }]);
    await expect(assertPublicUrl('https://evil3.example.com')).rejects.toThrow();
  });
  it('aceita quando o IP é público', async () => {
    lookup.mockResolvedValueOnce([{ address: '203.0.113.7', family: 4 }]);
    await expect(assertPublicUrl('https://loja.example.com')).resolves.toBeInstanceOf(URL);
  });
});
