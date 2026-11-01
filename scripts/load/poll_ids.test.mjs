// A2 ids (poll_saturation.js): the setup state check, joined id files, SEED_ONLY runs and the end-of-run re-check.
//   node --test "scripts/load/*.test.mjs"
import { strict as assert } from 'node:assert';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { BASE_ENV, TMP, fakeServer, lib, load, samples, state } from './testing/harness.mjs';

// k6's open() refuses a folder with "open() can't be used with directories, path: ..." (k6 v2.3.0); the stand-in reads
// with Node's readFileSync, which throws EISDIR. Translated here so the IDS_OUT folder check of SEED_ONLY runs as in k6.
const nodeOpen = globalThis.open;
globalThis.open = function open(path, mode) {
  try {
    return nodeOpen(path, mode);
  } catch (e) {
    if (e && e.code === 'EISDIR') throw new Error(`open() can't be used with directories, path: "${path}"`);
    throw e;
  }
};

const C =(i) => `00000000-0000-4000-8000-c${String(i).padStart(11, '0')}`;
const P = (i) => `00000000-0000-4000-8000-d${String(i).padStart(11, '0')}`;

function idsFile(name, doc) {
  const path = join(TMP, name);
  writeFileSync(path, typeof doc === 'string' ? doc : JSON.stringify(doc));
  return path;
}

// A fake server that knows `ids` with the given state; GETs never move a job on (completeAfterPolls null).
function serverWith(states) {
  const server = fakeServer({ completeAfterPolls: null });
  Object.keys(states).forEach((id) => { server.jobs[id] = { polls: 0, fail: false, status: states[id], filenames: [] }; });
  return server;
}

function range(n, f) {
  return Array.from({ length: n }, (_, i) => f(i));
}

test('seed.js checkIds: with states a 200 in another state is bad, and the oldest queuedMs is kept', async () => {
  const seed = await lib('seed.js');
  state.handler = serverWith({ [C(1)]: 'COMPLETED', [P(1)]: 'PENDING', [P(2)]: 'FAILED' });
  const cfg = { baseUrl: BASE_ENV.BASE_URL };
  // without states only the HTTP status counts (and no body is read)
  const plain = seed.checkIds(cfg, [P(1), P(2), P(3)]);
  assert.deepEqual(plain.bad, [{ id: P(3), status: 404, state: null }]);
  assert.equal(plain.maxQueuedMs, null);
  assert.ok(state.requests.every((r) => r.params.responseType === 'none' && r.tags.endpoint === 'setup'));
  const pending = seed.checkIds(cfg, [P(1), P(2), P(3), C(1)], ['PENDING']);
  assert.deepEqual(pending.bad, [{ id: P(2), status: 200, state: 'FAILED' }, { id: P(3), status: 404, state: null },
    { id: C(1), status: 200, state: 'COMPLETED' }]);
  assert.equal(pending.checked, 4);
  assert.equal(pending.maxQueuedMs, 100); // the fake server's timings.queuedMs
  assert.equal(state.requests[state.requests.length - 1].params.responseType, 'text');
});

test('seed.js joinIdLists: files are joined in order, each id once; contradictions and bad shapes are refused', async () => {
  const seed = await lib('seed.js');
  const joined = seed.joinIdLists([{ completed: [C(1), C(2)], pending: [] }, { completed: [], pending: [P(1)] },
    { completed: [C(2)], pending: [P(1), P(2)] }, { completed: [C(3)] }]);
  assert.deepEqual(joined, { completed: [C(1), C(2), C(3)], pending: [P(1), P(2)] });
  assert.throws(() => seed.joinIdLists([{ completed: [C(1)] }, { pending: [C(1)] }], ['c.json', 'p.json']),
    /1 id\(s\) are in both "completed" and "pending"/);
  assert.throws(() => seed.joinIdLists([[C(1)]], ['list.json']), /list\.json: not an object/);
  assert.throws(() => seed.joinIdLists([{ completed: C(1) }], ['x.json']), /x\.json: "completed" is not a list/);
  assert.throws(() => seed.joinIdLists([{ pending: [7] }], ['y.json']), /y\.json: "pending" holds 7/);
});

