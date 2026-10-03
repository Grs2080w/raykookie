import { describe, expect, it } from 'vitest';
import { fieldNum, parseLocaleNumber } from '@shared/text';
import { num } from '@/lib/api';

describe('parseLocaleNumber', () => {
  it('lê formatos brasileiros', () => {
    expect(parseLocaleNumber('1.500,50')).toBe(1500.5);
    expect(parseLocaleNumber('12,5')).toBe(12.5);
    expect(parseLocaleNumber('1.000')).toBe(1000); // 1 kg = 1.000 g
    expect(parseLocaleNumber('1.000.000')).toBe(1_000_000);
  });
  it('mantém decimais com ponto', () => {
    expect(parseLocaleNumber('7.5')).toBe(7.5);
    expect(parseLocaleNumber('0.500')).toBe(0.5);
    expect(parseLocaleNumber('10.25')).toBe(10.25);
  });
  it('texto que não é número vira NaN', () => {
    expect(Number.isNaN(parseLocaleNumber('abc'))).toBe(true);
    expect(Number.isNaN(parseLocaleNumber('1.2.3'))).toBe(true);
  });
  it('fieldNum devolve com vírgula, sem ambiguidade de milhar', () => {
    expect(fieldNum(2.125)).toBe('2,125');
    expect(parseLocaleNumber(fieldNum(2.125))).toBe(2.125);
    expect(fieldNum(null)).toBe('');
  });
});

describe('num (API)', () => {
  it('texto digitado usa o parse pt-BR; número JSON vale como está', () => {
    expect(num('1.000')).toBe(1000);
    expect(num('8,50')).toBe(8.5);
    expect(num(2.125)).toBe(2.125); // não é lido como milhar
    expect(num('', 7)).toBe(7);
  });
  it('recusa texto e negativo', () => {
    expect(() => num('abc')).toThrow(/não é um número/);
    expect(() => num('-5')).toThrow(/negativos/);
  });
});
