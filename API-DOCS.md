# Store — API Documentation

## Services Overview

| Service | Port | Role | Swagger UI |
|---------|------|------|------------|
| **api-read** (Product Catalog) | `8081` | Read products from DB + Redis cache | http://localhost:8081/swagger-ui.html |
| **api-write** (Order Processing) | `8082` | Write orders → Kafka → PostgreSQL | http://localhost:8082/swagger-ui.html |
| **api-rw** (Inventory) | `8083` | Reserve stock via Redis hot path or DB lock | http://localhost:8083/swagger-ui.html |

Raw OpenAPI specs: replace `/swagger-ui.html` → `/v3/api-docs`

---

## Authentication

All endpoints require a **Bearer JWT** from Keycloak.

**Keycloak (local):** http://localhost:8180  
**Realm:** `store-realm`

### Get a token

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/store-realm/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=store-client&username=customer1&password=password" \
  | jq -r .access_token)
```

Use in requests:
```bash
curl -H "Authorization: Bearer $TOKEN" ...
```

Or paste the token in Swagger UI → **Authorize** → Bearer field.

---

## api-read — Product Catalog (`localhost:8081`)

### Endpoints

#### `GET /api/v1/products/{id}`
Returns a single product by ID.  
**Auth:** `ROLE_CUSTOMER` or `ROLE_MANAGER`

**Flow:**
1. Spring Security validates JWT → extracts `realm_access.roles` from Keycloak token
2. `ProductController.getProduct(id)` — annotated with `@CircuitBreaker(name="productService")`
3. `ProductService.getProduct(id)` — `@Cacheable(value="products", key="#id")`
   - **Cache hit** → returns from Redis (TTL: 60s by default)
   - **Cache miss** → `ProductRepository.findById(id)` → PostgreSQL → stored in Redis
4. If DB is unreachable and circuit breaker is OPEN → `fallback()` → HTTP 503 `{status: "DEGRADED"}`

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8081/api/v1/products/1 | jq
```

Expected response:
```json
{
  "status": "OK",
  "data": {
    "id": 1,
    "name": "Product A",
    "price": 29.99,
    "stockLevel": 500
  }
}
```

---

#### `GET /api/v1/products?page=0&size=20`
Returns a paginated list of products.  
**Auth:** `ROLE_CUSTOMER` or `ROLE_MANAGER`

**Flow:**
1. JWT validation (same as above)
2. `ProductService.listProducts(page, size)` — `@Cacheable(value="product-list", key="#page + '-' + #size")`
   - Cache hit → Redis
   - Cache miss → `ProductRepository.findAllPaged(page, size)` → stored in Redis

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/api/v1/products?page=0&size=5" | jq
```

| Param | Default | Description |
|-------|---------|-------------|
| `page` | `0` | Zero-based page index |
| `size` | `20` | Items per page |

---

### Circuit Breaker (api-read)
| Config | Value |
|--------|-------|
| Sliding window | 10 requests |
| Failure threshold | 50% |
| Wait in OPEN state | 10s |

---

## api-write — Order Processing (`localhost:8082`)

### Endpoints

#### `POST /api/v1/orders`
Creates a new order. Returns **202 Accepted** immediately (async).  
**Auth:** `ROLE_CUSTOMER` or `ROLE_MANAGER`

**Flow:**
1. JWT validation
2. `OrderController.createOrder(request)` — receives `{productId, quantity}`
3. `OrderService.publishOrderEvent(payload)` — `@CircuitBreaker(name="kafkaProducer")`
   - **Kafka healthy** → publishes to topic `order-events` → returns `orderId` (UUID)
   - **Kafka circuit open** → `kafkaFallback()` → `OutboxPublisher.publish()` writes to `outbox_events` table in PostgreSQL (at-least-once guarantee across restarts)
4. `OrderConsumer.consumeBatch()` — `@KafkaListener(topics="order-events", batch=true)`, concurrency=3
   - Reads up to 100 messages per poll
   - `OrderRepository.batchInsert()` — single `JdbcTemplate.batchUpdate` into `orders` table
   - Manual-acks after successful DB write

```bash
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"productId": 1, "quantity": 2}' \
  http://localhost:8082/api/v1/orders | jq
