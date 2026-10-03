-- Código do pedido que o cliente vê (4 ou 5 dígitos, aleatório): o cliente não precisa saber quantos pedidos a loja tem.
-- O id interno continua existindo e é o que as rotas usam.
ALTER TABLE shop_orders ADD COLUMN public_code INT;

DO $$
DECLARE r RECORD; c INT;
BEGIN
  FOR r IN SELECT id FROM shop_orders WHERE public_code IS NULL ORDER BY id LOOP
    LOOP
      c := 1000 + floor(random() * 99000)::int;           -- 1000 a 99999
      EXIT WHEN NOT EXISTS (SELECT 1 FROM shop_orders WHERE public_code = c);
    END LOOP;
    UPDATE shop_orders SET public_code = c WHERE id = r.id;
  END LOOP;
END $$;

ALTER TABLE shop_orders ALTER COLUMN public_code SET NOT NULL;
ALTER TABLE shop_orders ADD CONSTRAINT shop_orders_public_code_range CHECK (public_code BETWEEN 1000 AND 99999);
CREATE UNIQUE INDEX shop_orders_public_code_idx ON shop_orders (public_code);

-- a observação dos pedidos já feitos citava o id: troca pelo código que o cliente enxerga
UPDATE orders o SET notes = regexp_replace(o.notes, 'Pedido online #\d+', 'Pedido online #' || so.public_code)
  FROM shop_orders so WHERE so.id = o.shop_order_id AND o.notes LIKE 'Pedido online #%';
