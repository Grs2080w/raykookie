import { mpCardConfigured, mpPublicKey } from '@/lib/mercadopago';

// Configuração de pagamento vista pelo site e pelo app antes de pedir. Público: só expõe a chave pública
// do Mercado Pago (pensada para rodar no cliente, como a "publishable key" de outros gateways) — nunca a
// chave de acesso (secreta). Lida em tempo real: configurar a chave não exige publicar o app de novo.
export async function GET() {
  const cardEnabled = mpCardConfigured();
  return Response.json(
    { cardEnabled, publicKey: cardEnabled ? mpPublicKey() : null },
    { headers: { 'Cache-Control': 'public, max-age=30' } },
  );
}
