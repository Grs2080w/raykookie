// Feedback dos clientes: nota de 1 a 5, mensagem e fotos opcionais. Só quem comprou (cliente logado) envia;
// a confeiteira só lê, pelo painel. Mesmas regras de foto do refund.ts (mesmo tamanho/limite, consistência).
export const MAX_FEEDBACK_PHOTOS = 5;
export const MAX_FEEDBACK_PHOTO_BYTES = 2_000_000;
export const MAX_FEEDBACK_MESSAGE = 1000;

export function validateFeedback(rating: unknown, message: unknown): string | null {
  if (!Number.isInteger(rating) || (rating as number) < 1 || (rating as number) > 5) return 'Escolha uma nota de 1 a 5 estrelas.';
  if (typeof message !== 'string') return 'Mensagem inválida.';
  if (message.length > MAX_FEEDBACK_MESSAGE) return `A mensagem pode ter no máximo ${MAX_FEEDBACK_MESSAGE} caracteres.`;
  return null;
}
