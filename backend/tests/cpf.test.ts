import { describe, expect, it } from 'vitest';
import { cleanCpf, formatCpf, isValidCpf } from '@shared/cpf';

describe('CPF', () => {
  it('aceita CPFs válidos, com ou sem máscara', () => {
    expect(isValidCpf('529.982.247-25')).toBe(true);
    expect(isValidCpf('52998224725')).toBe(true);
    expect(isValidCpf('111.444.777-35')).toBe(true);
  });
  it('recusa dígito verificador errado, sequência repetida e tamanho errado', () => {
    expect(isValidCpf('529.982.247-26')).toBe(false);
    expect(isValidCpf('111.111.111-11')).toBe(false);
    expect(isValidCpf('000.000.000-00')).toBe(false);
    expect(isValidCpf('123')).toBe(false);
    expect(isValidCpf('')).toBe(false);
    expect(isValidCpf(undefined)).toBe(false);
  });
  it('limpa e formata', () => {
    expect(cleanCpf('529.982.247-25')).toBe('52998224725');
    expect(formatCpf('52998224725')).toBe('529.982.247-25');
    expect(formatCpf('529')).toBe('529');
  });
});
