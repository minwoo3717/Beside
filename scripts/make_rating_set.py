#!/usr/bin/env python3
"""Blind quality rating for the fur conditions (③ quality vs cost).

1) prepare: shuffle the benchmark screenshots under anonymous names so raters don't know the method
    python scripts/make_rating_set.py prepare <screenshot folder> --out rating --distance near
   -> rating/A01.png ... , rating/board.png (all images in one grid, easy to send in a chat),
      rating/key.csv (which image is which condition — do not show raters), rating/sheet.csv (to fill in)

2) raters give each image 1-5 (5 = looks like real fur). Put one column per rater in sheet.csv:
      image,minwoo,byeonghak,...
      A01,4,3
3) score: average per condition
    python scripts/make_rating_set.py score rating
   -> rating/scores.csv (condition,score,n)  -> analyze_fur_bench.py ... --quality rating/scores.csv

Screenshot names come from FurBenchmark: <runId>_<distance>_<condition>.png ("+" written as "_").
"""
from __future__ import annotations

import argparse
import csv
import random
import re
import shutil
import statistics as st
import sys
from pathlib import Path

KNOWN = ["shell16_fins", "shell16_v1", "shell20_fixed", "shell20_adapt", "shell16_aa1", "shell16_aa2", "shell16_aa4",
         "none", "texture", "shell8", "shell16", "shell24", "strand5k"]


def parse(name: str):
    m = re.match(r"^\d{8}_\d{6}_(near|far|other)_(.+)\.png$", name)
    if not m:
        return None, None
    cond = m.group(2)
    return m.group(1), ("shell16+fins" if cond == "shell16_fins" else cond)


def prepare(src: str, out: str, distance: str | None, seed: int) -> int:
    files = sorted(Path(src).glob("*.png"))
    items = []
    for f in files:
        d, c = parse(f.name)
        if c and (distance is None or d == distance):
            items.append((f, d, c))
    if not items:
        print("no benchmark screenshots found (expected <runId>_<distance>_<condition>.png)")
        return 1
    random.Random(seed).shuffle(items)
    o = Path(out)
    o.mkdir(parents=True, exist_ok=True)
    with open(o / "key.csv", "w", newline="", encoding="utf-8") as fk, open(o / "sheet.csv", "w", newline="", encoding="utf-8") as fs:
        wk, ws = csv.writer(fk), csv.writer(fs)
        wk.writerow(["image", "condition", "distance", "file"])
        ws.writerow(["image", "rater1"])
        for i, (f, d, c) in enumerate(items, 1):
            img = f"A{i:02d}"
            shutil.copy(f, o / f"{img}.png")
            wk.writerow([img, c, d, f.name])
            ws.writerow([img, ""])
    try:
        from PIL import Image, ImageDraw, ImageFont
        thumbs = [Image.open(o / f"A{i:02d}.png").convert("RGB") for i in range(1, len(items) + 1)]
        tw = 360
        th = int(tw * thumbs[0].height / thumbs[0].width)
        cols = 4
        rows = (len(thumbs) + cols - 1) // cols
        board = Image.new("RGB", (cols * tw, rows * (th + 40)), (252, 252, 251))
        draw = ImageDraw.Draw(board)
        try:
            font = ImageFont.truetype("arial.ttf", 28)
        except OSError:
            font = ImageFont.load_default()
        for k, t in enumerate(thumbs):
            x, y = (k % cols) * tw, (k // cols) * (th + 40)
            board.paste(t.resize((tw, th)), (x, y + 40))
            draw.text((x + 10, y + 6), f"A{k + 1:02d}", fill=(11, 11, 11), font=font)
        board.save(o / "board.png")
    except Exception as e:  # noqa: BLE001
        print(f"board.png skipped: {e}")
    print(f"{len(items)} images -> {o.resolve()}  (send A*.png or board.png to raters; keep key.csv hidden)")
    return 0


def score(folder: str) -> int:
    o = Path(folder)
    key = {r["image"]: r["condition"] for r in csv.DictReader(open(o / "key.csv", encoding="utf-8-sig"))}
    per = {}
    for r in csv.DictReader(open(o / "sheet.csv", encoding="utf-8-sig")):
        c = key.get(r.get("image", "").strip())
        if not c:
            continue
        for k, v in r.items():
            if k == "image":
                continue
            try:
                per.setdefault(c, []).append(float(v))
            except (TypeError, ValueError):
                pass
    if not per:
        print("no scores in sheet.csv")
        return 1
    with open(o / "scores.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["condition", "score", "n", "stdev"])
        for c, v in sorted(per.items(), key=lambda kv: -st.mean(kv[1])):
            w.writerow([c, round(st.mean(v), 3), len(v), round(st.stdev(v), 3) if len(v) > 1 else ""])
            print(f"{c:<15} {st.mean(v):.2f}  (n={len(v)})")
    print(f"-> {o / 'scores.csv'}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("src")
    p.add_argument("--out", default="rating")
    p.add_argument("--distance", choices=["near", "far", "other"], help="only this distance (recommended: near)")
    p.add_argument("--seed", type=int, default=2026)
    s = sub.add_parser("score")
    s.add_argument("folder", nargs="?", default="rating")
    a = ap.parse_args()
    return prepare(a.src, a.out, a.distance, a.seed) if a.cmd == "prepare" else score(a.folder)


if __name__ == "__main__":
    sys.exit(main())
