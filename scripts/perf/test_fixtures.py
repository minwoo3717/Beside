"""fixtures.py: valid, deterministic PNGs and GLB, the size targets the scenarios rely on, and the cache."""
import json
import os
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

import fixtures

MB5 = 5 * 1024 * 1024    # Spring's spring.servlet.multipart.max-file-size=5MB
MB20 = 20 * 1024 * 1024  # max-request-size=20MB
PART_OVERHEAD = 200      # boundary line and part headers of one multipart part, generously


def png_chunks(data: bytes):
    """[(type, payload)] after checking the signature and every CRC."""
    assert data[:8] == fixtures.PNG_SIGNATURE
    offset, chunks = 8, []
    while offset < len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + length]
        crc = struct.unpack(">I", data[offset + 8 + length:offset + 12 + length])[0]
        assert crc == zlib.crc32(kind + payload) & 0xFFFFFFFF, kind
        chunks.append((kind, payload))
        offset += 12 + length
    return chunks


def png_rows(data: bytes):
    chunks = png_chunks(data)
    width, height, depth, colour, compression, filtering, interlace = struct.unpack(">IIBBBBB", chunks[0][1])
    raw = zlib.decompress(b"".join(p for k, p in chunks if k == b"IDAT"))
    stride = 1 + width * 3
    return (width, height, depth, colour, interlace), [raw[i * stride:(i + 1) * stride] for i in range(height)], raw


class PngTest(unittest.TestCase):
    def test_noise_png_structure(self):
        data = fixtures.png_noise(8, 4, seed=3)
        chunks = png_chunks(data)
        self.assertEqual([k for k, _ in chunks][0], b"IHDR")
        self.assertEqual([k for k, _ in chunks][-1], b"IEND")
        (width, height, depth, colour, interlace), rows, raw = png_rows(data)
        self.assertEqual((width, height, depth, colour, interlace), (8, 4, 8, 2, 0))
        self.assertEqual(len(raw), 4 * (1 + 24))
        self.assertTrue(all(row[0] == 0 for row in rows))
        self.assertEqual(fixtures.png_size(data), (8, 4))

    def test_deterministic_by_seed(self):
        self.assertEqual(fixtures.png_noise(16, 16, seed=5), fixtures.png_noise(16, 16, seed=5))
        self.assertNotEqual(fixtures.png_noise(16, 16, seed=5), fixtures.png_noise(16, 16, seed=6))
        self.assertEqual(fixtures.png_gradient(64, 48, seed=2), fixtures.png_gradient(64, 48, seed=2))

    def test_gradient_is_smooth_with_tiny_noise(self):
        (width, height, *_), rows, _raw = png_rows(fixtures.png_gradient(64, 48, seed=2, noise_every=16))
        first, last = rows[0][1:], rows[-1][1:]
        self.assertLessEqual(max(first[1::3]), 1)    # green ~0 on the top row (+-1 noise)
        self.assertGreaterEqual(min(last[1::3]), 254)  # green ~255 on the bottom row
        reds = first[0::3]
        self.assertLessEqual(reds[0], 1)
        self.assertGreaterEqual(reds[-1], 254)
        for row in rows:
            pixels = row[1:]
            steps = [abs(pixels[i + 3] - pixels[i]) for i in range(0, len(pixels) - 3)]
            self.assertLessEqual(max(steps), 8)  # smooth: no jumps between neighbours

    def test_standard_photo_sizes_fit_the_upload_limits(self):
        large = fixtures.png_noise(1230, 1230, seed=12)
        self.assertGreater(len(large), 4_400_000)
        self.assertLess(len(large), MB5)
        self.assertLess(4 * (len(large) + PART_OVERHEAD), MB20)  # upload_large sends four in one request
        photo = fixtures.png_gradient(4000, 3000, seed=13)
        self.assertEqual(fixtures.png_size(photo), (4000, 3000))
        self.assertLess(len(photo), 2_000_000)  # small file, many pixels


class GlbTest(unittest.TestCase):
    def test_structure_and_exact_size(self):
        data = fixtures.glb_synthetic(1_500_000, seed=14)
        self.assertEqual(len(data), 1_500_000)
        header = fixtures.parse_glb_header(data)
        self.assertEqual(header["magic"], b"glTF")
        self.assertEqual(header["version"], 2)
        self.assertEqual(header["length"], len(data))
        self.assertEqual(header["jsonType"], 0x4E4F534A)
        self.assertEqual(header["binType"], 0x004E4942)
        self.assertEqual(header["jsonLength"] % 4, 0)
        self.assertEqual(header["binLength"] % 4, 0)
        self.assertEqual(header["json"]["asset"]["version"], "2.0")
        self.assertEqual(header["json"]["buffers"][0]["byteLength"], header["binLength"])
        json_bytes = data[20:20 + header["jsonLength"]]
        self.assertNotIn(b"\x00", json_bytes)  # the JSON chunk is padded with spaces, not zeros
        self.assertTrue(json_bytes.rstrip(b" ").endswith(b"}"))
        json.loads(json_bytes.decode("ascii"))
        self.assertEqual(20 + header["jsonLength"] + 8 + header["binLength"], len(data))

    def test_size_not_multiple_of_four_and_determinism(self):
        data = fixtures.glb_synthetic(1001, seed=1)
        self.assertLessEqual(len(data), 1001)
        self.assertEqual(fixtures.parse_glb_header(data)["length"], len(data))
        self.assertEqual(data, fixtures.glb_synthetic(1001, seed=1))
        with self.assertRaises(ValueError):
            fixtures.glb_synthetic(10)


class EnsureTest(unittest.TestCase):
    def test_cache_reuse_and_repair(self):
        with tempfile.TemporaryDirectory() as work:
            first = fixtures.ensure(work, "tiny", "png_noise", {"width": 4, "height": 4, "seed": 1})
            path = Path(first["path"])
            self.assertTrue(path.is_file())
            self.assertEqual(first["bytes"], path.stat().st_size)
            stamp = path.stat().st_mtime_ns
            again = fixtures.ensure(work, "tiny", "png_noise", {"width": 4, "height": 4, "seed": 1})
            self.assertEqual(again, first)
            self.assertEqual(path.stat().st_mtime_ns, stamp)  # reused, not rewritten
            path.write_bytes(b"damaged")
            repaired = fixtures.ensure(work, "tiny", "png_noise", {"width": 4, "height": 4, "seed": 1})
            self.assertEqual(repaired["sha256"], first["sha256"])
            self.assertEqual(path.read_bytes(), fixtures.png_noise(4, 4, seed=1))
            other = fixtures.ensure(work, "tiny", "png_noise", {"width": 4, "height": 4, "seed": 2})
            self.assertNotEqual(other["path"], first["path"])
            self.assertFalse([p for p in os.listdir(Path(work) / "fixtures") if ".tmp" in p])

    def test_unknown_generator(self):
        with tempfile.TemporaryDirectory() as work, self.assertRaises(ValueError):
            fixtures.ensure(work, "x", "nope", {})


if __name__ == "__main__":
    unittest.main()
