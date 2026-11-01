// The Prometheus guard: it ends with the load (not with the drain's upper bound) and reports an unreadable
// Prometheus instead of failing open in silence.
//   node --test "scripts/load/*.test.mjs"
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { BASE_ENV, load, samples, state } from './testing/harness.mjs';

test('guard: one iteration whose length follows the load, not the drain upper bound', async () => {
  const st = await load('api_staged.js', { RTT_BASE_MS: '20', USERS: '25,200', RAMP_S: '10', HOLD_S: '30', GRACEFUL_STOP_S: '30',
    PROM_URL: 'http://localhost:9090' });
  assert.deepEqual(st.options.scenarios.guard, { executor: 'per-vu-iterations', exec: 'guard', vus: 1, iterations: 1, maxDuration: '110s', gracefulStop: '0s' });
  const gq = await load('gpu_queue.js', { RTT_BASE_MS: '20', DURATION_S: '60', PROM_URL: 'http://localhost:9090' });
  assert.equal(gq.options.scenarios.guard.maxDuration, '780s');
  const ps = await load('poll_saturation.js', { RTT_BASE_MS: '20', RPS: '5,5', HOLD_S: '6', GAP_S: '2', PROM_URL: 'http://localhost:9090' });
  assert.equal(ps.options.scenarios.guard.maxDuration, '24s');
  const none = await load('upload.js', { RTT_BASE_MS: '20' });
  assert.equal(none.options.scenarios.guard, undefined);
});

// A fake Prometheus for lib/guard.js: answer(kind) returns { status, body } for kind 'error' or 'disk'.
function prometheus(answer) {
  return (req) => {
    const q = decodeURIComponent(new URL(req.url).searchParams.get('query') || '');
    return answer(q.indexOf('disk_free_bytes') >= 0 ? 'disk' : 'error', req);
  };
}

function vector(v) {
  return { status: 200, body: JSON.stringify({ status: 'success', data: { resultType: 'vector', result: v === null ? [] : [{ metric: {}, value: [0, String(v)] }] } }) };
}

async function guardModule(env) {
  const mod = await load('lib/guard.js', Object.assign({ PROM_URL: 'http://127.0.0.1:9090' }, env || {}), { clock: 0, requestMs: 5 });
  const config = await import('./lib/config.js');
  return { mod, cfg: config.parseConfig(Object.assign({}, BASE_ENV, { PROM_URL: 'http://127.0.0.1:9090' }, env || {})) };
}

function captureWarnings(fn) {
  const real = console.warn;
  const out = [];
  console.warn = (msg) => out.push(String(msg));
  try {
    return { result: fn(), warnings: out };
  } finally {
    console.warn = real;
  }
}

test('guard: guardLoop returns once the load is over, and keeps guarding the drain while flows are in flight', async () => {
  const { mod, cfg } = await guardModule({ GUARD_EVERY_S: '10' });
  state.handler = prometheus((kind) => vector(kind === 'disk' ? 0.3 : null));
  mod.guardLoop(cfg, 30);
  assert.ok(Date.now() >= 30000 && Date.now() < 31000, `returned at ${Date.now()} ms`);
  assert.equal(samples('load_guardQueries').length, 8); // checks at 0, 10, 20, 30 s
  assert.deepEqual(samples('load_guardErrorRatio').map((x) => x.value), [0, 0, 0, 0]); // empty 5xx result = no 5xx
  // flows still in flight after the last stage: the guard goes on until only its own VU is left
  const again = await guardModule({ GUARD_EVERY_S: '10' });
  state.vusActive = 4;
  state.handler = prometheus((kind) => {
    if (state.clock > 45000) state.vusActive = 1;
    return vector(kind === 'disk' ? 0.3 : 0);
  });
  again.mod.guardLoop(again.cfg, 30);
  assert.ok(Date.now() >= 50000 && Date.now() < 51000, `returned at ${Date.now()} ms`);
});

test('guard: 5xx and disk aborts leave their reason in load_guardAbort', async () => {
  const errors = await guardModule();
  state.handler = prometheus((kind) => vector(kind === 'disk' ? 0.3 : 0.2));
  errors.mod.guardCheck(errors.cfg);
  assert.throws(() => errors.mod.guardCheck(errors.cfg), /test aborted: guard: server 5xx ratio 0\.200 > 0\.05 over 1m \(2 checks\)/);
  assert.deepEqual(samples('load_guardAbort').map((x) => x.value), [1]);
  const disk = await guardModule();
  state.handler = prometheus((kind) => vector(kind === 'disk' ? 0.9 : 0));
  assert.throws(() => disk.mod.guardCheck(disk.cfg), /test aborted: guard: disk 90\.0 % > 80 %/);
  assert.deepEqual(samples('load_guardAbort').map((x) => x.value), [2]);
});

test('guard: an unreadable Prometheus is counted and warned about; from the start it aborts, later it does not', async () => {
  const start = await guardModule();
  state.handler = () => ({ status: 500, body: '{"status":"error"}' });
  const first = captureWarnings(() => { start.mod.guardCheck(start.cfg); start.mod.guardCheck(start.cfg); });
  assert.equal(first.warnings.length, 1); // once per streak, not per query
  assert.match(first.warnings[0], /cannot read Prometheus: HTTP 500 from http:\/\/127\.0\.0\.1:9090.*NOT guarded/);
  assert.throws(() => start.mod.guardCheck(start.cfg), /could not be read in the first 3 checks .*unset PROM_URL/);
  assert.equal(samples('load_guardFailures').length, 6);
  assert.deepEqual(samples('load_guardLastFailure').map((x) => x.value).slice(-1), [500]);
  assert.deepEqual(samples('load_guardAbort').map((x) => x.value), [3]);

  const later = await guardModule();
  let broken = false;
  state.handler = prometheus((kind) => (broken ? { status: 200, body: '<html>grafana</html>' } : vector(kind === 'disk' ? 0.3 : 0)));
  later.mod.guardCheck(later.cfg);
  broken = true;
  const mid = captureWarnings(() => { for (let i = 0; i < 6; i++) later.mod.guardCheck(later.cfg); });
  assert.equal(mid.warnings.length, 1);
  assert.match(mid.warnings[0], /not a Prometheus query result/);
  assert.equal(samples('load_guardAbort').length, 0);
  broken = false;
  const back = captureWarnings(() => later.mod.guardCheck(later.cfg));
  assert.match(back.warnings[0], /answers again after 6 unreadable check/);

  // GUARD_BLIND_ABORT=0: never abort for an unreadable Prometheus; an empty disk result counts as unreadable
  const off = await guardModule({ GUARD_BLIND_ABORT: '0' });
  state.handler = prometheus(() => vector(null));
  captureWarnings(() => { for (let i = 0; i < 5; i++) off.mod.guardCheck(off.cfg); });
  assert.equal(samples('load_guardFailures').length, 5);
  assert.deepEqual(samples('load_guardLastFailure').map((x) => x.value).slice(-1), [-2]);
});
