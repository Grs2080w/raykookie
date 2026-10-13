-- Medição de engajamento dos clientes (site e app), anônima: sem IP, e-mail, conta nem identificador fixo por
-- aparelho. O `session_id` é um UUID aleatório que dura uma visita. Eventos brutos guardam 90 dias; o agregado diário
-- (`engagement_daily`) guarda mais. Aditiva.
CREATE TABLE IF NOT EXISTS engagement_events (
  id          BIGSERIAL    PRIMARY KEY,
  occurred_at TIMESTAMPTZ  NOT NULL,
  session_id  UUID         NOT NULL,
  platform    TEXT         NOT NULL CHECK (platform IN ('site', 'android', 'ios')),
  app_version TEXT         NOT NULL DEFAULT '',
  name        TEXT         NOT NULL,
  route       TEXT         NOT NULL DEFAULT '',
  item_id     TEXT         NOT NULL DEFAULT '',
  detail      TEXT         NOT NULL DEFAULT '',
  qty         INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS engagement_events_time_idx ON engagement_events (occurred_at);
-- vista e clique de um item contam uma vez por sessão (sem duplicação)
CREATE UNIQUE INDEX IF NOT EXISTS engagement_once_idx ON engagement_events (session_id, name, item_id)
  WHERE name IN ('menu_item_view', 'menu_item_click');

-- Uma linha por dia, plataforma, evento e recorte; '*' = "todos" no recorte. `sessions` = sessões distintas.
CREATE TABLE IF NOT EXISTS engagement_daily (
  day      DATE NOT NULL,
  platform TEXT NOT NULL,
  name     TEXT NOT NULL,
  route    TEXT NOT NULL,
  item_id  TEXT NOT NULL,
  detail   TEXT NOT NULL,
  events   INT  NOT NULL,
  sessions INT  NOT NULL,
  PRIMARY KEY (day, platform, name, route, item_id, detail)
);
