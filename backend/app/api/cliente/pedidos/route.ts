import { fail, ok } from '@/lib/api';
import { customerRoute } from '@/lib/customer-api';
import { getMenu } from '@/lib/shop-menu';
import { createShopOrder, getCustomerOrders, shopOwnerId } from '@/lib/shop-queries';
import { notifyNewOrder } from '@/lib/push';
import { mpConfigured } from '@/lib/mercadopago';
import { issuePix } from '@/lib/pix';
import { parseShopOrder } from '@shared/shop';

// data de hoje no fuso da loja (o servidor pode estar em UTC)
import { todayBR as today } from '@/lib/dates';

export const POST = customerRoute(async (req, customer) => {
  const b = await req.json().catch(() => null);
  const menu = await getMenu(); // preços sempre lidos das receitas no servidor
  const p = parseShopOrder(b, today(), menu.items);
  if (p.error) return fail(p.error);
  const owner = await shopOwnerId();
  if (!owner) return fail('A loja não está recebendo pedidos no momento. Tente mais tarde.', 503);
  const { id, code } = await createShopOrder(customer, owner, p.value!);
  void notifyNewOrder(owner, { id, code, customer: p.value!.contact_name, total: p.value!.total, items: p.value!.items.length }); // sem await: o cliente não espera o push
  // Pix: gera a cobrança no Mercado Pago. Se falhar, o pedido fica pendente (o app mostra o aviso e a loja fala com o cliente pelo WhatsApp só se for necessário).
  let pix: { code: string; qr_base64: string; expires_in: number } | null = null;
  if (p.value!.payment_method === 'pix' && mpConfigured()) {
    try {
      const pay = await issuePix(id, code, p.value!.total, customer.email, p.value!.contact_name, 1);
      pix = { code: pay.code, qr_base64: pay.qrBase64, expires_in: Math.floor((pay.expiresAt.getTime() - Date.now()) / 1000) };
    } catch (e) { console.error('[mercadopago] falha ao gerar Pix do pedido', id, e); }
  }
  return ok({ id, code, pix }, 201);
});

export const GET = customerRoute(async (_req, customer) => ok(await getCustomerOrders(customer.id)));
