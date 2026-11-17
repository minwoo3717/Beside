// failure/f3_uploads.js: every bad upload gets the documented status and error.code.
//   node --test "scripts/load/*.test.mjs"
import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { BASE_ENV, HERE, PHOTO, TMP, fakeServer, lib, load, samples, state } from './testing/harness.mjs';

// The photo filenames of each POST /api/v1/jobs the script sent, by its case tag (setup's job has none).
function sentNames() {
  return state.requests.filter((r) => r.method === 'POST').map((r) => ({
    case: r.tags.case || r.tags.endpoint,
    names: [...Buffer.from(r.body).toString('latin1').matchAll(/filename="([^"]*)"/g)].map((x) => x[1]),
  }));
}

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
  const server = fakeServer({ verifyMagic: false, proxyLimit: 25000000 });
  state.handler = server;
  mod.bad();
  assert.deepEqual(samples('load_f3MatchRatio').filter((s) => s.value !== 1).map((s) => s.tags.case), []);
  // the two uploads the server accepts without the magic check are named f3acc, so f3bad still means "never stored"
  const stored = Object.keys(server.jobs).map((id) => server.jobs[id].filenames).flat().sort();
  assert.deepEqual(stored, ['lt-unit1-f3acc-fake_jpg-1.jpg', 'lt-unit1-f3acc-gif_as_jpeg-1.jpg']);
});

test('f3_uploads.js: photo names tell rejected uploads from the background job (leftover check)', async () => {
  const mod = await load('failure/f3_uploads.js', { RTT_BASE_MS: '20' }, { clock: 0, openBase: join(HERE, 'failure') });
  const server = fakeServer({ verifyMagic: true });
  state.handler = server;
  mod.setup();
  mod.bad();
  const sent = sentNames();
  // setup's background job: lt-<RUN_ID>-f3bg-<n>.png, the only photos the server stores
  assert.deepEqual(sent[0], { case: 'seed', names: ['lt-unit1-f3bg-1.png', 'lt-unit1-f3bg-2.png'] });
  assert.deepEqual(Object.keys(server.jobs).map((id) => server.jobs[id].filenames).flat(), ['lt-unit1-f3bg-1.png', 'lt-unit1-f3bg-2.png']);
  // every rejected case: lt-<RUN_ID>-f3bad-<case>-<n>
  sent.slice(1).forEach((r) => r.names.forEach((n) => assert.ok(n.startsWith(`lt-unit1-f3bad-${r.case}-`), `${r.case}: ${n}`)));
  assert.deepEqual(sent.slice(1).filter((r) => r.names.length === 0).map((r) => r.case), ['photos_0']);
  assert.equal(sent.find((r) => r.case === 'photos_11').names[10], 'lt-unit1-f3bad-photos_11-11.png');
});

