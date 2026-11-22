// Small QR Code encoder for share links (ISO/IEC 18004): byte mode, error correction level M, versions 1~10
// (up to 213 UTF-8 bytes, a share URL needs about 60). Follows the structure of Project Nayuki's reference encoder.
// Written here instead of loading a library so the admin pages need no extra CDN script.
// Loaded by /job/index.html as window.BesideQr and by node:test (src/test/js/qr.test.cjs) via require.
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.BesideQr = api;
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  const MIN_VERSION = 1;
  const MAX_VERSION = 10;
  // Level M, index = version (0 unused). ISO/IEC 18004 table 9.
  const ECC_CODEWORDS_PER_BLOCK = [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26];
  const NUM_ERROR_CORRECTION_BLOCKS = [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5];
  const ECC_FORMAT_BITS_M = 0; // L = 1, M = 0, Q = 3, H = 2
  const PENALTY_N1 = 3;
  const PENALTY_N2 = 3;
  const PENALTY_N3 = 40;
  const PENALTY_N4 = 10;

  function getBit(value, index) {
    return ((value >>> index) & 1) !== 0;
  }

  // ------------------------------------------------------------------ Reed-Solomon over GF(2^8), polynomial 0x11D

  function multiply(x, y) {
    let z = 0;
    for (let i = 7; i >= 0; i--) {
      z = (z << 1) ^ ((z >>> 7) * 0x11D);
      z ^= ((y >>> i) & 1) * x;
    }
    return z;
  }

  /** Generator polynomial of the given degree, highest coefficient (always 1) left out. */
  function reedSolomonDivisor(degree) {
    const result = new Array(degree).fill(0);
    result[degree - 1] = 1;
    let root = 1;
    for (let i = 0; i < degree; i++) {
      for (let j = 0; j < result.length; j++) {
        result[j] = multiply(result[j], root);
        if (j + 1 < result.length) result[j] ^= result[j + 1];
      }
      root = multiply(root, 0x02);
    }
    return result;
  }

  /** Error correction codewords of {@code data} for the divisor (the remainder of the polynomial division). */
  function reedSolomonRemainder(data, divisor) {
    const result = divisor.map(() => 0);
    for (const b of data) {
      const factor = b ^ result.shift();
      result.push(0);
      divisor.forEach((coef, i) => { result[i] ^= multiply(coef, factor); });
    }
    return result;
  }

  // ------------------------------------------------------------------ capacities

  function numRawDataModules(version) {
    let result = (16 * version + 128) * version + 64;
    if (version >= 2) {
      const numAlign = Math.floor(version / 7) + 2;
      result -= (25 * numAlign - 10) * numAlign - 55;
      if (version >= 7) result -= 36;
    }
    return result;
  }

  function numDataCodewords(version) {
    return Math.floor(numRawDataModules(version) / 8)
      - ECC_CODEWORDS_PER_BLOCK[version] * NUM_ERROR_CORRECTION_BLOCKS[version];
  }

  function alignmentPositions(version) {
    if (version === 1) return [];
    const size = version * 4 + 17;
    const numAlign = Math.floor(version / 7) + 2;
    const step = Math.ceil((version * 4 + 4) / (numAlign * 2 - 2)) * 2;
    const result = [6];
    for (let pos = size - 7; result.length < numAlign; pos -= step) result.splice(1, 0, pos);
    return result;
  }

  /** 15 format bits for level M and the mask, BCH(15,5) with the 0x5412 XOR mask. */
  function formatBits(mask) {
    const data = (ECC_FORMAT_BITS_M << 3) | mask;
    let rem = data;
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    return ((data << 10) | rem) ^ 0x5412;
  }

  /** 18 version bits (versions 7+), BCH(18,6). */
  function versionBits(version) {
    let rem = version;
    for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
    return (version << 12) | rem;
  }

  // ------------------------------------------------------------------ data

  function utf8(text) {
    return Array.from(new TextEncoder().encode(String(text)));
  }

  /** Mode 0100 (byte), character count, the bytes, terminator and 0xEC/0x11 padding, as data codewords. */
  function dataCodewords(bytes, version) {
    const capacityBits = numDataCodewords(version) * 8;
    const bits = [];
    const append = (value, length) => {
      for (let i = length - 1; i >= 0; i--) bits.push((value >>> i) & 1);
    };
    append(0x4, 4);
    append(bytes.length, version <= 9 ? 8 : 16);
    bytes.forEach((b) => append(b, 8));
    append(0, Math.min(4, capacityBits - bits.length));
    append(0, (8 - (bits.length % 8)) % 8);
    for (let pad = 0xEC; bits.length < capacityBits; pad ^= 0xEC ^ 0x11) append(pad, 8);
    const codewords = [];
    for (let i = 0; i < bits.length; i += 8) {
      codewords.push(bits.slice(i, i + 8).reduce((byte, bit) => (byte << 1) | bit, 0));
    }
    return codewords;
  }

  /** Splits into blocks, appends each block's error correction, and interleaves the blocks column by column. */
  function addEccAndInterleave(data, version) {
    const numBlocks = NUM_ERROR_CORRECTION_BLOCKS[version];
    const blockEccLen = ECC_CODEWORDS_PER_BLOCK[version];
    const rawCodewords = Math.floor(numRawDataModules(version) / 8);
    const numShortBlocks = numBlocks - (rawCodewords % numBlocks);
    const shortBlockLen = Math.floor(rawCodewords / numBlocks);
    const divisor = reedSolomonDivisor(blockEccLen);
    const blocks = [];
    for (let i = 0, k = 0; i < numBlocks; i++) {
      const dat = data.slice(k, k + shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1));
      k += dat.length;
      const ecc = reedSolomonRemainder(dat, divisor);
      if (i < numShortBlocks) dat.push(0);
      blocks.push(dat.concat(ecc));
    }
    const result = [];
    for (let i = 0; i < blocks[0].length; i++) {
      blocks.forEach((block, j) => {
        if (i !== shortBlockLen - blockEccLen || j >= numShortBlocks) result.push(block[i]);
      });
    }
    return result;
  }

  // ------------------------------------------------------------------ matrix

  function createMatrix(version) {
    const size = version * 4 + 17;
    const modules = Array.from({ length: size }, () => new Array(size).fill(false));
    const isFunction = Array.from({ length: size }, () => new Array(size).fill(false));
    const set = (x, y, dark) => {
      modules[y][x] = dark;
      isFunction[y][x] = true;
    };

    for (let i = 0; i < size; i++) {
      set(6, i, i % 2 === 0);
      set(i, 6, i % 2 === 0);
    }
    const finder = (cx, cy) => {
      for (let dy = -4; dy <= 4; dy++) {
        for (let dx = -4; dx <= 4; dx++) {
          const dist = Math.max(Math.abs(dx), Math.abs(dy));
          const x = cx + dx;
          const y = cy + dy;
          if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, dist !== 2 && dist !== 4);
        }
      }
    };
    finder(3, 3);
    finder(size - 4, 3);
    finder(3, size - 4);

    const positions = alignmentPositions(version);
    const n = positions.length;
    for (let i = 0; i < n; i++) {
      for (let j = 0; j < n; j++) {
        if ((i === 0 && j === 0) || (i === 0 && j === n - 1) || (i === n - 1 && j === 0)) continue;
        for (let dy = -2; dy <= 2; dy++) {
          for (let dx = -2; dx <= 2; dx++) set(positions[i] + dx, positions[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
        }
      }
    }

    const matrix = { version, size, modules, isFunction, set };
    drawFormatBits(matrix, 0); // reserves the format areas; redrawn once the mask is chosen
    if (version >= 7) {
      const bits = versionBits(version);
      for (let i = 0; i < 18; i++) {
        const dark = getBit(bits, i);
        const a = size - 11 + (i % 3);
        const b = Math.floor(i / 3);
        set(a, b, dark);
        set(b, a, dark);
      }
    }
    return matrix;
  }

  function drawFormatBits(matrix, mask) {
    const { size, set } = matrix;
    const bits = formatBits(mask);
    for (let i = 0; i <= 5; i++) set(8, i, getBit(bits, i));
    set(8, 7, getBit(bits, 6));
    set(8, 8, getBit(bits, 7));
    set(7, 8, getBit(bits, 8));
    for (let i = 9; i < 15; i++) set(14 - i, 8, getBit(bits, i));
    for (let i = 0; i < 8; i++) set(size - 1 - i, 8, getBit(bits, i));
    for (let i = 8; i < 15; i++) set(8, size - 15 + i, getBit(bits, i));
    set(8, size - 8, true); // the dark module
  }

  /** Codeword bits in the zigzag order: column pairs from the right, alternately upward and downward. */
  function drawCodewords(matrix, codewords) {
    const { size, modules, isFunction } = matrix;
    let i = 0;
    for (let right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let vert = 0; vert < size; vert++) {
        for (let j = 0; j < 2; j++) {
          const x = right - j;
          const upward = ((right + 1) & 2) === 0;
          const y = upward ? size - 1 - vert : vert;
          if (!isFunction[y][x] && i < codewords.length * 8) {
            modules[y][x] = getBit(codewords[i >>> 3], 7 - (i & 7));
            i++;
          }
        }
      }
    }
  }

  function maskBit(mask, x, y) {
    switch (mask) {
      case 0: return (x + y) % 2 === 0;
      case 1: return y % 2 === 0;
      case 2: return x % 3 === 0;
      case 3: return (x + y) % 3 === 0;
      case 4: return (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0;
      case 5: return ((x * y) % 2) + ((x * y) % 3) === 0;
      case 6: return (((x * y) % 2) + ((x * y) % 3)) % 2 === 0;
      case 7: return (((x + y) % 2) + ((x * y) % 3)) % 2 === 0;
      default: throw new RangeError(`mask ${mask}`);
    }
  }

  /** XORs the mask over the data modules; applying it twice undoes it. */
  function applyMask(matrix, mask) {
    const { size, modules, isFunction } = matrix;
    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) {
        if (!isFunction[y][x] && maskBit(mask, x, y)) modules[y][x] = !modules[y][x];
      }
    }
  }

  function penaltyScore(matrix) {
    const { size, modules } = matrix;
    let result = 0;
    const addHistory = (run, history) => {
      if (history[0] === 0) run += size; // light border before the first run
      history.pop();
      history.unshift(run);
    };
    const countPatterns = (history) => {
      const n = history[1];
      const core = n > 0 && history[2] === n && history[3] === n * 3 && history[4] === n && history[5] === n;
      return (core && history[0] >= n * 4 && history[6] >= n ? 1 : 0) + (core && history[6] >= n * 4 && history[0] >= n ? 1 : 0);
    };
    const terminate = (color, run, history) => {
      if (color) {
        addHistory(run, history);
        run = 0;
      }
      addHistory(run + size, history); // light border after the last run
      return countPatterns(history);
    };
    const line = (get) => {
      let color = false;
      let run = 0;
      const history = [0, 0, 0, 0, 0, 0, 0];
      for (let i = 0; i < size; i++) {
        if (get(i) === color) {
          run++;
          if (run === 5) result += PENALTY_N1;
          else if (run > 5) result++;
        } else {
          addHistory(run, history);
          if (!color) result += countPatterns(history) * PENALTY_N3;
          color = get(i);
          run = 1;
        }
      }
      result += terminate(color, run, history) * PENALTY_N3;
    };
    for (let y = 0; y < size; y++) line((x) => modules[y][x]);
    for (let x = 0; x < size; x++) line((y) => modules[y][x]);
    for (let y = 0; y < size - 1; y++) {
      for (let x = 0; x < size - 1; x++) {
        const c = modules[y][x];
        if (c === modules[y][x + 1] && c === modules[y + 1][x] && c === modules[y + 1][x + 1]) result += PENALTY_N2;
      }
    }
    const dark = modules.reduce((sum, row) => sum + row.filter(Boolean).length, 0);
    const total = size * size;
    const k = Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1;
    return result + k * PENALTY_N4;
  }

  /**
   * QR code of {@code text} (UTF-8, byte mode, level M) in the smallest version 1~10 that fits, mask chosen by the
   * standard penalty rules. Returns { version, size, mask, modules } with modules[y][x] true = dark.
   * @throws RangeError when the text needs more than version 10
   */
  function encode(text) {
    const bytes = utf8(text);
    let version = MIN_VERSION;
    for (; version <= MAX_VERSION; version++) {
      if (4 + (version <= 9 ? 8 : 16) + bytes.length * 8 <= numDataCodewords(version) * 8) break;
    }
    if (version > MAX_VERSION) throw new RangeError(`QR: ${bytes.length} bytes do not fit version ${MAX_VERSION}`);
    const matrix = createMatrix(version);
    drawCodewords(matrix, addEccAndInterleave(dataCodewords(bytes, version), version));
    let best = 0;
    let bestScore = Infinity;
    for (let mask = 0; mask < 8; mask++) {
      applyMask(matrix, mask);
      drawFormatBits(matrix, mask);
      const score = penaltyScore(matrix);
      if (score < bestScore) {
        best = mask;
        bestScore = score;
      }
      applyMask(matrix, mask);
    }
    applyMask(matrix, best);
    drawFormatBits(matrix, best);
    return { version, size: matrix.size, mask: best, modules: matrix.modules.map((row) => row.slice()) };
  }

  /** SVG path data with one 1×1 square per dark module, shifted by a quiet-zone border (4 modules by the standard). */
  function svgPath(qr, border = 4) {
    const parts = [];
    for (let y = 0; y < qr.size; y++) {
      for (let x = 0; x < qr.size; x++) {
        if (qr.modules[y][x]) parts.push(`M${x + border},${y + border}h1v1h-1z`);
      }
    }
    return parts.join("");
  }

  return Object.freeze({
    encode, svgPath,
    // Exposed for node:test (known-answer checks of the building blocks).
    internals: Object.freeze({
      multiply, reedSolomonDivisor, reedSolomonRemainder, numDataCodewords, alignmentPositions, formatBits,
      versionBits, dataCodewords, addEccAndInterleave, ECC_CODEWORDS_PER_BLOCK, NUM_ERROR_CORRECTION_BLOCKS
    })
  });
});
