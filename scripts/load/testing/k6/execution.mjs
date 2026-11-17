// Stand-in for 'k6/execution' (default export) and 'k6/data' (SharedArray), the parts the scripts use.
import { state } from './state.mjs';

const exec = {
  scenario: {
    get startTime() { return state.scenarioStartTime; },
    name: 'test',
  },
  test: {
    abort(message) { throw new Error(`test aborted: ${message}`); },
  },
  // VUs running an iteration in this instance (lib/guard.js stops once only its own is left); the test sets it.
  instance: {
    get vusActive() { return state.vusActive; },
  },
  vu: { idInTest: 1, metrics: { tags: {} } },
};

export default exec;

export class SharedArray {
  constructor(name, fn) {
    const data = fn();
    if (!Array.isArray(data)) throw new Error(`SharedArray ${name}: callback must return an array`);
    return data;
  }
}
