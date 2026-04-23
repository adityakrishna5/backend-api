# Store Platform — Implementation Reference

**Target throughput:** 200,000 req/min (~3,333 req/sec)  
**Stack:** Java 21 · Spring Boot 3.3 · PostgreSQL 15 · Redis 7 · Kafka 3.7 · Keycloak 24 · GKE

---

## Architecture Overview

```
Client
  └─► GKE Ingress (GCE L7 LB, TLS)
        ├─► /api/v1/products   → api-read  :8081  (85% traffic)
        ├─► /api/v1/orders     → api-write :8082  (12% traffic)
        └─► /api/v1/inventory  → api-rw    :8083  (3% traffic)

Backing services (same VPC):
  PostgreSQL 15  (Cloud SQL, private IP, Auth Proxy sidecar)
  Redis 7        (Memorystore, private IP)
  Kafka 3.7      (KRaft mode, 12 partitions)
  Keycloak 24    (in-cluster or HA pair)
```

Traffic split is intentional:
- **85% reads** → served from Redis cache → near-zero DB load
- **12% writes** → Kafka publish (async, 202 Accepted) → consumers persist
- **3% inventory** → Redisson distributed lock + DB `SELECT FOR UPDATE`

---

## Multi-Module Layout

```
store/
├── pom.xml              (parent, Spring Boot 3.3, Java 21)
├── common/              (shared DTOs, exceptions, security utils, filter)
├── api-read/            (Product Catalog — read-only)
├── api-write/           (Order API — Kafka + Outbox)
├── api-rw/              (Inventory API — dual-path stock reservation)
├── infra/
│   ├── local/           (init.sql, store-realm.json)
│   └── k8s/             (Deployment, Service, HPA, PDB, Ingress, SA per service)
├── k6/                  (smoke.js, load-200k.js, load-400k.js)
└── docker-compose.yml
```

---

## Common Module (`com.store.common`)

### DTOs
| Class | Fields |
|---|---|
| `ApiResponse<T>` | `correlationId`, `status`, `data`, `timestamp (Instant)` |
| `OrderRequest` | `productId (Long)`, `quantity (Integer)` |
| `InventoryUpdateRequest` | `quantity (Integer)` |
| `ProductDto` | *(mapped from Product entity)* |

### `GlobalExceptionHandler` (`@RestControllerAdvice`)
| Exception | HTTP Status |
|---|---|
| `IllegalArgumentException` | 400 Bad Request |
| `NoSuchElementException` | **404 Not Found** |
| `IllegalStateException` | 409 Conflict |
| `AccessDeniedException` | 403 Forbidden |
| `Exception` (catch-all) | 500 Internal Server Error |

### `CorrelationIdFilter`
Reads `X-Correlation-ID` from inbound request (or generates UUID). Puts it in MDC for log correlation and echoes it in the response header.

### `JwtClaimsExtractor`
Utility for extracting `realm_access.roles` and `sub` from Keycloak JWTs. Used by `SecurityConfig` in each service.

---

## api-read — Product Catalog (port 8081)

**Purpose:** High-throughput read-only product access with Redis-first caching.

### Endpoints
| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/products/{id}` | CUSTOMER or MANAGER | Get product by ID. Redis cache first, DB on miss. Circuit breaker → 503. |
| GET | `/api/v1/products?page=0&size=20` | CUSTOMER or MANAGER | Paginated product list. Cached by page+size key. |

### Key Classes
- **`ProductService`** — `@Cacheable("products", key="#id")` and `@Cacheable("product-list", key="#page+'-'+#size")`; `@Transactional(readOnly=true)`
- **`ProductRepository`** — `JpaRepository<Product, Long>` with `findAllPaged(page, size)` via Spring Data `PageRequest`
- **`Product`** — `@Entity` on table `products`; fields: `id, name, description, price, stockLevel, createdAt`
- **`RedisConfig`** — `RedisCacheManager` with `GenericJackson2JsonRedisSerializer` + `JavaTimeModule` (fixes `Instant` serialization); TTL from `cache.redis.time-to-live` (60s local, 600s GCP)
- **`DataSourceConfig`** — empty; Hikari auto-configured (pool: 20 max / 5 min)
- **`OpenApiConfig`** — Swagger UI with `bearerAuth` security scheme

### Resilience
- **Circuit breaker** `productService`: sliding window 10, failure threshold 50%, open wait 10s
- Fallback: `ResponseEntity<ApiResponse<String>>` with status `DEGRADED` and HTTP 503

### Configuration
```yaml
server.shutdown: graceful
spring.threads.virtual.enabled: true   # Java 21 virtual threads
spring.lifecycle.timeout-per-shutdown-phase: 30s
resilience4j.circuitbreaker.instances.productService:
  slidingWindowSize: 10
  failureRateThreshold: 50
  waitDurationInOpenState: 10s
