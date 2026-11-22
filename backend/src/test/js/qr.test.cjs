// QR encoder of the job detail page (static/job/qr.js). Run: node --test backend/src/test/js/qr.test.cjs
// Known answers for the building blocks (Reed-Solomon, format and version bits, alignment positions), then a reader
// written separately here that takes the symbol apart again: function areas, zigzag order, mask, blocks, data.
const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const qr = require(path.resolve(__dirname, "../../main/resources/static/job/qr.js"));
const I = qr.internals;

test("Reed-Solomon matches the textbook 1-M example (HELLO WORLD)", () => {
  const data = [32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17];
  assert.deepEqual(I.reedSolomonRemainder(data, I.reedSolomonDivisor(10)), [196, 35, 39, 119, 235, 215, 231, 226, 93, 23]);
});

test("format and version bits match the standard tables", () => {
  const bits = (value, width) => value.toString(2).padStart(width, "0");
  assert.equal(bits(I.formatBits(0), 15), "101010000010010"); // M, mask 0
  assert.equal(bits(I.formatBits(1), 15), "101000100100101"); // M, mask 1
  assert.equal(bits(I.formatBits(4), 15), "100010111111001"); // M, mask 4
  assert.equal(I.versionBits(7), 0x07C94);
  assert.equal(I.versionBits(8), 0x085BC);
});

test("capacities and alignment positions of versions 1~10 at level M", () => {
  assert.deepEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map(I.numDataCodewords), [16, 28, 44, 64, 86, 108, 124, 154, 182, 216]);
  assert.deepEqual(I.alignmentPositions(1), []);
  assert.deepEqual(I.alignmentPositions(2), [6, 18]);
  assert.deepEqual(I.alignmentPositions(7), [6, 22, 38]);
  assert.deepEqual(I.alignmentPositions(10), [6, 28, 50]);
});

// ------------------------------------------------------------------ reader

