"""Deterministic benchmark fixtures: PNG photos and a synthetic GLB (standard library only).

Every generator is a pure function of its arguments (a seeded random.Random, never the global one), so the same
GENERATOR_VERSION always produces the same bytes on any OS. ensure() caches the bytes in the work dir under a key made
from the generator name, its arguments and GENERATOR_VERSION, stores the SHA-256 of the content next to the file and
checks it on every reuse, so a damaged or hand-edited cache file is rebuilt instead of measured.
"""
from __future__ import annotations

import hashlib
import json
import os
import random
import struct
import zlib
from pathlib import Path

# Bump when a generator changes its output; the cache key includes it.
GENERATOR_VERSION = 1

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
GLB_MAGIC = b"glTF"
GLB_JSON = 0x4E4F534A  # "JSON"
GLB_BIN = 0x004E4942   # "BIN\0"
IDAT_CHUNK = 64 * 1024  # bytes of compressed data per IDAT chunk, like common encoders


def _chunk(kind: bytes, data: bytes) -> bytes:
    """One PNG chunk: big-endian length, type, data, CRC-32 over type + data."""
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def png_rgb(width: int, height: int, rows, level: int = 6) -> bytes:
    """An 8-bit RGB PNG from an iterable of `height` raw rows (each width*3 bytes), filter type 0 on every row."""
    if width <= 0 or height <= 0:
        raise ValueError("width and height must be positive")
    row_len = width * 3
    compressor = zlib.compressobj(level)
    parts = []
    count = 0
    for row in rows:
        if len(row) != row_len:
            raise ValueError(f"row {count} has {len(row)} bytes, expected {row_len}")
        parts.append(compressor.compress(b"\x00" + bytes(row)))
        count += 1
    if count != height:
        raise ValueError(f"got {count} rows, expected {height}")
    parts.append(compressor.flush())
    idat = b"".join(parts)
    # IHDR: width, height, bit depth 8, colour type 2 (RGB), compression 0, filter method 0, no interlace.
    out = [PNG_SIGNATURE, _chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))]
    for start in range(0, len(idat), IDAT_CHUNK):
        out.append(_chunk(b"IDAT", idat[start:start + IDAT_CHUNK]))
    out.append(_chunk(b"IEND", b""))
    return b"".join(out)


def png_noise(width: int, height: int, seed: int = 1) -> bytes:
    """RGB noise: incompressible, so the file is about width*height*3 bytes (1230x1230 -> ~4.5 MB)."""
    rng = random.Random(seed)
    return png_rgb(width, height, (rng.randbytes(width * 3) for _ in range(height)))