test('IDS_FILE: pending ids a backend restart turned FAILED (still 200) are refused with the restart named', async () => {
  const file = idsFile('ids-restarted.json', { completed: range(30, C), pending: range(30, P) });
  const mod = await load('poll_saturation.js', { RTT_BASE_MS: '20', IDS_FILE: file }, { clock: 0 });
  const states = {};
  range(30, C).forEach((id) => { states[id] = 'COMPLETED'; });
  range(30, P).forEach((id) => { states[id] = 'FAILED'; }); // InterruptedJobRecovery after a restart
  state.handler = serverWith(states);
  assert.throws(() => mod.setup(), (e) => {
    assert.match(e.message, /^setup: 10 of 20 sampled IDS_FILE ids did not return 200 with their list's state/);
    assert.match(e.message, /10 x pending -> 200 FAILED \(first 00000000-0000-4000-8000-d00000000000\)/);
    assert.match(e.message, /the backend restarted after the PENDING seed/);
    return true;
  });
  // 10 completed + 10 pending were sampled, every one with its body read
  const gets = state.requests.filter((r) => r.url.indexOf('/jobs/') > 0);
  assert.equal(gets.length, 20);
  assert.ok(gets.every((r) => r.tags.endpoint === 'setup' && r.params.responseType === 'text'));
});

test('IDS_FILE: every wrong state is named with its likely cause, grouped by list and state', async () => {
  const file = idsFile('ids-mixed.json', { completed: range(10, C), pending: range(10, P) });
  const mod = await load('poll_saturation.js', { RTT_BASE_MS: '20', IDS_FILE: file }, { clock: 0 });
  const states = {};
  range(10, C).forEach((id) => { states[id] = 'COMPLETED'; });
  range(10, P).forEach((id) => { states[id] = 'PENDING'; });
  states[C(3)] = 'PENDING'; // a pending id in the completed list
  delete states[C(4)]; // 404: another server or an emptied volume
  states[P(1)] = 'PROCESSING'; // pending-ms ran out
  states[P(2)] = 'COMPLETED';
  state.handler = serverWith(states);
  assert.throws(() => mod.setup(), (e) => {
    assert.match(e.message, /4 of 20 sampled IDS_FILE ids/);
    assert.match(e.message, /1 x completed -> 200 PENDING .*1 x completed -> 404 .*1 x pending -> 200 PROCESSING .*1 x pending -> 200 COMPLETED/);
    assert.match(e.message, /use the IDS_OUT of a SEED_COMPLETED run/);
    assert.match(e.message, /another server, or of a volume emptied/);
    assert.match(e.message, /mock\.worker\.pending-ms ran out/);
    return true;
  });
});

test('IDS_FILE: ids in their states start the run; pending-only and completed-only files sample 20', async () => {
  const file = idsFile('ids-ok.json', { completed: range(30, C), pending: range(30, P) });
  const mod = await load('poll_saturation.js', { RTT_BASE_MS: '20', IDS_FILE: file }, { clock: 0 });
  const states = {};
  range(30, C).forEach((id) => { states[id] = 'COMPLETED'; });
  range(30, P).forEach((id) => { states[id] = 'PENDING'; });
  state.handler = serverWith(states);
  const data = mod.setup();
  assert.equal(data.idsChecked, 20);
  assert.equal(data.pendingAgeS, 0.1); // the fake server's queuedMs 100
  const only = await load('poll_saturation.js', { RTT_BASE_MS: '20', IDS_FILE: idsFile('ids-p.json', { pending: range(30, P) }) }, { clock: 0 });
  state.handler = serverWith(states);
  assert.equal(only.setup().idsChecked, 20);
  assert.ok(state.requests.filter((r) => r.url.indexOf('/jobs/') > 0).every((r) => r.url.indexOf('-d') > 0));
});

test('IDS_FILE: two files (the IDS_OUT of a COMPLETED and a PENDING SEED_ONLY run) are joined', async () => {
  const c = idsFile('seed-c.json', { completed: [C(1), C(2)], pending: [] });
  const p = idsFile('seed-p.json', { completed: [], pending: [P(1), P(2), P(3)] });
  const mod = await load('poll_saturation.js', { RTT_BASE_MS: '20', IDS_FILE: `${c},${p}` }, { clock: 0 });
  state.handler = serverWith({ [C(1)]: 'COMPLETED', [C(2)]: 'COMPLETED', [P(1)]: 'PENDING', [P(2)]: 'PENDING', [P(3)]: 'PENDING' });
  const data = mod.setup();
  assert.equal(data.idsChecked, 5);
  const realRandom = Math.random;
  try {
    Math.random = () => 0.9; // >= PENDING_SHARE 0.5: a completed id
    mod.poll(data);
    Math.random = () => 0.1;
    mod.poll(data);
  } finally {
    Math.random = realRandom;
  }
  const polls = state.requests.filter((r) => r.tags.endpoint === 'poll');
  assert.deepEqual(polls.map((r) => r.tags.jobState), ['completed', 'pending']);
  const doc = JSON.parse(mod.handleSummary({ metrics: {}, setup_data: data, state: {} })[BASE_ENV.SUMMARY_PATH]);
  assert.deepEqual(doc.meta.ids.files, [c, p]);
  assert.equal(doc.meta.ids.completed, 2);
  assert.equal(doc.meta.ids.pending, 3);
  assert.equal(doc.meta.ids.checkedInSetup, 5);
  assert.equal(doc.meta.mode, 'load');
  // a contradiction between the files and an unreadable file stop k6 before setup
  const bad = idsFile('seed-bad.json', { completed: [P(1)], pending: [] });
  await assert.rejects(load('poll_saturation.js', { RTT_BASE_MS: '20', IDS_FILE: `${p},${bad}` }), /in both "completed" and "pending"/);
  await assert.rejects(load('poll_saturation.js', { RTT_BASE_MS: '20', IDS_FILE: 'no-such-ids.json' }),
    /IDS_FILE no-such-ids\.json cannot be read .*relative path from scripts\/load\//);
  await assert.rejects(load('poll_saturation.js', { RTT_BASE_MS: '20', IDS_FILE: idsFile('ids-text.json', 'not json') }), /is not JSON/);
});

test('SEED_ONLY: setup seeds and handleSummary writes IDS_OUT; no load, no thresholds, no RTT needed', async () => {
  const out = join(TMP, 'seed-only-c.json');
  const remote = { BASE_URL: 'https://beside.example.org', SEED_ONLY: '1', SEED_COMPLETED: '3', IDS_OUT: out };
  const mod = await load('poll_saturation.js', remote, { clock: 0 });
  assert.deepEqual(Object.keys(mod.options.scenarios), ['seedOnly']);
  assert.deepEqual(mod.options.scenarios.seedOnly, { executor: 'shared-iterations', exec: 'seedOnly', vus: 1, iterations: 1, maxDuration: '60s' });
  assert.deepEqual(mod.options.thresholds, {});
  state.handler = fakeServer({ completeAfterPolls: 1 });
  const data = mod.setup();
  assert.equal(data.completed.length, 3);
  mod.seedOnly(data);
  mod.teardown(data); // nothing to re-check in a seed-only run
  assert.equal(samples('load_pendingRechecked').length, 0);
  const files = mod.handleSummary({ metrics: {}, setup_data: data, state: {} });
  assert.deepEqual(JSON.parse(files[out]), { completed: data.completed, pending: [] });
  // k6 writes IDS_OUT after handleSummary and may fail to: the line does not claim the write, and says where else the ids are
  assert.match(files.stdout, /^SEED ONLY: 3 COMPLETED and 0 PENDING ids for IDS_OUT .*seed-only-c\.json \(also in SUMMARY_PATH under k6\.setup_data; a k6 error "could not open" for this path means IDS_OUT was not written\); no load ran/);
  assert.doesNotMatch(files.stdout, /written to IDS_OUT/);
  const doc = JSON.parse(files[BASE_ENV.SUMMARY_PATH]);
  assert.equal(doc.meta.mode, 'seed-only');
  assert.deepEqual(doc.k6.setup_data.completed, data.completed);
  assert.deepEqual(doc.meta.stages, []);
  assert.equal(doc.meta.loadPlannedS, 0);
  assert.equal(doc.verdict.breakingPoint.lastPassingStage, null);
  // a measured run against a remote BASE_URL still needs RTT_BASE_MS
  await assert.rejects(load('poll_saturation.js', { BASE_URL: 'https://beside.example.org', IDS_OUT: out }), /RTT_BASE_MS/);
  await assert.rejects(load('poll_saturation.js', { SEED_ONLY: '1' }), /SEED_ONLY needs IDS_OUT/);
  await assert.rejects(load('poll_saturation.js', { SEED_ONLY: '1', IDS_OUT: out, IDS_FILE: idsFile('x.json', { completed: [C(1)] }) }),
    /cannot be combined with IDS_FILE/);
  await assert.rejects(load('poll_saturation.js', { SEED_ONLY: '1', IDS_OUT: out, SEED_COMPLETED: '0' }), /seeds nothing/);
});

test('SEED_ONLY: IDS_OUT must be an absolute file path in an existing folder, checked before anything is seeded', async () => {
  const seedOnly = (idsOut) => load('poll_saturation.js', { SEED_ONLY: '1', SEED_COMPLETED: '2', IDS_OUT: idsOut });
  // k6 opens a relative path from scripts/load/ but writes the summary's from the current folder: it cannot be checked
  await assert.rejects(seedOnly('out/a2/c.json'), /SEED_ONLY needs an absolute IDS_OUT .*: out\/a2\/c\.json/);
  // a missing folder: k6 would seed, log "could not open" for IDS_OUT and still exit 0
  const missing = join(TMP, 'no-such-run', 'c.json');
  await assert.rejects(seedOnly(missing), (e) => {
    assert.match(e.message, /^IDS_OUT folder .*no-such-run cannot be used \(ENOENT.*\): create it first, or k6 would seed/);
    return true;
  });
  assert.equal(state.requests.length, 0); // refused in init, before setup sent anything
  // a folder given as IDS_OUT (with or without the trailing slash), or a file where the folder should be
  const run = join(TMP, 'a2-run');
  mkdirSync(run, { recursive: true });
  await assert.rejects(seedOnly(run), /^Error: IDS_OUT .*a2-run is a folder: name a file in it/);
  await assert.rejects(seedOnly(`${run}/`), /IDS_OUT .*a2-run\/ is a folder/);
  const file = idsFile('not-a-folder', 'x');
  await assert.rejects(seedOnly(join(file, 'c.json')), /IDS_OUT folder .*not-a-folder is a file, not a folder/);
  // an absolute file in an existing folder, new or written before, starts the run
  const ok = await seedOnly(join(run, 'c.json'));
  assert.deepEqual(Object.keys(ok.options.scenarios), ['seedOnly']);
  writeFileSync(join(run, 'c.json'), '{}');
  await seedOnly(join(run, 'c.json'));
  // a seeding load run keeps its relative IDS_OUT (README: IDS_OUT=$RUN/ids.json); its ids are in the summary as well
  const loadRun = await load('poll_saturation.js', { RTT_BASE_MS: '20', SEED_COMPLETED: '3', IDS_OUT: 'docs/perf/load/data/x/ids.json' });
  assert.ok(loadRun.options.scenarios.rps0);
});

test('a seeding load run with PENDING ids only is refused unless PENDING_SHARE=1 asks for it', async () => {
  // the old A2 step "SEED_PENDING=1000 SEED_COMPLETED=0 ... IDS_OUT" without SEED_ONLY ran the whole 33-minute load
  await assert.rejects(load('poll_saturation.js', { RTT_BASE_MS: '20', SEED_PENDING: '1000', SEED_COMPLETED: '0', IDS_OUT: join(TMP, 'p.json') }),
    /SEED_PENDING with no COMPLETED seed would run the whole A2 load on PENDING ids only: .*add -e SEED_ONLY=1 .*set PENDING_SHARE=1/);
  await assert.rejects(load('poll_saturation.js', { RTT_BASE_MS: '20', SEED_PENDING: '4' }), /PENDING ids only/); // SEED_COMPLETED defaults to 0
  const share = await load('poll_saturation.js', { RTT_BASE_MS: '20', SEED_PENDING: '4', PENDING_SHARE: '1' });
  assert.ok(share.options.scenarios.rps0);
  // both seeds, a SEED_ONLY run and a pending-only IDS_FILE are not affected
  assert.ok((await load('poll_saturation.js', { RTT_BASE_MS: '20', SEED_PENDING: '4', SEED_COMPLETED: '4' })).options.scenarios.rps0);
  assert.ok((await load('poll_saturation.js', { SEED_ONLY: '1', SEED_PENDING: '4', IDS_OUT: join(TMP, 'p.json') })).options.scenarios.seedOnly);
  assert.ok((await load('poll_saturation.js', { RTT_BASE_MS: '20', IDS_FILE: idsFile('ids-p-only.json', { pending: [P(1)] }) })).options.scenarios.rps0);
});

test('SEED_PENDING: no COMPLETED seed unless asked, and the new jobs must still be PENDING', async () => {
  const out = join(TMP, 'seed-only-p.json');
  const mod = await load('poll_saturation.js', { SEED_ONLY: '1', SEED_PENDING: '4', IDS_OUT: out }, { clock: 0 });
  state.handler = fakeServer({ completeAfterPolls: null }); // a backend with a long pending-ms
  const data = mod.setup();
  assert.equal(state.requests.filter((r) => r.method === 'POST').length, 4); // SEED_COMPLETED defaults to 0 here
  assert.deepEqual(data.completed, []);
  assert.equal(data.pending.length, 4);
  assert.equal(data.seedReport.pendingChecked, 4);
  assert.deepEqual(JSON.parse(mod.handleSummary({ metrics: {}, setup_data: data, state: {} })[out]), { completed: [], pending: data.pending });

  // pending-ms 0 (the A-series default): the jobs are PROCESSING a moment after they are created
  const again = await load('poll_saturation.js', { SEED_ONLY: '1', SEED_PENDING: '4', IDS_OUT: out }, { clock: 0 });
  const server = fakeServer({ completeAfterPolls: null });
  state.handler = (req) => {
    const res = server(req);
    if (req.method === 'POST' && res.status === 202) server.jobs[JSON.parse(res.body).jobId].status = 'PROCESSING';
    return res;
  };
  assert.throws(() => again.setup(), /4 of 4 sampled SEED_PENDING jobs \(just created\) did not return 200 .*4 x pending -> 200 PROCESSING/);

  // both seeds asked for on a backend that keeps jobs PENDING: the COMPLETED seed never completes and setup says why
  const both = await load('poll_saturation.js', { RTT_BASE_MS: '20', SEED_COMPLETED: '2', SEED_PENDING: '2', SEED_WAIT_S: '10' }, { clock: 0 });
  state.handler = fakeServer({ completeAfterPolls: null });
  assert.throws(() => both.setup(), /none of the 2 SEED_COMPLETED jobs completed within SEED_WAIT_S 10 s .*mock\.worker\.pending-ms 0/);
});

test('teardown: pending ids that left PENDING during the run fail load_pendingLeft and are reported', async () => {
  const file = idsFile('ids-end.json', { completed: range(5, C), pending: range(5, P) });
  const mod = await load('poll_saturation.js', { RTT_BASE_MS: '20', IDS_FILE: file, RPS: '5', HOLD_S: '6', GAP_S: '0' }, { clock: 0 });
  assert.deepEqual(mod.options.thresholds.load_pendingLeft, ['count==0']);
  const states = {};
  range(5, C).forEach((id) => { states[id] = 'COMPLETED'; });
  range(5, P).forEach((id) => { states[id] = 'PENDING'; });
  const server = serverWith(states);
  state.handler = server;
  const data = mod.setup();
  mod.teardown(data);
  assert.deepEqual(samples('load_pendingRechecked').map((s) => s.value), [5]);
  assert.deepEqual(samples('load_pendingLeft').map((s) => s.value), [0]);
  // pending-ms ran out during the run: two of them completed
  server.jobs[P(0)].status = 'PROCESSING';
  server.jobs[P(3)].status = 'COMPLETED';
  state.samples = [];
  mod.teardown(data);
  assert.deepEqual(samples('load_pendingLeft').map((s) => s.value), [2]);
  assert.ok(state.requests.slice(-5).every((r) => r.tags.endpoint === 'setup'));
  // an answer that tells nothing (5xx, no response) is neither PENDING nor left: it is not counted as re-checked
  state.samples = [];
  state.handler = (req) => (req.url.endsWith(P(4)) ? { status: 503, body: '' } : server(req));
  mod.teardown(data);
  assert.deepEqual(samples('load_pendingRechecked').map((s) => s.value), [4]);
  assert.deepEqual(samples('load_pendingLeft').map((s) => s.value), [2]);
  const metrics = {
    'http_req_duration{endpoint:poll,stage:0,phase:hold}': { values: { med: 3, 'p(95)': 5, 'p(99)': 6, count: 30 }, thresholds: { 'p(95)<120': { ok: true } } },
    load_pendingRechecked: { values: { count: 5 } },
    load_pendingLeft: { values: { count: 2 }, thresholds: { 'count==0': { ok: false } } },
  };
  const files = mod.handleSummary({ metrics, setup_data: Object.assign({}, data, { readyAtMs: Date.now() - 7000 }), state: {} });
  const doc = JSON.parse(files[BASE_ENV.SUMMARY_PATH]);
  assert.deepEqual(doc.meta.ids.endCheck, { checked: 5, leftPending: 2 });
  assert.equal(doc.verdict.thresholdsOk, false);
  assert.deepEqual(doc.verdict.failedThresholds, [{ metric: 'load_pendingLeft', threshold: 'count==0' }]);
  assert.deepEqual(doc.verdict.stages.map((s) => s.status), ['pass']); // the step itself still ran and passed
  assert.match(files.stdout, /pending ids: 2 of 5 sampled were no longer PENDING when the load ended/);
  // no pending ids: nothing to re-check, and the summary says so with null
  const none = await load('poll_saturation.js', { RTT_BASE_MS: '20', IDS_FILE: idsFile('ids-c-only.json', { completed: range(5, C) }) }, { clock: 0 });
  state.handler = serverWith(states);
  const d2 = none.setup();
  none.teardown(d2);
  assert.equal(samples('load_pendingRechecked').length, 0);
  assert.equal(JSON.parse(none.handleSummary({ metrics: {}, setup_data: d2, state: {} })[BASE_ENV.SUMMARY_PATH]).meta.ids.endCheck, null);
});