test('f3_uploads.js: a round is a flow and dropped rounds fail the run', async () => {
  const mod = await load('failure/f3_uploads.js', { RTT_BASE_MS: '20', CASES: 'gif,photos_11', F3_VUS: '2', F3_REPEATS: '3' },
    { clock: 0, openBase: join(HERE, 'failure') });
  assert.deepEqual(mod.options.thresholds.dropped_iterations, ['count==0']);
  state.handler = fakeServer({ verifyMagic: true });
  mod.bad();
  assert.equal(samples('load_flowStarted').length, 1);
  assert.equal(samples('load_flowEnded').length, 1);
  assert.equal(samples('load_f3MatchRatio').length, 2);

  const full = { rate: 1, passes: 6, fails: 0 };
  const PASS = (values) => ({ values, thresholds: { 'rate==1': { ok: true } } });
  const metrics = (extra) => Object.assign({
    load_f3MatchRatio: PASS({ rate: 1, passes: 12, fails: 0 }),
    'load_f3MatchRatio{case:gif}': PASS(full),
    'load_f3MatchRatio{case:photos_11}': PASS(full),
    load_flowStarted: { values: { count: 6 } },
    load_flowEnded: { values: { count: 6 } },
    dropped_iterations: { values: { count: 0 }, thresholds: { 'count==0': { ok: true } } },
  }, extra || {});
  const setupData = { readyAtMs: Date.now() - 200000, pollJob: 'job-bg', server: { profile: 'mock,prod' } };
  const summarize = (m) => {
    const out = mod.handleSummary({ metrics: m, setup_data: setupData, state: {} });
    return { doc: JSON.parse(out[BASE_ENV.SUMMARY_PATH]), text: out.stdout };
  };

  const ok = summarize(metrics());
  assert.equal(ok.doc.verdict.thresholdsOk, true);
  assert.equal(ok.doc.verdict.unfinishedFlows, 0);
  assert.equal(ok.doc.meta.pollJob, 'job-bg');
  assert.equal(ok.doc.meta.photoNames.rejected, 'lt-unit1-f3bad-');
  assert.equal(ok.doc.meta.photoNames.background, 'lt-unit1-f3bg-');
  assert.deepEqual(ok.doc.meta.cases.map((c) => [c.name, c.checks, c.expectedChecks, c.photoPrefix]),
    [['photos_11', 6, 6, 'lt-unit1-f3bad-photos_11-'], ['gif', 6, 6, 'lt-unit1-f3bad-gif-']]);
  assert.match(ok.text, /photos_11 +400 TOO_MANY_PHOTOS +match 1 +uploads 6\/6\n/);
  assert.match(ok.text, /"_lt-unit1-f3bad-"/);
  assert.doesNotMatch(ok.text, /FEWER THAN PLANNED/);
  // the slow upload and the leftover check are named where the operator reads the run's result
  assert.match(ok.text, /slow upload \(10 kB\/s\): failure\/f3_throttled\.py, started right before this k6 run/);
  const vm = ok.text.split('\n').find((l) => l.startsWith('  VM:')).slice('  VM:'.length).trim();
  assert.equal(vm, ok.doc.meta.leftoverCheckVm);
  assert.equal(vm, '$L exec -T backend sh -c \'cd /app/storage/uploads && echo "f3bad stored $(ls | grep -cF -- _lt-unit1-f3bad-)"; ' +
    'echo "tomcat dirs $(ls -d /tmp/tomcat.8080.*/work/Tomcat/localhost/ROOT 2>/dev/null | wc -l) upload_tmp ' +
    '$(ls /tmp/tomcat.8080.*/work/Tomcat/localhost/ROOT 2>/dev/null | grep -c ^upload_)"\' | tee "$OUT/f3-leftovers.txt"');
  assert.match(ok.text, /local: failure\/f3_throttled\.py --uploads-dir/);

  // maxDuration stopped 4 rounds before they started: the case rates still read 1, dropped_iterations does not
  const cut = summarize(metrics({
    'load_f3MatchRatio{case:gif}': PASS({ rate: 1, passes: 2, fails: 0 }),
    'load_f3MatchRatio{case:photos_11}': PASS({ rate: 1, passes: 2, fails: 0 }),
    load_flowStarted: { values: { count: 2 } },
    load_flowEnded: { values: { count: 2 } },
    dropped_iterations: { values: { count: 4 }, thresholds: { 'count==0': { ok: false } } },
  }));
  assert.equal(cut.doc.verdict.thresholdsOk, false);
  assert.deepEqual(cut.doc.verdict.failedThresholds, [{ metric: 'dropped_iterations', threshold: 'count==0' }]);
  assert.match(cut.text, /gif +400 UNSUPPORTED_IMAGE_TYPE +match 1 +uploads 2\/6  FEWER THAN PLANNED/);

  // gracefulStop ran out in the middle of a round: not dropped, but the round has no outcome. k6 v2.3.0 kept the
  // cut case's submetric at rate 0 with no sample: that is "not judged" (null), not a 0 % match
  const drained = summarize(metrics({ load_flowStarted: { values: { count: 6 } }, load_flowEnded: { values: { count: 5 } },
    'load_f3MatchRatio{case:gif}': PASS({ rate: 0, passes: 0, fails: 0 }) }));
  assert.equal(drained.doc.verdict.unfinishedFlows, 1);
  assert.equal(drained.doc.verdict.aborted.by, 'unfinished');
  assert.equal(drained.doc.verdict.thresholdsOk, false);
  assert.deepEqual(drained.doc.meta.cases.map((c) => [c.name, c.matchRate, c.checks]), [['photos_11', 1, 6], ['gif', null, 0]]);
  assert.match(drained.text, /gif +400 UNSUPPORTED_IMAGE_TYPE +match - +uploads 0\/6  FEWER THAN PLANNED/);
  assert.match(drained.text, /planned uploads per case: F3_VUS 2 x F3_REPEATS 3 = 6/);
  // the abort reason names GRACEFUL_STOP_S, and F3 reads it
  assert.match(drained.doc.verdict.aborted.reason, /GRACEFUL_STOP_S/);
});

test('f3_uploads.js: a round left running at maxDuration may finish its upload (GRACEFUL_STOP_S)', async () => {
  const dflt = await load('failure/f3_uploads.js', { RTT_BASE_MS: '20' }, { clock: 0, openBase: join(HERE, 'failure') });
  // the 120 s request timeout + 60 s, as upload.js; k6's default 30 s would cut a 30 MB upload on a slow uplink
  assert.equal(dflt.options.scenarios.bad.gracefulStop, '180s');
  const set = await load('failure/f3_uploads.js', { RTT_BASE_MS: '20', GRACEFUL_STOP_S: '400' }, { clock: 0, openBase: join(HERE, 'failure') });
  assert.equal(set.options.scenarios.bad.gracefulStop, '400s');
});

test('f3_uploads.js, f3_throttled.py and README.md document the same command (helper first, k6 exit file, wait)', () => {
  const block = (text, prefix) => {
    const lines = text.split(/\r?\n/);
    const from = lines.findIndex((l) => l.startsWith(`${prefix}newrun f3r1`));
    const to = lines.findIndex((l, i) => i > from && l.startsWith(`${prefix}wait $F3T`));
    assert.ok(from >= 0 && to > from, prefix);
    return lines.slice(from, to + 1).map((l) => l.slice(prefix.length).replace(/\s+#.*$/, '').replace(/\\\\$/, '\\').trim());
  };
  const js = block(readFileSync(join(HERE, 'failure', 'f3_uploads.js'), 'utf8'), '//   ');
  const py = block(readFileSync(join(HERE, 'failure', 'f3_throttled.py'), 'utf8'), '    ');
  // the README's VM block (bash, no comment prefix) is the operator's copy of the same five lines
  const md = block(readFileSync(join(HERE, 'README.md'), 'utf8'), '');
  assert.deepEqual(js, py);
  assert.deepEqual(md, js);
  assert.equal(js.length, 5);
  assert.ok(js[1].startsWith('python scripts/load/failure/f3_throttled.py') && js[2].endsWith('& F3T=$!'), 'the helper first');
  assert.ok(js[3].startsWith('k6 run'), 'then k6');
  assert.match(js.join('\n'), /--k6-exit-file \$RUN\/k6-exit\.txt/);
  assert.match(js[3], /; echo \$\? > \$RUN\/k6-exit\.txt$/);
});
