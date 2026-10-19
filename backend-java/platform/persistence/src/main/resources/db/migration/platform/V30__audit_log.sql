-- Histórico de auditoria: quem mudou estoque, receitas, preços, cupons e pedidos, quando, de onde e o antes/depois.
-- Só inserção: os gatilhos abaixo recusam UPDATE, DELETE e TRUNCATE, até para quem tem acesso direto ao banco pela
-- aplicação. Sem chave estrangeira de propósito: apagar uma conta não pode apagar (nem ser barrado por) o histórico.
-- Aditiva.
CREATE TABLE IF NOT EXISTS audit_log (
  id          BIGSERIAL    PRIMARY KEY,
  occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
  owner_id    INT          NOT NULL,           -- o painel (loja) dono do registro
  user_id     INT          NOT NULL,           -- quem agiu
  user_name   TEXT         NOT NULL,
  role        TEXT         NOT NULL,           -- papel de quem agiu (hoje só 'dona')
  action      TEXT         NOT NULL,           -- criou, editou, excluiu, aplicou, ajustou...
  category    TEXT         NOT NULL,           -- estoque, receita, preco, cupom, pedido, financeiro, cardapio, pix_presencial
  location    TEXT         NOT NULL,           -- tela de origem: "Gestão > Estoque"
  route       TEXT         NOT NULL,           -- "PATCH /api/ingredients/7" ou "(sistema)"
  record_kind TEXT         NOT NULL,           -- tipo do registro afetado: ingredient, recipe, coupon...
  record_id   TEXT         NOT NULL,
  old_value   JSONB,
  new_value   JSONB,
  ip          TEXT,
  details     TEXT         NOT NULL
);
CREATE INDEX IF NOT EXISTS audit_log_owner_time_idx ON audit_log (owner_id, occurred_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS audit_log_owner_category_idx ON audit_log (owner_id, category, occurred_at DESC);

CREATE OR REPLACE FUNCTION audit_log_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'audit_log é somente inserção (% bloqueado)', TG_OP USING ERRCODE = '42501';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER audit_log_no_change BEFORE UPDATE OR DELETE ON audit_log
  FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();

CREATE OR REPLACE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
  FOR EACH STATEMENT EXECUTE FUNCTION audit_log_immutable();