```

Expected response (202):
```json
{
  "status": "ACCEPTED",
  "orderId": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

#### `PUT /api/v1/orders/{id}`
Updates an existing order.  
**Auth:** `ROLE_MANAGER` only

**Flow:**
1. JWT validation — must have `ROLE_MANAGER`
2. `OrderService.publishOrderUpdateEvent(id, payload)` — publishes to `order-events` with key `"update-{id}"`
3. Returns `eventId` (UUID) with HTTP 202

```bash
curl -s -X PUT \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"productId": 1, "quantity": 5}' \
  http://localhost:8082/api/v1/orders/123 | jq
```

---

### Outbox Pattern (api-write)
When Kafka is unavailable, events land in `outbox_events`:
```sql
SELECT * FROM outbox_events WHERE processed = false ORDER BY created_at;
```
A background relay process picks these up and re-publishes to Kafka once it recovers.

### Circuit Breaker (api-write)
| Config | Value |
|--------|-------|
| Sliding window | 10 requests |
| Failure threshold | 50% |
| Wait in OPEN state | 10s |

---

## api-rw — Inventory Management (`localhost:8083`)

### Endpoints

#### `POST /api/v1/inventory/{productId}/reserve-hot`
**HOT PATH** — Redis atomic decrement. ~100k ops/sec capacity.  
**Auth:** `ROLE_CUSTOMER` or `ROLE_MANAGER`

**Flow:**
1. JWT validation
2. `InventoryService.reserveStockHot(productId, request)`
3. `StringRedisTemplate.decrement("inventory:stock:{productId}", quantity)`
   - **remaining >= 0** → success → publishes audit event to `inventory-audit` Kafka topic (fire-and-forget)
   - **remaining < 0** → `INCR` by same quantity to restore → throws `IllegalStateException` → HTTP 409
4. Returns `{productId, reserved, remaining}`

```bash
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"quantity": 1}' \
  http://localhost:8083/api/v1/inventory/1/reserve-hot | jq
```

Expected response:
```json
{
  "productId": 1,
  "reserved": 1,
  "remaining": 499
}
```

Out-of-stock (409):
```json
{
  "error": "Insufficient stock"
}
```

---

#### `PUT /api/v1/inventory/{id}/reserve`
**STANDARD PATH** — PostgreSQL pessimistic lock + Redisson distributed lock. ~200 TPS per product.  
**Auth:** `ROLE_CUSTOMER` or `ROLE_MANAGER`

**Flow:**
1. JWT validation
2. `InventoryService.reserveStock(id, request)` — `@Transactional` + `@CacheEvict(value="products", key="#id")`
3. Acquires Redisson `RLock("inventory-lock:{id}")` — 3s wait, 10s lease
4. `inventoryRepository.findByProductIdForUpdate(id)` → `SELECT ... FOR UPDATE`
5. `inventoryRepository.decrementStock(productId, qty)` — JPQL `UPDATE SET stockLevel -= qty WHERE stockLevel >= qty`
   - Returns 0 → throws `IllegalStateException` → HTTP 409
6. Deletes Redis hot-path stock key to re-seed from DB on next request
7. Publishes audit event to `inventory-audit`
8. Releases lock in `finally`

```bash
curl -s -X PUT \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"quantity": 1}' \
  http://localhost:8083/api/v1/inventory/1/reserve | jq
```

---

### Hot Path vs Standard Path

| | `reserve-hot` | `reserve` |
|--|---|---|
| Storage | Redis only | PostgreSQL + Redis evict |
| Throughput | ~100k ops/s | ~200 TPS/product |
| Consistency | Eventually consistent | Strongly consistent |
| Lock type | Redis atomic `DECR` | Redisson `RLock` + `SELECT FOR UPDATE` |
| Use case | Flash sale, high concurrency | Normal checkout |

---

## Request/Response Model

All APIs return responses wrapped in `ApiResponse<T>` from the `common` module:

```json
{
  "status": "OK",
  "data": { ... }
}
```

Error responses:
```json
{
  "status": "DEGRADED",
  "message": "Service unavailable"
}
```

---

## How to Test with Swagger UI

1. Open the Swagger UI for the service you want to test:
   - api-read: http://localhost:8081/swagger-ui.html
   - api-write: http://localhost:8082/swagger-ui.html
   - api-rw: http://localhost:8083/swagger-ui.html

2. Get a token (see [Authentication](#authentication) section above).

3. Click **Authorize** (top-right lock icon in Swagger UI).

4. Paste the token in the **bearerAuth** field (no `Bearer ` prefix — Swagger adds it).

5. Click any endpoint → **Try it out** → fill params → **Execute**.

---

## Actuator / Health Endpoints

Each service exposes these (no auth required):

| Endpoint | Description |
|----------|-------------|
| `/actuator/health` | Liveness + readiness probes |
| `/actuator/health/liveness` | Liveness probe |
| `/actuator/health/readiness` | Readiness probe |
| `/actuator/metrics` | JVM + app metrics |
| `/actuator/prometheus` | Prometheus scrape endpoint |

```bash
curl http://localhost:8081/actuator/health | jq
curl http://localhost:8082/actuator/health | jq
curl http://localhost:8083/actuator/health | jq
```

---

## End-to-End Flow Example

```
Client → [POST /api/v1/orders]                 → api-write (8082)
                                                  └─ Kafka: order-events
                                                        └─ api-write consumer → orders table

Client → [POST /api/v1/inventory/1/reserve-hot] → api-rw (8083)
                                                  └─ Redis DECR inventory:stock:1
                                                  └─ Kafka: inventory-audit (async)

Client → [GET /api/v1/products/1]               → api-read (8081)
                                                  ├─ Redis cache hit → return
                                                  └─ cache miss → PostgreSQL → store in Redis
```

---

## Debug Ports (local)

| Service | Debug Port |
|---------|-----------|
| api-read | `5005` |
| api-write | `5006` |
| api-rw | `5007` |

Attach a remote JVM debugger in your IDE to `localhost:<port>`.
