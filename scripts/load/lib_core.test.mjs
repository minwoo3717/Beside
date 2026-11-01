// Pure k6 libs: config (users -> arrivals), stage tags, multipart bodies, result classes.
//   node --test "scripts/load/*.test.mjs"
import { strict as assert } from 'node:assert';
import { join } from 'node:path';
import { test } from 'node:test';
import { BASE_ENV, HERE, PHOTO, TMP, fakeServer, lib, load, samples, state } from './testing/harness.mjs';

test('stages: levels, segments, tags and expected iterations', async () => {
  const st = await lib('stages.js');
  const lv = st.buildLevels([{ target: 50, rampS: 60, holdS: 480, users: 25 }, { target: 400, rampS: 60, holdS: 480, users: 200 }]);
  assert.deepEqual(lv.stages, [{ duration: '60s', target: 50 }, { duration: '480s', target: 50 }, { duration: '60s', target: 400 }, { duration: '480s', target: 400 }]);
  assert.equal(lv.totalS, 1080);
  assert.deepEqual(st.tagsAt(lv.segments, 1000, 1000 + 30000), { stage: '0', phase: 'ramp' });
  assert.deepEqual(st.tagsAt(lv.segments, 1000, 1000 + 60000), { stage: '0', phase: 'hold' });
  assert.deepEqual(st.tagsAt(lv.segments, 1000, 1000 + 570000), { stage: '1', phase: 'ramp' });
  assert.deepEqual(st.tagsAt(lv.segments, 1000, 1000 + 600000), { stage: '1', phase: 'hold' });
  assert.deepEqual(st.tagsAt(lv.segments, 1000, 1000 + 2000000), { stage: '1', phase: 'drain' });
  assert.deepEqual(st.holdScopes(lv.segments).map((s) => s.scope), ['stage:0,phase:hold', 'stage:1,phase:hold']);
  // (0+50)/2*60/3600 + 50*480/3600 + (50+400)/2*60/3600 + 400*480/3600
  assert.ok(Math.abs(st.expectedIterations(lv.stages, 3600, 0) - (25 / 60 + 50 * 480 / 3600 + 225 / 60 + 400 * 480 / 3600)) < 1e-9);
  const seq = st.sequentialScenarios('rps', [100, 300], 300, 30, (rate) => ({ executor: 'constant-arrival-rate', rate }));
  assert.equal(seq.scenarios.rps1.startTime, '330s');
  assert.deepEqual(seq.scenarios.rps1.tags, { stage: '1', phase: 'hold' });
});

test('config: users -> arrival rates use the T3 ratio of 1,800', async () => {
  const cfg = await lib('config.js');
  const tiers = JSON.parse(open('./tiers.json'));
  assert.equal(cfg.usersPerJobPerS(tiers, 'T3'), 1800);
  assert.equal(cfg.jobsPerHourForUsers(tiers, 'T3', 25), 50);
  assert.equal(cfg.jobsPerHourForUsers(tiers, 'T3', 1800), 3600);
  assert.equal(cfg.browsePerHourForUsers(tiers, 'T3', 1800), 14400); // 5 sessions/s - 1 job/s = 4/s
  assert.equal(cfg.usersPerJobPerS(tiers, 'T2'), 720);
  assert.throws(() => cfg.validRunId('myFailRun', 'fail'), /fail token/);
  assert.throws(() => cfg.validRunId('a b', 'fail'), /RUN_ID/);
  assert.equal(cfg.validRunId('a1r1.x-2', 'fail'), 'a1r1.x-2');
  assert.ok(cfg.isLocalUrl('http://127.0.0.1:18080'));
  assert.ok(!cfg.isLocalUrl('https://beside.example.org'));
  assert.match(cfg.uuid4(), /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.throws(() => cfg.requireRtt(cfg.parseConfig({ BASE_URL: 'https://x.example' }), 's'), /RTT_BASE_MS/);
  assert.equal(cfg.requireRtt(cfg.parseConfig({ BASE_URL: 'http://localhost:1' }), 's'), 0);
});

test('classify: errors, intended rejections and unexpected statuses', async () => {
  const c = await lib('classify.js');
  assert.equal(c.classify(202, [202], null), 'ok');
  assert.equal(c.classify(0, [200], null), 'error');
  assert.equal(c.classify(500, [200], '{"error":{"code":"INTERNAL_ERROR"}}'), 'error');
  assert.equal(c.classify(503, [202], '{"error":{"code":"QUEUE_FULL"}}'), 'rejected');
  assert.equal(c.classify(503, [202], ''), 'error');
  assert.equal(c.classify(429, [202], '{"error":{"code":"RATE_LIMITED"}}'), 'rejected');
  assert.equal(c.classify(404, [200], '{"error":{"code":"JOB_NOT_FOUND"}}'), 'unexpected');
  assert.equal(c.classify(413, [413], null), 'ok');
});

test('multipart: two photos under one field, exact bytes', async () => {
  const mp = await lib('multipart.js');
  const a = new Uint8Array([1, 2, 3]);
  const out = mp.buildMultipart(mp.photoParts('r1', [a, a.buffer]), 'BOUND');
  const text = Buffer.from(out.body).toString('latin1');
  assert.equal(out.contentType, 'multipart/form-data; boundary=BOUND');
  assert.equal(out.bytes, Buffer.from(out.body).length);
  assert.equal((text.match(/name="photos"; filename="lt-r1-\d\.png"/g) || []).length, 2);
  assert.ok(text.endsWith('--BOUND--\r\n'));
  assert.equal(mp.signedBytes('png', 10)[1], 0x50);
  assert.equal(mp.signedBytes('png', 0).length, 0);
  assert.deepEqual(Array.from(mp.utf8('가')), [0xea, 0xb0, 0x80]);
});
