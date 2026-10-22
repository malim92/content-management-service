// k6 load test: sustain ~170 requests/sec (≈10,200 req/min) against the
// Redis-cached GET endpoint. Run via scripts/load-test-10k-per-min.sh.
import http from 'k6/http';

export const options = {
  scenarios: {
    constant_load: {
      executor: 'constant-arrival-rate',
      rate: 170,            // requests per timeUnit
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 200,
      maxVUs: 300,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],       // fewer than 1% errors
    http_req_duration: ['p(99)<1000'],    // p99 under 1s
  },
};

export default function () {
  const res = http.get(__ENV.TARGET_URL);
  if (res.status !== 200) {
    console.error(`Unexpected status ${res.status}`);
  }
}
