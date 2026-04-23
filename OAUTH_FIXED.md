# ✅ OAuth / Keycloak - Fixed & Working

## Issue & Solution

**Problem:** You were getting `unauthorized` when trying to use the Keycloak OAuth token endpoint.

**Root Cause:** The `store-api` client had incorrect `clientAuthenticatorType` configuration:
- ❌ Was: `client-secret-basic`
- ✅ Fixed: `client-secret`

**Solution Implemented:**
1. Stopped all Docker containers and cleared volumes
2. Updated `infra/local/store-realm.json` with correct client configuration
3. Deleted and recreated the `store-api` client via Keycloak Admin API
4. Verified token generation works

---

## ✅ Status: NOW WORKING

### Token Generation Test
```bash
curl -X POST http://localhost:8180/realms/store-realm/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=store-api&client_secret=store-api-secret&grant_type=client_credentials"

# Returns: 
{
  "access_token": "eyJ...",
  "token_type": "Bearer",
  "expires_in": 300
}
```

---

## Running Everything

### 1. Start All Services
```bash
cd store
docker compose up -d

# Wait ~30 seconds for Kafka to initialize
```

**Services Ready:**
- ✓ Keycloak: http://localhost:8180 (admin / admin)
- ✓ api-read: http://localhost:8081
- ✓ api-rw: http://localhost:8082
- ✓ api-write: http://localhost:8083
- ✓ PostgreSQL: localhost:5432
- ✓ Redis: localhost:6379
- ✓ Kafka: localhost:9092

---

## Using Postman Collection

### Import
1. Open **Store-API-Complete-Keycloak.postman_collection.json** in Postman
2. No additional setup needed - collection has all variables pre-configured

### Get Token
Click: **"🔐 Token Management"** → **"Generate Token (Client Credentials)"** → **Send**
- Token auto-generated and cached
- Will refresh automatically when expired

### Test APIs
All endpoint requests now work with auto-managed OAuth:
- 📖 Product API (api-read @ 8081)
- 📦 Inventory API (api-rw @ 8082)
- 📝 Order API (api-write @ 8083)

---

## Keycloak Configuration

### Pre-Configured Clients
- **store-api**: Main API client (client credentials + password grants)
  - Secret: `store-api-secret`
  - Enabled: All grant types
- **k6-load-test**: Load testing client
  - Secret: `k6-secret-local-only`

### Pre-Configured Users
- **customer1** / password (CUSTOMER role) — read-only access
- **manager1** / password (MANAGER role) — read-write access

### Realm
- **Name**: `store-realm`
- **Roles**: CUSTOMER, MANAGER
- **Token URL**: http://localhost:8180/realms/store-realm/protocol/openid-connect/token
- **Auth URL**: http://localhost:8180/realms/store-realm/protocol/openid-connect/auth

---

## Manual Token Generation (cURL)

### Client Credentials Flow
```bash
curl -X POST http://localhost:8180/realms/store-realm/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=store-api&client_secret=store-api-secret&grant_type=client_credentials"
```

### Password Flow (Customer)
```bash
curl -X POST http://localhost:8180/realms/store-realm/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=store-api&client_secret=store-api-secret&grant_type=password&username=customer1&password=password"
```

### Password Flow (Manager)
```bash
curl -X POST http://localhost:8180/realms/store-realm/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=store-api&client_secret=store-api-secret&grant_type=password&username=manager1&password=password"
```

### Use Token in API Call
```bash
TOKEN=$(curl -s -X POST ... | jq -r .access_token)

curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/v1/products/42
```

---

## Troubleshooting

### "Still getting unauthorized"
✓ Keycloak is running: `docker ps | findstr keycloak`
✓ Token endpoint responds: `curl http://localhost:8180/realms/store-realm`
✓ Try k6-load-test client first to isolate issues

### API returns 401 after token generation
- ❌ Missing header: `Authorization: Bearer <token>`
- ❌ Expired token: Get new token
- ❌ Wrong realm: Verify `store-realm` in URL

### Everything running but Postman fails
1. Clear Postman cookies: Settings → Cookies
2. Restart Postman app
3. Re-run "Generate Token" request

### Keycloak admin console won't authenticate
- URL: http://localhost:8180/admin
- User: `admin`
- Password: `admin`

---

## Next Steps

1. **Import Postman collection**: `Store-API-Complete-Keycloak.postman_collection.json`
2. **Test token generation**: Run any request in the Token Management folder
3. **Test APIs**: Try Product/Inventory/Order endpoints
4. **Monitor logs**: `docker logs store-keycloak-1`

---

## Key Files
- **Postman Collection**: [Store-API-Complete-Keycloak.postman_collection.json](../Store-API-Complete-Keycloak.postman_collection.json)
- **Realm Config**: [infra/local/store-realm.json](infra/local/store-realm.json)
- **Docker Compose**: [docker-compose.yml](docker-compose.yml)
- **Setup Guide**: [KEYCLOAK_SETUP.md](KEYCLOAK_SETUP.md)

---

## Summary

✅ **OAuth 2.0 with Keycloak is now fully functional**
- Token generation works
- APIs validate JWT tokens
- Postman collection ready to use
- All services running and healthy
