// Direitos do titular (LGPD) e onde cada um é exercido. O texto da Política (§7) e este mapa andam juntos:
// o teste legal.test.ts quebra se um direito do texto ficar sem tela.
export type PrivacyType = 'acesso' | 'correcao' | 'anonimizacao' | 'portabilidade' | 'compartilhamento' | 'revogacao' | 'exclusao' | 'outro';

/** Tipos aceitos em "Fazer uma solicitação sobre meus dados". */
export const PRIVACY_TYPES: { id: PrivacyType; label: string }[] = [
  { id: 'anonimizacao', label: 'Anonimizar, bloquear ou eliminar dados' },
  { id: 'acesso', label: 'Quero uma resposta sobre os meus dados' },
  { id: 'compartilhamento', label: 'Saber com quem compartilharam meus dados' },
  { id: 'revogacao', label: 'Revogar um consentimento' },
  { id: 'outro', label: 'Outro assunto' },
];
export const privacyLabel = (id: string) => ({ ...Object.fromEntries(PRIVACY_TYPES.map((t) => [t.id, t.label])), exclusao: 'Exclusão da conta', correcao: 'Correção de dados', portabilidade: 'Cópia dos dados' } as Record<string, string>)[id] ?? id;

export const PRIVACY_DEADLINE_DAYS = 15;

/** Cada direito do §7 da Política → onde o cliente o exerce (mesmo caminho no site e no app). */
export const LGPD_RIGHTS: { right: string; where: string }[] = [
  { right: 'confirmação de que tratamos seus dados e acesso a eles', where: 'Minha conta → Meus dados' },
  { right: 'correção de dados incompletos, inexatos ou desatualizados', where: 'Minha conta → Meus dados → Editar' },
  { right: 'anonimização, bloqueio ou eliminação de dados desnecessários ou tratados em desacordo com a lei', where: 'Minha conta → Meus dados → Fazer uma solicitação' },
  { right: 'portabilidade dos dados, quando aplicável', where: 'Minha conta → Meus dados → Baixar meus dados' },
  { right: 'eliminação dos dados tratados com o seu consentimento, e informação sobre com quem compartilhamos', where: 'Minha conta → Meus dados → Com quem compartilhamos / Fazer uma solicitação' },
  { right: 'informação sobre a possibilidade de não consentir e suas consequências, e revogação do consentimento', where: 'Minha conta → Meus dados → Login com Google / Fazer uma solicitação' },
  { right: 'exclusão da sua conta', where: 'Minha conta → Meus dados → Excluir minha conta' },
];