const MASKS = [
  (x, y) => (x + y) % 2 === 0,
  (x, y) => y % 2 === 0,
  (x) => x % 3 === 0,
  (x, y) => (x + y) % 3 === 0,
  (x, y) => (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0,
  (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
  (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0,
  (x, y) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0
];

function isFunctionModule(x, y, version, size) {
  if (x < 9 && y < 9) return true;                     // top-left finder, separator, format
  if (x >= size - 8 && y < 9) return true;             // top-right finder, separator, format row
  if (x < 9 && y >= size - 8) return true;             // bottom-left finder, separator, format column, dark module
  if (x === 6 || y === 6) return true;                 // timing
  if (version >= 7 && ((x >= size - 11 && x < size - 8 && y < 6) || (y >= size - 11 && y < size - 8 && x < 6))) return true;
  const centers = I.alignmentPositions(version);
  for (const cx of centers) {
    for (const cy of centers) {
      const overlapsFinder = (cx === 6 && cy === 6) || (cx === 6 && cy === size - 7) || (cx === size - 7 && cy === 6);
      if (!overlapsFinder && Math.abs(x - cx) <= 2 && Math.abs(y - cy) <= 2) return true;
    }
  }
  return false;
}

/** Takes a symbol apart: format copies, mask, codewords in zigzag order, blocks, then the byte-mode payload. */
function read(symbol) {
  const { size, modules } = symbol;
  const version = (size - 17) / 4;
  const dark = (x, y) => modules[y][x];

  let first = 0;
  let second = 0;
  const firstCells = [[8, 0], [8, 1], [8, 2], [8, 3], [8, 4], [8, 5], [8, 7], [8, 8], [7, 8], [5, 8], [4, 8], [3, 8], [2, 8], [1, 8], [0, 8]];
  firstCells.forEach(([x, y], i) => { if (dark(x, y)) first |= 1 << i; });
  for (let i = 0; i < 8; i++) if (dark(size - 1 - i, 8)) second |= 1 << i;
  for (let i = 8; i < 15; i++) if (dark(8, size - 15 + i)) second |= 1 << i;
  assert.equal(first, second, "both format copies agree");
  const mask = [0, 1, 2, 3, 4, 5, 6, 7].find((m) => I.formatBits(m) === first);
  assert.notEqual(mask, undefined, "format bits are level M with a known mask");
  assert.equal(mask, symbol.mask);
  assert.equal(dark(8, size - 8), true, "dark module");

  const bits = [];
  let upward = true;
  for (let right = size - 1; right > 0; right -= 2) {
    if (right === 6) right = 5;
    for (let step = 0; step < size; step++) {
      const y = upward ? size - 1 - step : step;
      for (const x of [right, right - 1]) {
        if (!isFunctionModule(x, y, version, size)) bits.push(dark(x, y) !== MASKS[mask](x, y) ? 1 : 0);
      }
    }
    upward = !upward;
  }
  const codewords = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) codewords.push(bits.slice(i, i + 8).reduce((b, bit) => (b << 1) | bit, 0));

  const numBlocks = I.NUM_ERROR_CORRECTION_BLOCKS[version];
  const eccLen = I.ECC_CODEWORDS_PER_BLOCK[version];
  const total = codewords.length;
  const dataTotal = I.numDataCodewords(version);
  const shortData = Math.floor(dataTotal / numBlocks);
  const longBlocks = dataTotal % numBlocks;
  const dataLengths = Array.from({ length: numBlocks }, (_, b) => shortData + (b >= numBlocks - longBlocks ? 1 : 0));
  const blocks = dataLengths.map(() => []);
  let at = 0;
  for (let i = 0; i < shortData + 1; i++) {
    blocks.forEach((block, b) => { if (i < dataLengths[b]) block.push(codewords[at++]); });
  }
  const eccs = dataLengths.map(() => []);
  for (let i = 0; i < eccLen; i++) eccs.forEach((ecc) => ecc.push(codewords[at++]));
  assert.equal(at, dataTotal + numBlocks * eccLen);
  assert.ok(total - at < 1, "no codewords left over");
  const divisor = I.reedSolomonDivisor(eccLen);
  blocks.forEach((block, b) => assert.deepEqual(eccs[b], I.reedSolomonRemainder(block, divisor), `block ${b} error correction`));

  const dataBits = blocks.flat().flatMap((b) => Array.from({ length: 8 }, (_, i) => (b >> (7 - i)) & 1));
  let pos = 0;
  const take = (n) => {
    let value = 0;
    for (let i = 0; i < n; i++) value = (value << 1) | dataBits[pos++];
    return value;
  };
  assert.equal(take(4), 0b0100, "byte mode");
  const count = take(version <= 9 ? 8 : 16);
  const bytes = Array.from({ length: count }, () => take(8));
  return { version, mask, text: new TextDecoder().decode(Uint8Array.from(bytes)) };
}

function assertFinder(symbol, left, top) {
  for (let dy = 0; dy < 7; dy++) {
    for (let dx = 0; dx < 7; dx++) {
      const ring = Math.max(Math.abs(dx - 3), Math.abs(dy - 3));
      assert.equal(symbol.modules[top + dy][left + dx], ring !== 2, `finder at ${left},${top}`);
    }
  }
}

test("symbols read back to the same text, from version 1 to 10", () => {
  const cases = [
    { text: "Beside", version: 1 },
    { text: "https://beside.example.duckdns.org/s/q3Zr8yT0bWm1xK4vL9pN2A", version: 4 },
    { text: "https://beside.example.duckdns.org/s/" + "x".repeat(113), version: 8 },
    // 210 UTF-8 bytes: version 10, the first with a 16-bit character count.
    { text: "반려동물 3D 모델 공유 링크 ".repeat(5) + "/s/q3Zr8yT0bWm1xK4vL9pN2A", version: 10 }
  ];
  for (const { text, version } of cases) {
    const symbol = qr.encode(text);
    assert.equal(symbol.version, version, text);
    assert.equal(symbol.size, version * 4 + 17);
    assertFinder(symbol, 0, 0);
    assertFinder(symbol, symbol.size - 7, 0);
    assertFinder(symbol, 0, symbol.size - 7);
    for (let i = 8; i < symbol.size - 8; i++) {
      assert.equal(symbol.modules[6][i], i % 2 === 0, "horizontal timing");
      assert.equal(symbol.modules[i][6], i % 2 === 0, "vertical timing");
    }
    assert.equal(read(symbol).text, text);
  }
});

test("a text over version 10 is refused, and the SVG path draws one square per dark module", () => {
  assert.throws(() => qr.encode("x".repeat(214)), RangeError);
  assert.equal(qr.encode("x".repeat(213)).version, 10);
  const symbol = qr.encode("Beside");
  const squares = qr.svgPath(symbol, 4).match(/M\d+,\d+h1v1h-1z/g);
  const darkCount = symbol.modules.flat().filter(Boolean).length;
  assert.equal(squares.length, darkCount);
  assert.match(qr.svgPath(symbol, 4), /^M4,4h1v1h-1z/, "top-left finder corner after the 4-module border");
});
