// multipart/form-data bodies built by hand. k6's object form cannot send several files under one field name
// (`photos` x 2) and cannot send a part with a chosen Content-Type and no file, which the failure cases need.
// Pure module (no k6 imports); the result is an ArrayBuffer that k6's http.post sends as is.

export function utf8(text) {
  const out = [];
  for (let i = 0; i < text.length; i++) {
    let code = text.charCodeAt(i);
    if (code >= 0xd800 && code <= 0xdbff && i + 1 < text.length) {
      const low = text.charCodeAt(i + 1);
      if (low >= 0xdc00 && low <= 0xdfff) {
        code = 0x10000 + ((code - 0xd800) << 10) + (low - 0xdc00);
        i++;
      }
    }
    if (code < 0x80) out.push(code);
    else if (code < 0x800) out.push(0xc0 | (code >> 6), 0x80 | (code & 63));
    else if (code < 0x10000) out.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 63), 0x80 | (code & 63));
    else out.push(0xf0 | (code >> 18), 0x80 | ((code >> 12) & 63), 0x80 | ((code >> 6) & 63), 0x80 | (code & 63));
  }
  return new Uint8Array(out);
}

export function toBytes(data) {
  if (data instanceof Uint8Array) return data;
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  if (typeof data === 'string') return utf8(data);
  throw new Error('part data must be a string, ArrayBuffer or Uint8Array');
}

function headerSafe(value) {
  return String(value).replace(/["\r\n]/g, '_');
}

export function randomBoundary() {
  let s = '';
  for (let i = 0; i < 24; i++) s += Math.floor(Math.random() * 16).toString(16);
  return `----beside-load-${s}`;
}

// parts: [{ name, filename?, contentType?, data }] -> { body: ArrayBuffer, contentType, bytes }
export function buildMultipart(parts, boundary) {
  const b = boundary || randomBoundary();
  const chunks = [];
  parts.forEach((p) => {
    let head = `--${b}\r\nContent-Disposition: form-data; name="${headerSafe(p.name)}"`;
    if (p.filename !== undefined && p.filename !== null) head += `; filename="${headerSafe(p.filename)}"`;
    head += '\r\n';
    if (p.contentType) head += `Content-Type: ${p.contentType}\r\n`;
    head += '\r\n';
    chunks.push(utf8(head), toBytes(p.data), utf8('\r\n'));
  });
  chunks.push(utf8(`--${b}--\r\n`));
  let total = 0;
  chunks.forEach((c) => { total += c.length; });
  const out = new Uint8Array(total);
  let offset = 0;
  chunks.forEach((c) => { out.set(c, offset); offset += c.length; });
  return { body: out.buffer, contentType: `multipart/form-data; boundary=${b}`, bytes: total };
}

// `size` bytes that start with a real image signature (the server checks only the first 12 bytes; nothing decodes
// the picture). Used for the size-limit cases where the content does not matter.
const SIGNATURES = {
  png: [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a],
  jpeg: [0xff, 0xd8, 0xff, 0xe0],
  gif: [0x47, 0x49, 0x46, 0x38, 0x39, 0x61],
};

export function signedBytes(kind, size) {
  const sig = SIGNATURES[kind];
  if (!sig) throw new Error(`unknown signature ${kind}`);
  const out = new Uint8Array(Math.max(size, 0));
  for (let i = 0; i < sig.length && i < out.length; i++) out[i] = sig[i];
  return out;
}

// Photos named lt-<runId>-<tag><n>.<ext> so every upload of a run can be found (and removed) by its prefix.
export function photoParts(runId, files, opts) {
  const o = opts || {};
  const tag = o.tag ? `${o.tag}-` : '';
  return files.map((data, i) => ({
    name: 'photos',
    filename: `lt-${runId}-${tag}${i + 1}.${o.ext || 'png'}`,
    contentType: o.contentType || 'image/png',
    data,
  }));
}
