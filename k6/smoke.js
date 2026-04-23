// k6/smoke.js — 2-minute sanity check before load testing
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';

export const options = {
  vus: 5,
  duration: '2m',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

export function setup() {
  const res = http.post(`${__ENV.KEYCLOAK_URL}/protocol/openid-connect/token`, {
    grant_type: 'password',
    client_id: 'store-api',
    client_secret: __ENV.CLIENT_SECRET || 'store-api-secret',
    username: __ENV.TEST_USERNAME || 'customer1',
    password: __ENV.TEST_PASSWORD || 'password',
  });
  if (res.status !== 200) {
    throw new Error(`Token fetch failed: ${res.status} ${res.body}`);
  }
  return { token: res.json('access_token') };
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}` };
  const res = http.get(`${BASE_URL}/api/v1/products/1`, { headers });
  check(res, { 'status is 200': (r) => r.status === 200 });
}
