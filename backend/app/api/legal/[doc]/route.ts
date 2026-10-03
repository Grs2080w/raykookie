import { LEGAL_DOCS, isLegalId } from '@shared/legal';

// Texto dos Termos e da Política de Privacidade para os apps (o site os lê direto de shared/legal.ts).
// Público: precisa abrir antes de criar a conta.
export async function GET(_req: Request, { params }: { params: Promise<{ doc: string }> }) {
  const { doc } = await params;
  if (!isLegalId(doc)) return Response.json({ error: 'Documento não encontrado' }, { status: 404 });
  return Response.json(LEGAL_DOCS[doc], { headers: { 'Cache-Control': 'public, max-age=300' } });
}
