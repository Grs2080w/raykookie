'use client';
import { api } from '@/lib/client';

/** Resposta de uma cobrança no cartão. `challenge_url` = o banco pede a confirmação (3DS) antes de aprovar. */
export type CardResult = { approved: boolean; pending?: boolean; reason?: string; challenge_url?: string };

/** Depois do desafio 3DS: pergunta ao servidor (sem cobrar de novo) até sair do "pendente". O banco pode demorar
 *  alguns segundos para avisar o Mercado Pago, por isso algumas tentativas espaçadas. */
export async function pollCard(statusUrl: string, tries = 8, waitMs = 2000): Promise<CardResult> {
  let r = await api<CardResult>(statusUrl, 'GET');
  for (let i = 1; i < tries && r.pending && !r.approved; i++) {
    await new Promise((resolve) => setTimeout(resolve, waitMs));
    r = await api<CardResult>(statusUrl, 'GET');
  }
  return r;
}

/** Se a resposta pede o desafio 3DS: mostra o desafio, espera o fim e devolve o resultado final.
 *  Sem desafio, devolve a resposta como veio. */
export async function withChallenge(r: CardResult, statusUrl: string, run: (url: string) => Promise<void>): Promise<CardResult> {
  if (!r.challenge_url) return r;
  await run(r.challenge_url);
  return pollCard(statusUrl);
}
