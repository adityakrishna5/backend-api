# Keycloak Setup & API Integration Guide

## Quick Start

### 1. Start Keycloak & Services
```bash
cd store
docker-compose up -d
```

**Important:** The store-realm.json import happens automatically on first startup. Wait ~30 seconds for Keycloak to fully initialize.

**Service Endpoints:**
- **Keycloak Console**: http://localhost:8180
- **Keycloak Admin**: admin@localhost:8180 (admin/admin)
- **api-read**: http://localhost:8081 ✓ GET /api/v1/products
- **api-rw**: http://localhost:8082 ✓ POST/PUT /api/v1/inventory
- **api-write**: http://localhost:8083 ✓ POST/PUT /api/v1/orders
- **PostgreSQL**: localhost:5432 (store/store)
- **Redis**: localhost:6379
- **Kafka**: localhost:9092

---

## Keycloak Configuration

### Pre-Configured Realm: `store-realm`
- **Roles**: CUSTOMER, MANAGER
- **Users**: 
  - `customer1` / `password` (CUSTOMER role)
  - `manager1` / `password` (MANAGER role)
- **Client**: `store-api` (for API authentication)

### OAuth 2.0 Endpoints
```
Authorization: http://localhost:8180/realms/store-realm/protocol/openid-connect/auth
Token:         http://localhost:8180/realms/store-realm/protocol/openid-connect/token
Logout:        http://localhost:8180/realms/store-realm/protocol/openid-connect/logout
JWKS:          http://localhost:8180/realms/store-realm/protocol/openid-connect/certs
```

---

## Using Postman Collections

### Option 1: Import & Test
1. Import one of these collections into Postman:
   - `Store-APIs-Complete.postman_collection.json` (all 3 APIs)
   - `api-read/ProductCatalogAPI.postman_collection.json`
   - `api-rw/InventoryAPI.postman_collection.json`
   - `api-write/OrderAPI.postman_collection.json`

2. **Get OAuth Token in Postman**:
   - Click the collection name → **Authorization** tab
   - Select **OAuth 2.0**
   - Click **Get New Access Token**
   - Fill dialog:
     - **Token Name**: `store_token`
     - **Grant Type**: `Client Credentials`
     - **Access Token URL**: `http://localhost:8180/realms/store-realm/protocol/openid-connect/token`
     - **Client ID**: `store-api`
     - **Client Secret**: `store-api-secret`
     - Click **Request Token**
   - Token appears → Click **Use Token**

3. Test endpoints (token auto-included in requests)

### Option 2: Manual cURL
```bash
# 1. Get access token
TOKEN=$(curl -X POST \
  http://localhost:8180/realms/store-realm/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=store-api&client_secret=store-api-secret&grant_type=client_credentials" \
  | jq -r '.access_token')

# 2. Use token in API call
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8081/api/v1/products/42
```

---

## API Authentication & Authorization

All endpoints require:
- **Authorization Header**: `Bearer <JWT_TOKEN>`
- **Roles** (enforced by `@PreAuthorize`):

| Endpoint | Method | URL | Required Role |
|----------|--------|-----|---|
| **Get Product** | GET | `/api/v1/products/{id}` | CUSTOMER \| MANAGER |
| **List Products** | GET | `/api/v1/products` | CUSTOMER \| MANAGER |
| **Reserve (Hot)** | POST | `/api/v1/inventory/{id}/reserve-hot` | CUSTOMER \| MANAGER |
| **Reserve (Std)** | PUT | `/api/v1/inventory/{id}/reserve` | CUSTOMER \| MANAGER |
| **Place Order** | POST | `/api/v1/orders` | CUSTOMER \| MANAGER |
| **Update Order** | PUT | `/api/v1/orders/{id}` | **MANAGER only** |

### Test Token Scopes
For testing different roles, use the pre-configured users:

**Customer Token** (read-only):
```bash
curl -X POST http://localhost:8180/realms/store-realm/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=store-api&client_secret=store-api-secret&grant_type=password&username=customer1&password=password"
```

**Manager Token** (read-write):
```bash
curl -X POST http://localhost:8180/realms/store-realm/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=store-api&client_secret=store-api-secret&grant_type=password&username=manager1&password=password"
```

---

## Spring Boot Configuration

APIs are configured to validate JWT from Keycloak:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://keycloak:8080/realms/store-realm
```

The issuer URI is automatically resolved in docker-compose.yml. For local Spring Boot runs (outside Docker), update to:
```yaml
issuer-uri: http://localhost:8180/realms/store-realm
```

---

## Troubleshooting

### APIs returning 401 Unauthorized
- ❌ Token expired → Get new token
- ❌ Wrong realm → Use `http://localhost:8180/realms/**store-realm**`
- ❌ Missing JWT → Add `Authorization: Bearer <token>` header

### Keycloak not accessible
```bash
# Check Keycloak health
curl http://localhost:8180/

# Check container logs
docker logs store-keycloak-1
```

### 403 Forbidden (wrong role)
- ❌ Using CUSTOMER token but endpoint requires MANAGER
- ✓ Use `manager1` user token for write operations

### Postman OAuth flow fails
1. Clear Postman cache: Settings → Clear Cookies
2. Verify Keycloak is healthy: http://localhost:8180
3. Re-enter credentials in OAuth dialog
4. Use **Client Credentials** grant (not Authorization Code)

---

## Custom Realm Configuration

To modify users, roles, or clients, edit `infra/local/store-realm.json`:

1. Update the JSON file
2. Restart Keycloak: `docker-compose restart keycloak`
3. Realm re-imports automatically

**Example: Add new user**
```json
{
  "username": "user2",
  "email": "user2@store.local",
  "enabled": true,
  "credentials": [
    {"type": "password", "value": "password123", "temporary": false}
  ],
  "realmRoles": ["CUSTOMER"]
}
```

---

## Production Checklist

- [ ] Change Keycloak admin password
- [ ] Set `KC_DB` to PostgreSQL (not file-based)
- [ ] Configure SSL/TLS
- [ ] Update JWT issuer URI to production domain
- [ ] Use client secrets from Vault
- [ ] Enable rate limiting & bot protection
- [ ] Set up Keycloak backup & HA
- [ ] Audit logging enabled
