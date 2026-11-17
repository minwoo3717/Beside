// Upload bodies built once per VU from photo bytes the entry script opened in its init context (open() must be
// called there, and each distinct path is opened only once). Pure module.
import { buildMultipart, photoParts } from './multipart.js';

export function openOnce(paths, opener) {
  const cache = {};
  return paths.map((p) => {
    if (!cache[p]) cache[p] = opener(p);
    return cache[p];
  });
}

// { body(): normal body (built on first use), makeFailBody(): body whose filenames carry the fail token }
// The mock worker (and the inference stub's BESIDE_PASSTHROUGH_FAIL_TOKEN) fail a job whose filename contains it.
export function lazyBodies(runId, files, failToken, opts) {
  const o = opts || {};
  let normal = null;
  return {
    body() {
      if (!normal) normal = buildMultipart(photoParts(runId, files, o));
      return normal;
    },
    makeFailBody() {
      return buildMultipart(photoParts(runId, files, Object.assign({}, o, { tag: o.tag ? `${o.tag}-${failToken}` : failToken })));
    },
  };
}

export function totalBytes(files) {
  let n = 0;
  files.forEach((f) => { n += f.byteLength === undefined ? f.length : f.byteLength; });
  return n;
}
