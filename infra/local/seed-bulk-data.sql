-- infra/local/seed-bulk-data.sql
-- Expands the product/inventory dataset from 1k to 10k rows for load testing.
-- Safe to run multiple times (ON CONFLICT DO NOTHING).
--
-- Usage:
--   docker exec store-postgres-1 psql -U store -d store -f /dev/stdin < infra/local/seed-bulk-data.sql
-- Or via psql directly:
--   psql -h localhost -U store -d store -f infra/local/seed-bulk-data.sql

-- ─── extend products to 10,000 rows ─────────────────────────────────────────
INSERT INTO products (name, description, price, stock_level)
SELECT
  'Product-' || gs,
  'Description for product ' || gs,
  round((random() * 500 + 5)::numeric, 2),
  (random() * 900 + 100)::int
FROM generate_series(1001, 10000) gs
ON CONFLICT DO NOTHING;

-- ─── create matching inventory rows ─────────────────────────────────────────
INSERT INTO inventory (product_id, stock_level, reserved_qty)
SELECT id, stock_level, 0
FROM products WHERE id > 1000
ON CONFLICT (product_id) DO NOTHING;

-- ─── insert 500 sample orders ────────────────────────────────────────────────
INSERT INTO orders (order_id, product_id, quantity, status)
SELECT
  gen_random_uuid()::text,
  (random() * 9999 + 1)::int,
  (random() * 5 + 1)::int,
  (ARRAY['PENDING','CONFIRMED','SHIPPED','DELIVERED'])[floor(random()*4+1)]
FROM generate_series(1, 500);

-- ─── verify ──────────────────────────────────────────────────────────────────
SELECT 'products' AS table_name, COUNT(*) FROM products
UNION ALL
SELECT 'inventory', COUNT(*) FROM inventory
UNION ALL
SELECT 'orders',    COUNT(*) FROM orders;
