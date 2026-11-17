// Stand-in for 'k6/metrics': every add() is recorded as { metric, value, tags } in state.samples.
import { state } from './state.mjs';

class Metric {
  constructor(name) {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) throw new Error(`invalid k6 metric name ${name}`);
    this.name = name;
  }

  add(value, tags) {
    if (typeof value !== 'number' || Number.isNaN(value)) throw new Error(`${this.name}.add(${value}): not a number`);
    Object.keys(tags || {}).forEach((k) => {
      if (typeof tags[k] !== 'string') throw new Error(`${this.name}: tag ${k} must be a string`);
    });
    state.samples.push({ metric: this.name, value, tags: Object.assign({}, tags || {}) });
  }
}

export class Trend extends Metric {}
export class Rate extends Metric {}
export class Counter extends Metric {}
export class Gauge extends Metric {}
