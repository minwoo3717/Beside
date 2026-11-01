// failure/f3_uploads.js: every bad upload gets the documented status and error.code.
//   node --test "scripts/load/*.test.mjs"
import { strict as assert } from 'node:assert';
import { join } from 'node:path';
import { test } from 'node:test';
import { BASE_ENV, HERE, PHOTO, TMP, fakeServer, lib, load, samples, state } from './testing/harness.mjs';

test('f3_uploads.js: every bad upload gets the documented status and code', async () => {
  const mod = await load('failure/f3_uploads.js', { RTT_BASE_MS: '20' }, { clock: 0, openBase: join(HERE, 'failure') });
  state.handler = fakeServer({ verifyMagic: true });
  const data = mod.setup();
  mod.bad();
  const match = samples('load_f3MatchRatio');
  assert.equal(match.length, 9);
  assert.deepEqual(match.filter((s) => s.value !== 1).map((s) => s.tags.case), []);
  mod.background(data);
  assert.equal(state.requests[state.requests.length - 1].tags.endpoint, 'poll');
});

test('f3_uploads.js: without the magic check and behind a 25 MB proxy', async () => {
  const mod = await load('failure/f3_uploads.js', { RTT_BASE_MS: '20', VERIFY_MAGIC: '0', BASE_URL: 'https://beside.example.org' },
    { clock: 0, openBase: join(HERE, 'failure') });
  state.handler = fakeServer({ verifyMagic: false, proxyLimit: 25000000 });
  mod.bad();
  assert.deepEqual(samples('load_f3MatchRatio').filter((s) => s.value !== 1).map((s) => s.tags.case), []);
});