```

---

## api-write — Order API (port 8082)

**Purpose:** Async order placement and update via Kafka with transactional outbox fallback.

### Endpoints
| Method | Path | Auth | Returns |
|---|---|---|---|
| POST | `/api/v1/orders` | CUSTOMER or MANAGER | 202 Accepted + `{orderId}` UUID |
| PUT | `/api/v1/orders/{id}` | **MANAGER only** | 202 Accepted + `{eventId}` UUID |

### Key Classes
- **`OrderService`**
  - `publishOrderEvent(payload)` — `@CircuitBreaker(name="kafkaProducer", fallbackMethod="kafkaFallback")`. Sends to topic `order-events`. On async publish failure: writes to outbox.
  - `publishOrderUpdateEvent(id, payload)` — `@CircuitBreaker(name="kafkaProducer", fallbackMethod="kafkaUpdateFallback")`. Sends to `order-events` with key `"update-{id}"`. Fallback writes to outbox.
  - `kafkaFallback(payload, t)` — writes to outbox_events via `OutboxPublisher`
  - `kafkaUpdateFallback(id, payload, t)` — outbox fallback for the update path

- **`OutboxPublisher`** — `JdbcTemplate` INSERT into `outbox_events (aggregate_id, event_type, payload jsonb, created_at, published)`. Payload serialized with `ObjectMapper`.

- **`OrderConsumer`** — `@KafkaListener(topics="order-events", batch=true)`, `@Transactional`. Calls `OrderRepository.batchInsert(messages)` — single SQL batch round-trip. On failure: forwards all messages in the batch to `order-events.DLT` topic via `KafkaTemplate`, then acks. No messages are lost.

- **`OutboxSweeper`** — `@Scheduled(fixedDelay=5000)`, `@Transactional`. Polls `outbox_events WHERE published=false ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED`. For each row: `kafkaTemplate.send(topic, payload).get()` (synchronous), then `UPDATE published=true`. Runs in api-write; enabled by `@EnableScheduling` on `OrderApplication`.

- **`OrderRepository`** — JDBC `batchUpdate` INSERT into `orders`. Handles both `OrderRequest` and `Map<?,?>` (from Kafka `JsonDeserializer`).

- **`Order`** — `@Entity` on table `orders`; fields: `id, orderId (UUID), productId, quantity, status, createdAt`

### Kafka Producer Config (`KafkaConfig`)
| Setting | Value |
|---|---|
| acks | all |
| idempotence | true |
| retries | 3 |
| max.in.flight.requests.per.connection | 5 |
| linger.ms | 5 |
| batch.size | 64 KB |
| compression.type | **gzip** (changed from snappy — Alpine JRE lacks native lib) |
| ack mode | MANUAL_IMMEDIATE |
| concurrency | 3 threads per pod |
| max.poll.records | 100 |

### Outbox Table
```sql
outbox_events (
  id bigserial PK,
  aggregate_id varchar(255),   -- UUID of the event
  event_type varchar(128),     -- Kafka topic name
  payload jsonb NOT NULL,
  published boolean DEFAULT false,
  created_at timestamptz
)
-- Partial index on unpublished rows
CREATE INDEX idx_outbox_unpublished ON outbox_events(published) WHERE published = FALSE;
```

> **Implemented:** `OutboxSweeper` polls every 5 seconds using `FOR UPDATE SKIP LOCKED` (safe for multi-replica pods) and republishes pending events synchronously before marking them published.

---

## api-rw — Inventory API (port 8083)

**Purpose:** Dual-path stock reservation — high-throughput Redis path and consistent DB path.

### Endpoints
| Method | Path | Auth | Path Type |
|---|---|---|---|
| POST | `/api/v1/inventory/{productId}/reserve-hot` | CUSTOMER or MANAGER | Hot: Redis DECR |
| PUT | `/api/v1/inventory/{id}/reserve` | CUSTOMER or MANAGER | Standard: DB lock |

### Hot Path — Redis DECR
1. `stringRedisTemplate.opsForValue().decrement(key, qty)` — atomic, ~0.1ms
2. If result < 0: `increment(key, qty)` to undo, throw `IllegalStateException` ("Out of stock") → 409
3. Publish async audit event to `inventory-audit` topic (non-fatal on failure)

**Recovery:** Redis counter can drift from DB on JVM crash after DECR but before Kafka publish. Reconciliation from the `orders` table must be done manually.

**Key in Redis:** `inventory:stock:{productId}`

### Standard Path — Redisson + SELECT FOR UPDATE
1. `@CircuitBreaker(name="inventoryService")` — sliding window 10, 50% threshold, 10s open wait
2. `redissonClient.getLock("inventory-lock:{id}").tryLock(3s wait, 10s lease)` — throws `IllegalStateException` (→ 409) on timeout
3. `inventoryRepository.findByProductIdForUpdate(id)` → `SELECT ... FOR UPDATE` — throws `IllegalStateException` (→ 409) if product not found
4. `inventoryRepository.decrementStock(id, qty)` — JPQL UPDATE with `stockLevel >= qty` guard
5. Evict Redis key so the hot path re-seeds from DB on next access
6. Publish audit event to `inventory-audit`
7. Fallback `reserveStockFallback` returns 503 when circuit is OPEN

**Double lock:** Redisson RLock (distributed) + JPA `@Lock(PESSIMISTIC_WRITE)` + JPA `@Version` (optimistic, third line of defence).

**`InventoryStockSeeder`** — `@EventListener(ApplicationReadyEvent.class)`. On startup: `SELECT product_id, stock_level FROM inventory WHERE stock_level > 0` → for each row runs a Lua script atomically: sets `"inventory:stock:{id}"` to the DB value **only if the key is absent OR its current value is ≤ 0**. This handles both cold starts (missing key → DECR returns -1) and previously-drained keys (value=0 despite DB having stock). Positive keys are left untouched, making it safe for rolling restarts.

### Key Classes
- **`Inventory`** — `@Entity` on table `inventory`; fields: `id, productId, stockLevel, reservedQty, version (@Version)`
- **`InventoryRepository`** — `findByProductIdForUpdate` + `decrementStock` JPQL; `reserveStock` default method dispatches to `decrementStock`
- **`RedisConfig`** — `RedissonClient` (pool 5 min / 20 max, 3s timeout) + `RedisCacheManager` for `@CacheEvict`

### Configuration
```yaml
spring.datasource.hikari:
  maximum-pool-size: 10
  minimum-idle: 3