def png_gradient(width: int = 4000, height: int = 3000, seed: int = 1, noise_every: int = 256) -> bytes:
    """A smooth gradient with tiny noise (+-1 on about one byte in `noise_every`): many pixels, small file.

    Stands in for a phone photo: the server must inflate width*height*3 bytes to decode it while the upload stays
    well under the 5 MB per-photo limit. Rows are built with big-integer XOR so 12 MP takes well under a second.
    """
    if width < 2 or height < 2:
        raise ValueError("width and height must be at least 2")
    rng = random.Random(seed)
    row_len = width * 3
    base = bytearray(row_len)
    base[0::3] = bytes(x * 255 // (width - 1) for x in range(width))               # red rises left to right
    base[2::3] = bytes(255 - x * 191 // (width - 1) for x in range(width))         # blue falls left to right
    masks = []
    for _ in range(32):
        mask = bytearray(row_len)
        for index in rng.sample(range(row_len), max(1, row_len // noise_every)):
            mask[index] = 1  # flips the lowest bit: a +-1 change of one channel
        masks.append(int.from_bytes(mask, "big"))

    def rows():
        for y in range(height):
            base[1::3] = bytes((y * 255 // (height - 1),)) * width  # green rises top to bottom
            noisy = int.from_bytes(base, "big") ^ masks[rng.randrange(len(masks))]
            yield noisy.to_bytes(row_len, "big")

    return png_rgb(width, height, rows())


def glb_synthetic(total_bytes: int = 1_500_000, seed: int = 1) -> bytes:
    """A structurally valid glTF 2.0 binary of about total_bytes (exactly, when it is a multiple of 4).

    12-byte header (magic "glTF", version 2, total length), a JSON chunk with a minimal asset and one buffer whose
    byteLength is the BIN chunk length (padded with spaces to 4 bytes), and a BIN chunk of deterministic bytes.
    The backend only checks the magic and the version, but a glTF loader can open this file as well.
    """
    if total_bytes < 64:
        raise ValueError("total_bytes must be at least 64")
    bin_len = 0
    json_bytes = b""
    for _ in range(8):  # the digits of byteLength change the JSON length; settle in a few rounds
        doc = {"asset": {"version": "2.0", "generator": "beside-perf fixtures"}, "buffers": [{"byteLength": bin_len}]}
        json_bytes = json.dumps(doc, separators=(",", ":")).encode("ascii")
        json_bytes += b" " * (-len(json_bytes) % 4)
        new_bin_len = (total_bytes - 12 - 8 - len(json_bytes) - 8) // 4 * 4
        if new_bin_len == bin_len:
            break
        bin_len = new_bin_len
    if bin_len <= 0:
        raise ValueError("total_bytes too small for the JSON chunk")
    payload = random.Random(seed).randbytes(bin_len)  # bin_len is a multiple of 4: no zero padding needed
    length = 12 + 8 + len(json_bytes) + 8 + bin_len
    return b"".join([
        GLB_MAGIC, struct.pack("<II", 2, length),
        struct.pack("<II", len(json_bytes), GLB_JSON), json_bytes,
        struct.pack("<II", bin_len, GLB_BIN), payload,
    ])


def parse_glb_header(data: bytes) -> dict:
    """Magic, version, declared length and the two chunk headers (used by tests and by the runner's sanity check)."""
    magic, (version, length) = data[:4], struct.unpack("<II", data[4:12])
    json_len, json_type = struct.unpack("<II", data[12:20])
    json_doc = json.loads(data[20:20 + json_len].decode("ascii"))
    offset = 20 + json_len
    bin_len, bin_type = struct.unpack("<II", data[offset:offset + 8])
    return {"magic": magic, "version": version, "length": length, "jsonLength": json_len, "jsonType": json_type,
            "json": json_doc, "binLength": bin_len, "binType": bin_type}


def png_size(data: bytes) -> tuple:
    """(width, height) from the IHDR chunk of a PNG."""
    if data[:8] != PNG_SIGNATURE or data[12:16] != b"IHDR":
        raise ValueError("not a PNG")
    return struct.unpack(">II", data[16:24])


GENERATORS = {"png_noise": png_noise, "png_gradient": png_gradient, "glb_synthetic": glb_synthetic}
EXTENSIONS = {"png_noise": ".png", "png_gradient": ".png", "glb_synthetic": ".glb"}

# The fixtures the scenarios use: name -> (generator, arguments).
STANDARD = {
    "png_small": ("png_noise", {"width": 64, "height": 64, "seed": 11}),
    "png_large": ("png_noise", {"width": 1230, "height": 1230, "seed": 12}),
    "png_12mp": ("png_gradient", {"width": 4000, "height": 3000, "seed": 13}),
    "glb_sample": ("glb_synthetic", {"total_bytes": 1_500_000, "seed": 14}),
}


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def cache_key(generator: str, args: dict) -> str:
    text = json.dumps({"generator": generator, "args": args, "version": GENERATOR_VERSION}, sort_keys=True)
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def ensure(work_dir, name: str, generator: str, args: dict) -> dict:
    """The fixture file for (generator, args) under <work_dir>/fixtures, generated once and verified by SHA-256.

    Returns {"name", "path", "bytes", "sha256", "generator", "args"}.
    """
    if generator not in GENERATORS:
        raise ValueError(f"unknown generator {generator!r}")
    folder = Path(work_dir) / "fixtures"
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / f"{name}-{cache_key(generator, args)}{EXTENSIONS[generator]}"
    digest_file = path.with_name(path.name + ".sha256")
    if path.is_file() and digest_file.is_file():
        data = path.read_bytes()
        if _sha256(data) == digest_file.read_text(encoding="ascii").strip():
            return {"name": name, "path": str(path), "bytes": len(data), "sha256": _sha256(data),
                    "generator": generator, "args": dict(args)}
    data = GENERATORS[generator](**args)
    digest = _sha256(data)
    tmp = path.with_name(path.name + f".tmp{os.getpid()}")
    tmp.write_bytes(data)
    try:
        os.replace(tmp, path)
    except PermissionError:
        # Windows: another run sharing the work dir has the file open; its copy is fine if the content matches.
        tmp.unlink(missing_ok=True)
        if not (path.is_file() and _sha256(path.read_bytes()) == digest):
            raise
    digest_file.write_text(digest + "\n", encoding="ascii")
    return {"name": name, "path": str(path), "bytes": len(data), "sha256": digest, "generator": generator,
            "args": dict(args)}


def ensure_standard(work_dir) -> dict:
    """All fixtures of STANDARD, by name."""
    return {name: ensure(work_dir, name, gen, args) for name, (gen, args) in STANDARD.items()}
