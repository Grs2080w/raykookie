import { getActivePickup } from '@/lib/pickup';
import { shopOwnerId } from '@/lib/shop-queries';

// Endereço de retirada atual da loja. Público: o cliente vê antes de pedir e no pedido.
export async function GET() {
  const owner = await shopOwnerId();
  const a = owner ? await getActivePickup(owner) : null;
  return Response.json(
    a ? { label: a.label, address: a.address, instructions: a.instructions } : { label: '', address: '', instructions: '' },
    { headers: { 'Cache-Control': 'public, max-age=30' } },
  );
}
