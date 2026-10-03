import { randomInt } from 'node:crypto';

export const CODE_MIN = 1000, CODE_MAX = 99999; // 4 ou 5 dígitos

/** Código aleatório de 4 ou 5 dígitos, que ainda não existe (`taken` diz se já está em uso). */
export async function allocateOrderCode(taken: (code: number) => Promise<boolean>, attempts = 30): Promise<number> {
  for (let i = 0; i < attempts; i++) {
    const code = randomInt(CODE_MIN, CODE_MAX + 1);
    if (!(await taken(code))) return code;
  }
  throw new Error('Não foi possível gerar um código de pedido livre.');
}
