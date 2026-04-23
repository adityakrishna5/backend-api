-- infra/local/init.sql
-- Initialises the local PostgreSQL 15 database for the store project
-- Run automatically by docker-compose when the postgres container is first created

SET client_encoding = 'UTF8';

-- ─────── tables ───────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS products (
  id            BIGSERIAL     PRIMARY KEY,
  name          VARCHAR(255)  NOT NULL,
  description   TEXT,
  price         NUMERIC(10,2) NOT NULL DEFAULT 0.00,
  stock_level   INTEGER       NOT NULL DEFAULT 0,
  created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  updated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS inventory (
  id            BIGSERIAL     PRIMARY KEY,
  product_id    BIGINT        NOT NULL UNIQUE REFERENCES products(id),
  stock_level   INTEGER       NOT NULL DEFAULT 0,
  reserved_qty  INTEGER       NOT NULL DEFAULT 0,
  version       BIGINT        NOT NULL DEFAULT 0  -- JPA @Version (optimistic lock)
);

CREATE TABLE IF NOT EXISTS orders (
  id            BIGSERIAL     PRIMARY KEY,
  order_id      VARCHAR(255)  NOT NULL UNIQUE,
  product_id    BIGINT        NOT NULL,
  quantity      INTEGER       NOT NULL DEFAULT 1,
  status        VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
  created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS outbox_events (
  id            BIGSERIAL     PRIMARY KEY,
  aggregate_id  VARCHAR(255)  NOT NULL,
  event_type    VARCHAR(128)  NOT NULL,
  payload       JSONB         NOT NULL,
  published     BOOLEAN       NOT NULL DEFAULT FALSE,
  created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS dlt_events (
  id            VARCHAR(255)  PRIMARY KEY,
  source_topic  VARCHAR(128)  NOT NULL,
  payload       JSONB         NOT NULL,
  error_message TEXT,
  created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  reprocessed   BOOLEAN       NOT NULL DEFAULT FALSE
);

-- ─────── indexes ──────────────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_orders_product_id      ON orders(product_id);
CREATE INDEX IF NOT EXISTS idx_orders_status          ON orders(status);
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished     ON outbox_events(published) WHERE published = FALSE;
CREATE INDEX IF NOT EXISTS idx_dlt_reprocessed        ON dlt_events(reprocessed) WHERE reprocessed = FALSE;

-- ─────── seed data: 1000 products with matching inventory rows ─────────────────

INSERT INTO products (name, description, price, stock_level)
SELECT
  'Product-' || gs,
  'Description for product ' || gs,
  round((random() * 200 + 1)::numeric, 2),
  (random() * 400 + 100)::int
FROM generate_series(1, 1000) gs
ON CONFLICT DO NOTHING;

INSERT INTO inventory (product_id, stock_level, reserved_qty)
SELECT id, stock_level, 0
FROM products
ON CONFLICT (product_id) DO NOTHING;
