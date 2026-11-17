// Shared state of the k6 stand-ins used by lib.test.mjs (Node only; k6 never loads this folder).
// The test sets `handler` (a fake server), `env` (__ENV) and `openBase`, then reads `requests` and `samples`.
import { readFileSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';

export const state = globalThis.__k6stub || (globalThis.__k6stub = {
  env: {},
  openBase: process.cwd(),
  handler: () => ({ status: 0, body: '' }),
  requests: [],
  samples: [],
  checks: [],
  clock: null,          // fake clock in ms when set; sleep() and each request advance it
  requestMs: 5,
  scenarioStartTime: 0,
  vusActive: 1,
});

export function reset(fields) {
  state.requests = [];
  state.samples = [];
  state.checks = [];
  state.clock = null;
  state.requestMs = 5;
  state.scenarioStartTime = 0;
  state.vusActive = 1;
  state.handler = () => ({ status: 0, body: '' });
  Object.assign(state, fields || {});
  globalThis.__ENV = state.env;
}

// The scripts read Date.now() for stage tags and the 600 s limit; with a fake clock set, it follows the clock.
const realNow = Date.now;
Date.now = () => (state.clock !== null ? state.clock : realNow());

export function advance(ms) {
  if (state.clock !== null) state.clock += ms;
}

// k6's open(): text by default, ArrayBuffer with 'b'. Relative paths resolve against state.openBase.
globalThis.open = function open(path, mode) {
  const full = isAbsolute(path) ? path : resolve(state.openBase, path);
  const buf = readFileSync(full);
  if (mode === 'b') return buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
  return buf.toString('utf8');
};
globalThis.__ENV = state.env;
