import { describe, expect, it } from 'vitest';
import { MAX_FEEDBACK_MESSAGE, validateFeedback } from '@shared/feedback';

describe('validateFeedback', () => {
  it('aceita nota de 1 a 5 com mensagem dentro do limite', () => {
    for (let r = 1; r <= 5; r++) expect(validateFeedback(r, 'Muito bom!')).toBeNull();
  });
  it('recusa nota fora de 1-5 ou não inteira', () => {
    expect(validateFeedback(0, '')).toMatch(/nota/);
    expect(validateFeedback(6, '')).toMatch(/nota/);
    expect(validateFeedback(3.5, '')).toMatch(/nota/);
    expect(validateFeedback('5', '')).toMatch(/nota/);
  });
  it('recusa mensagem que não é string', () => {
    expect(validateFeedback(5, null)).toMatch(/[Mm]ensagem/);
    expect(validateFeedback(5, 123)).toMatch(/[Mm]ensagem/);
  });
  it('recusa mensagem acima do limite', () => {
    expect(validateFeedback(5, 'a'.repeat(MAX_FEEDBACK_MESSAGE))).toBeNull();
    expect(validateFeedback(5, 'a'.repeat(MAX_FEEDBACK_MESSAGE + 1))).toMatch(/máximo/);
  });
  it('mensagem vazia é válida (só a nota é obrigatória)', () => expect(validateFeedback(4, '')).toBeNull());
});
