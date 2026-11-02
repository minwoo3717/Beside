// Stand-in for the 'k6' module.
import { advance, state } from './state.mjs';

export function check(value, sets, tags) {
  let all = true;
  Object.keys(sets).forEach((name) => {
    const ok = !!sets[name](value);
    state.checks.push({ name, ok, tags: Object.assign({}, tags || {}) });
    if (!ok) all = false;
  });
  return all;
}

export function sleep(seconds) {
  advance(seconds * 1000);
}

export function fail(message) {
  throw new Error(message);
}

export function group(name, fn) {
  return fn();
}

export default { check, sleep, fail, group };
