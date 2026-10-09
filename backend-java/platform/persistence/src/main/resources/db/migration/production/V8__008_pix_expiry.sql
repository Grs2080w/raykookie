-- Validade do Pix: o código expira e, depois disso, um novo precisa ser gerado.
ALTER TABLE shop_orders ADD COLUMN pix_expires_at TIMESTAMPTZ;
ALTER TABLE shop_orders ADD COLUMN pix_attempts   INT NOT NULL DEFAULT 1;           -- quantos Pix já foram gerados para o pedido
ALTER TABLE shop_orders ADD COLUMN mp_payment_ids TEXT[] NOT NULL DEFAULT '{}';     -- todos os pagamentos Pix gerados (o webhook aceita qualquer um)
UPDATE shop_orders SET mp_payment_ids = ARRAY[mp_payment_id] WHERE mp_payment_id IS NOT NULL;
-- Pix já gerados antes desta mudança: o Mercado Pago os deixa valer 24 h
UPDATE shop_orders SET pix_expires_at = created_at + interval '24 hours' WHERE mp_payment_id IS NOT NULL AND paid_at IS NULL;
