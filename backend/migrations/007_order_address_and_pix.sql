-- Endereço dedicado nos pedidos da gestão (antes ia concatenado em `notes`).
ALTER TABLE orders ADD COLUMN address TEXT NOT NULL DEFAULT '';
UPDATE orders o SET address = CASE
    WHEN so.fulfillment = 'entrega'
      THEN so.street || ', ' || so.number || CASE WHEN so.complement <> '' THEN ' (' || so.complement || ')' ELSE '' END
           || ' - ' || so.district || ', ' || so.city
    ELSE 'Retirada no local' END
  FROM shop_orders so WHERE so.id = o.shop_order_id;

-- Pagamento Pix pelo Mercado Pago: o pedido só vira "pago" quando o webhook confirma a aprovação.
ALTER TABLE shop_orders ADD COLUMN mp_payment_id TEXT;
ALTER TABLE shop_orders ADD COLUMN pix_qr_code   TEXT NOT NULL DEFAULT '';
ALTER TABLE shop_orders ADD COLUMN pix_qr_base64 TEXT NOT NULL DEFAULT '';
ALTER TABLE shop_orders ADD COLUMN paid_at       TIMESTAMPTZ;
CREATE UNIQUE INDEX shop_orders_mp_payment_idx ON shop_orders (mp_payment_id) WHERE mp_payment_id IS NOT NULL;