kafka.producer.compression.type: gzip   # was snappy — Alpine JRE fix
cache.redis.time-to-live: 60000         # 60s local, 600s GCP
```

---

## Authentication & Authorization

**Provider:** Keycloak 24, realm `store-realm`, client `store-api` (client secret: `store-api-secret`)

**Token flow:** Password grant (user-facing and k6 load tests via `customer1`)

**JWT claim:** `realm_access.roles` → `["CUSTOMER"]` or `["MANAGER"]`

**SecurityConfig** (identical in all three services):
- `@EnableMethodSecurity(prePostEnabled = true)`
- JWT converter maps `realm_access.roles` → `ROLE_CUSTOMER`, `ROLE_MANAGER` as `SimpleGrantedAuthority`
- `/actuator/health/**` and `/swagger-ui/**`, `/v3/api-docs/**` are public
- All other endpoints require authentication; role enforcement via `@PreAuthorize`

**Users:**
| User | Password | Role | Access |
|---|---|---|---|
| `customer1` | `password` | CUSTOMER | GET products, POST orders, reserve inventory |
| `manager1` | `password` | MANAGER | All of above + PUT orders |

---

## Database Schema

```sql
products (id, name, description, price, stock_level, created_at, updated_at)
inventory (id, product_id FK→products, stock_level, reserved_qty, version)
orders (id, order_id UNIQUE, product_id, quantity, status, created_at)
outbox_events (id, aggregate_id, event_type, payload JSONB, published, created_at)
```

Indexes: `idx_orders_product_id`, `idx_orders_status`, `idx_outbox_unpublished` (partial, `WHERE published = FALSE`)

Seed: 1,000 products and matching inventory rows generated by `generate_series(1,1000)`. Run `infra/local/seed-bulk-data.sql` to expand to **10,000 products** with matching inventory (idempotent, `ON CONFLICT DO NOTHING`). Also inserts 500 sample orders.

---

## Infrastructure

### Local (Docker Compose)
| Service | Port |
|---|---|
| PostgreSQL 15 | 5432 |
| Redis 7 | 6379 |
| Kafka 3.7 (KRaft) | 9092 |
| Keycloak 24 | 8180 → 8080 |
| api-read | **8081** → 8080 |
| api-write | **8082** → 8080 |
| api-rw | **8083** → 8080 |

Kafka: single-node KRaft (no ZooKeeper), 12 partitions, 1 replica factor.

### GKE (Production)
| Component | Details |
|---|---|
| Ingress | GCE L7, global static IP, Google-managed TLS cert, HTTP→HTTPS only |
| DB | Cloud SQL PostgreSQL 15, private IP, Cloud SQL Auth Proxy sidecar (Workload Identity) |
| Redis | Memorystore (private IP) |
| Kafka | Confluent Cloud or Bitnami Helm (headless service) |
| Keycloak | In-cluster or external HA pair |
| Registry | Artifact Registry (`REGION-docker.pkg.dev/PROJECT_ID/store/`) |

### HPA (Horizontal Pod Autoscaler)
| Service | Min | Max | CPU target | Scale-up |
|---|---|---|---|---|
| api-read | 2 | 20 | 70% | +4 pods / 60s |
| api-write | 2 | 10 | 70% | +2 pods / 60s |
| api-rw | 2 | 8 | **65%** (lock-sensitive) | +2 pods / 60s |

All: scale-down stabilization 300s.

### Workload Identity
Each service has its own KSA (`store-api-read`, `store-api-write`, `store-api-rw`) annotated with a GCP service account.  
Required GCP roles per SA: `roles/cloudsql.client`, `roles/monitoring.metricWriter`.

---

## Observability

### Metrics
Micrometer → `micrometer-registry-new-relic` → pushes to New Relic every 30s (`step: 30s`).  
Local: disabled via `management.newrelic.metrics.export.enabled: false`; use `/actuator/metrics` instead.

Actuator endpoints exposed: `health`, `info`, `metrics`, `prometheus`  
Liveness/readiness probes: `/actuator/health/liveness`, `/actuator/health/readiness`

### Logging
| Profile | Format | Level |
|---|---|---|
| `local` | Plain text console `%d{HH:mm:ss} [%thread] %level %logger - %msg` | DEBUG |
| `gcp` | Logstash JSON (logstash-logback-encoder) → STDOUT → Fluent Bit | WARN (root) / INFO (com.store) |

GCP JSON log fields: `timestamp`, `message`, `logger`, `level`, `service`, `env`, `app`, `X-Correlation-ID` (from MDC).

### Correlation IDs
`CorrelationIdFilter` reads or generates `X-Correlation-ID` per request, populates `MDC`, echoes in response header. Propagated throughout logs.

---

## Load Testing (k6)

### `smoke.js`
- 5 VUs, 2 minutes
- GET `/api/v1/products/1` only (api-read)
- Thresholds: `p(95) < 500ms`, error rate < 1%
- Auth: `password` grant, `client_id=store-api`, `username=customer1`; env vars `CLIENT_SECRET`, `TEST_USERNAME`, `TEST_PASSWORD`
- Fails fast if token fetch returns non-200

### `load-200k.js`
- Ramping arrival rate: 0 → 1000 → **3,333 req/s** → hold 10m → ramp down
- Max VUs: 1,500; pre-allocated: 500
- Traffic mix: 85% read / 12% write / 3% inventory
- Thresholds: read p(95)<200ms, write p(95)<500ms, rw p(95)<800ms
- Auth: same `password` grant as smoke.js

---

## Implemented Fixes (all gaps resolved)

| # | Description | File(s) | Status |
|---|---|---|---|
| 1 | Outbox sweeper | `api-write/.../outbox/OutboxSweeper.java` + `@EnableScheduling` on `OrderApplication` | ✅ |
| 2 | `publishOrderUpdateEvent` circuit breaker + outbox fallback | `OrderService.java` | ✅ |
| 3 | `pom.xml` java.version 17 → 21 | `store/pom.xml` | ✅ |
| 4 | k6 `client_credentials` → `password` grant with `customer1` | `smoke.js`, `load-200k.js`, `load-400k.js` | ✅ |
| 5 | `NoSuchElementException` → 404 | `GlobalExceptionHandler.java` | ✅ |
| 6 | `RuntimeException` → `IllegalStateException` in `InventoryService` | `InventoryService.java` | ✅ |
| 7 | `OrderConsumer` batch DLT routing (`order-events.DLT`) | `OrderConsumer.java` | ✅ |
| 8 | Redis stock key seeded on startup via Lua CAS (set-if-absent-or-zero) | `api-rw/.../startup/InventoryStockSeeder.java` | ✅ |
| 9 | `ApiResponse.ok()` / `accepted()` populate `correlationId` from MDC (requires JAR rebuild) | `ApiResponse.java` | ✅ |
| 10 | `@CircuitBreaker` on `reserveStock` + resilience4j config in api-rw | `InventoryService.java`, `api-rw/application.yml` | ✅ |
| 11 | `OutboxSweeper` queried wrong column `topic` → fixed to `event_type` | `OutboxSweeper.java` | ✅ |
| 12 | `InventoryStockSeeder` `setIfAbsent` (NX) skipped drained keys → Lua CAS fix | `InventoryStockSeeder.java` | ✅ |
