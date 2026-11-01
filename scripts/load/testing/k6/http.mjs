// Stand-in for 'k6/http': synchronous requests answered by state.handler (a fake server written in the test).
// Like discardResponseBodies: true, the body is null unless the request asks for responseType 'text'.
import { advance, state } from './state.mjs';

class Response {
  constructor(status, body, headers, durationMs) {
    this.status = status;
    this.body = body;
    this.headers = headers;
    this.timings = { duration: durationMs, waiting: durationMs * 0.8, sending: 0, receiving: durationMs * 0.2 };
  }

  json(selector) {
    if (typeof this.body !== 'string') throw new Error('json(): no response body (responseType was not text)');
    const doc = JSON.parse(this.body);
    if (!selector) return doc;
    return selector.split('.').reduce((o, k) => (o === null || o === undefined ? undefined : o[k]), doc);
  }
}

function canonical(headers) {
  const out = {};
  Object.keys(headers || {}).forEach((k) => {
    out[k.split('-').map((p) => p.charAt(0).toUpperCase() + p.slice(1).toLowerCase()).join('-')] = String(headers[k]);
  });
  return out;
}

export function request(method, url, body, params) {
  const p = params || {};
  const req = { method, url, body, params: p, headers: Object.assign({}, p.headers || {}), tags: Object.assign({}, p.tags || {}) };
  state.requests.push(req);
  advance(state.requestMs);
  const res = state.handler(req) || { status: 0 };
  const text = res.body === undefined || res.body === null ? '' : String(res.body);
  return new Response(res.status, p.responseType === 'text' ? text : null, canonical(res.headers), state.requestMs);
}

export function get(url, params) {
  return request('GET', url, null, params);
}

export function post(url, body, params) {
  return request('POST', url, body, params);
}

export function batch(reqs) {
  return reqs.map((r) => (Array.isArray(r) ? request(r[0], r[1], r[2], r[3]) : request(r.method, r.url, r.body, r.params)));
}

export function expectedStatuses() {
  return { expectedStatuses: Array.prototype.slice.call(arguments) };
}

export default { request, get, post, batch, expectedStatuses };
