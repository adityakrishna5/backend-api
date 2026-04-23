// k6/load-400k.js — double throughput test (≈6667 req/s, ~400 k rpm)
// Same traffic split as load-200k.js — scale test for capacity validation
import http from 'k6/http';
import { check, randomSeed } from 'k6';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const READ_URL  = __ENV.READ_URL  || 'http://localhost:8081';
const WRITE_URL = __ENV.WRITE_URL || 'http://localhost:8082';
const RW_URL    = __ENV.RW_URL    || 'http://localhost:8083';

export const options = {
  scenarios: {
    load_test: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1m',
      preAllocatedVUs: 1000,
      maxVUs: 3000,
      stages: [
        { target: 1000, duration: '5m' },   // ramp to 50% capacity
        { target: 6667, duration: '15m' },  // ramp to 400k rpm
        { target: 6667, duration: '10m' },  // sustain
        { target: 0,    duration: '5m' },   // ramp down
      ],
    },
  },
  thresholds: {
    http_req_failed:        ['rate<0.01'],
    'http_req_duration{op:read}':  ['p(95)<200', 'p(99)<500'],
    'http_req_duration{op:write}': ['p(95)<500'],
    'http_req_duration{op:rw}':    ['p(95)<800'],
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
  const roll = randomIntBetween(1, 100);

  if (roll <= 85) {
    const productId = randomIntBetween(1, 1000);
    const res = http.get(`${READ_URL}/api/v1/products/${productId}`,
      { headers, tags: { op: 'read' } });
    check(res, { 'read 200': (r) => r.status === 200 });

  } else if (roll <= 97) {
    const payload = JSON.stringify({ productId: randomIntBetween(1, 1000), quantity: 1 });
    const writeHeaders = Object.assign({ 'Content-Type': 'application/json' }, headers);
    const res = http.post(`${WRITE_URL}/api/v1/orders`, payload,
      { headers: writeHeaders, tags: { op: 'write' } });
    check(res, { 'write 202': (r) => r.status === 202 });

  } else {
    const productId = randomIntBetween(1, 200);
    const payload = JSON.stringify({ quantity: 1 });
    const rwHeaders = Object.assign({ 'Content-Type': 'application/json' }, headers);
    const res = http.put(`${RW_URL}/api/v1/inventory/${productId}/reserve`, payload,
      { headers: rwHeaders, tags: { op: 'rw' } });
    check(res, { 'rw 200': (r) => r.status === 200 });
  }
}
