-- Devolução total encerra o pedido (regra nova). Corrige os pedidos que ficaram em desacordo antes dela.

-- 1. A loja "cancelou" um pedido que já tinha sido devolvido por inteiro: o cancelamento não devolveu nada (não há devolução
--    criada junto com ele), só trocou quem cancelou e o motivo que o cliente vê. O motivo de verdade é a devolução total.
UPDATE shop_orders so
   SET cancelled_by = 'sistema', cancel_reason_code = 'devolucao_total', cancel_reason = 'Todo o valor foi devolvido', cancel_note = '',
       cancelled_at = COALESCE((SELECT max(r.decided_at) FROM refunds r WHERE r.shop_order_id = so.id AND r.status = 'concluido'), so.cancelled_at)
 WHERE so.cancelled_at IS NOT NULL AND so.cancelled_by = 'loja' AND so.paid_at IS NOT NULL AND so.refunded_total >= so.total
   AND NOT EXISTS (SELECT 1 FROM refunds r WHERE r.shop_order_id = so.id AND r.created_at >= so.cancelled_at)
   AND EXISTS (SELECT 1 FROM refunds r WHERE r.shop_order_id = so.id AND r.status = 'concluido' AND r.refunded_at < so.cancelled_at);

-- 2. Pedidos pagos e devolvidos por inteiro que continuaram abertos: os itens não entregues viram cancelados.
WITH closed AS (
  UPDATE shop_orders so
     SET cancelled_at = now(), cancelled_by = 'sistema', cancel_reason_code = 'devolucao_total', cancel_reason = 'Todo o valor foi devolvido', cancel_note = ''
   WHERE so.cancelled_at IS NULL AND so.paid_at IS NOT NULL AND so.refunded_total >= so.total
     AND EXISTS (SELECT 1 FROM orders o WHERE o.shop_order_id = so.id AND o.status NOT IN ('cancelado','entregue'))
  RETURNING so.id
), items AS (
  UPDATE orders SET status = 'cancelado' WHERE shop_order_id IN (SELECT id FROM closed) AND status NOT IN ('cancelado','entregue') RETURNING shop_order_id
)
INSERT INTO order_events(shop_order_id, actor, type, data)
SELECT id, 'sistema', 'cancelado', '{"code":"devolucao_total","paid":true,"fix":"016"}'::jsonb FROM closed;
