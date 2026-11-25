"""3D A/B evaluation, step 3 of 3 (any PC): a blind rating board, then the scorer that opens the key.

  python generation/tools/eval_board.py build --eval-dir <eval_arms --out> --photos-dir <preprocessBatch output> \
      --out <folder> [--arms off,keep,remove,remove+pad] [--seed 20261128] [--force]
  python -m http.server 8000 --directory <folder>/board          # then http://localhost:8000/ (not file://)
  python generation/tools/eval_board.py score --key <folder>/_private/key.csv --ratings r1.csv r2.csv ... \
      [--baseline remove] [--out <folder>/_private/scores]

build writes
  <out>/board/            the public folder raters get: A01.glb, A02.glb ... in a seeded shuffled order, photos/P01.jpg
                          (input photo, long side <= 1024 px, no EXIF), index.html (photo + <model-viewer>),
                          ratings_template.csv (code, photoCode, rater + the six METRICS §4 items, 1-5)
  <out>/_private/key.csv  code -> photo, dog, arm. Keep it away from raters until every rating is in.
score joins the filled CSVs with key.csv: per-arm means of the six items (raters averaged per code first), per-photo
paired differences vs the baseline arm (improve/same/worse, two-sided sign test), and rater agreement per pair of
raters (exact, within +-1, quadratic weighted kappa). Its output names the arms, so it goes to _private by default.
"""
from __future__ import annotations

import argparse
import csv
import html
import itertools
import json
import math
import random
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from eval_arms import BASELINE, REFERENCE_PHOTO, dog_of, find_photos, split_variant, ARM_INPUTS  # noqa: E402
from eval_metrics import paired  # noqa: E402

ITEMS = ["faceShape", "ears", "bodyProportion", "furColor", "pattern", "textureArtifacts"]   # docs/METRICS.md §4
ITEM_KO = {"faceShape": "얼굴 형태 — 주둥이 길이, 이마·눈 위치, 전체 윤곽",
           "ears": "귀 모양/위치 — 선 귀/접힌 귀, 크기, 붙은 위치",
           "bodyProportion": "체형 비율 — 다리 길이, 몸통 길이/높이 비, 꼬리",
           "furColor": "털 색상 — 주 색상과 밝기",
           "pattern": "무늬 재현 — 반점·줄무늬·얼굴 마스크의 위치와 경계",
           "textureArtifacts": "텍스처 번짐/seam — UV 경계선, 늘어남, 뭉개짐 (5 = 결함 없음)"}
DEFAULT_BOARD_ARMS = ("off", "keep", "remove", "remove+pad")
DEFAULT_SEED = 20261128
TEMPLATE_COLUMNS = ["code", "photoCode", "rater"] + ITEMS + ["comment"]
KEY_COLUMNS = ["code", "photoCode", "photo", "dog", "arm", "sourceGlb"]
MODEL_VIEWER = "https://cdn.jsdelivr.net/npm/@google/model-viewer@3.5.0/dist/model-viewer.min.js"
THUMB_PX = 1024


def board_items(eval_dir: Path, arms) -> list[tuple[str, str]]:
    """(photo, arm) pairs that have a base.glb, sorted (the order before the shuffle)."""
    items = []
    for arm in arms:
        d = eval_dir / arm
        if not d.is_dir():
            continue
        items += [(p.name, arm) for p in d.iterdir() if (p / "base.glb").is_file()]
    return sorted(items)


def shuffled_codes(items: list[tuple[str, str]], seed: int) -> list[tuple[str, tuple[str, str]]]:
    """Deterministic: the same items and seed give the same codes on every PC."""
    order = sorted(items)
    random.Random(seed).shuffle(order)
    width = max(2, len(str(len(order))))
    return [(f"A{i + 1:0{width}d}", it) for i, it in enumerate(order)]


def make_thumbnail(src: Path, dst: Path, max_px: int = THUMB_PX) -> None:
    """Upright, RGB, long side <= max_px, saved without EXIF (team photos may carry GPS)."""
    from PIL import Image, ImageOps  # noqa: WPS433

    with Image.open(src) as im:
        img = ImageOps.exif_transpose(im).convert("RGB")
    img.thumbnail((max_px, max_px))
    dst.parent.mkdir(parents=True, exist_ok=True)
    img.save(dst, "JPEG", quality=85)


def build(eval_dir, photos_dir, out, arms=DEFAULT_BOARD_ARMS, seed: int = DEFAULT_SEED, force: bool = False,
          log=print) -> dict:
    eval_dir, out = Path(eval_dir), Path(out)
    public, private = out / "board", out / "_private"
    for arm in arms:
        if split_variant(arm)[0] not in ARM_INPUTS:
            raise SystemExit(f"--arms: unknown arm {arm}")
    if public.exists() and any(public.iterdir()):
        if not force:
            raise SystemExit(f"{public} is not empty (an earlier board?); --force replaces it")
        shutil.rmtree(public)
    public.mkdir(parents=True, exist_ok=True)
    private.mkdir(parents=True, exist_ok=True)

    items = board_items(eval_dir, arms)
    if not items:
        raise SystemExit(f"no base.glb for arms {','.join(arms)} under {eval_dir}")
    codes = shuffled_codes(items, seed)
    photos = sorted({p for p, _ in items})
    photo_codes = {p: f"P{i + 1:02d}" for i, p in enumerate(photos)}
    folders = {p.name: p.folder for p in find_photos(photos_dir, include_refused=True, log=lambda *_: None)}

    thumbs = {}
    for p in photos:
        src = folders.get(p)
        src = src / REFERENCE_PHOTO if src else None
        if src and src.is_file():
            make_thumbnail(src, public / "photos" / f"{photo_codes[p]}.jpg")
            thumbs[p] = f"photos/{photo_codes[p]}.jpg"
        else:
            log(f"{p}: no {REFERENCE_PHOTO} in --photos-dir, card without photo")

    key_rows = []
    for code, (photo, arm) in codes:
        shutil.copyfile(eval_dir / arm / photo / "base.glb", public / f"{code}.glb")
        key_rows.append({"code": code, "photoCode": photo_codes[photo], "photo": photo, "dog": dog_of(photo),
                         "arm": arm, "sourceGlb": f"{arm}/{photo}/base.glb"})
    _write_csv(private / "key.csv", key_rows, KEY_COLUMNS)
    (private / "board.json").write_text(json.dumps(
        {"seed": seed, "arms": list(arms), "codes": len(codes), "photos": len(photos),
         "builtAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}, indent=1), encoding="utf-8")
    _write_csv(public / "ratings_template.csv",
               [{"code": c, "photoCode": photo_codes[p]} for c, (p, _) in codes], TEMPLATE_COLUMNS, excel=True)
    (public / "index.html").write_text(board_html(codes, photo_codes, thumbs), encoding="utf-8")

    log(f"{len(codes)} models of {len(photos)} photos x {len(arms)} arms -> {public}")
    log(f"WARNING: {private / 'key.csv'} maps codes to arms. Give raters only the board folder; keep _private "
        "closed until all ratings are in.")
    return {"public": public, "private": private, "codes": codes}


def board_html(codes, photo_codes: dict, thumbs: dict) -> str:
    """The rater page. Names only codes (A01, P01): nothing on it tells which arm a model is."""
    items = "".join(f"<li><b>{i}</b> {html.escape(ITEM_KO[i])}</li>" for i in ITEMS)
    cards = []
    for code, (photo, _arm) in codes:
        pc = photo_codes[photo]
        img = (f'<img src="{thumbs[photo]}" alt="사진 {pc}" loading="lazy">' if photo in thumbs
               else '<div class="nophoto">사진 없음</div>')
        cards.append(
            f'<section class="card" id="{code}"><h2>{code} <span>사진 {pc}</span></h2><div class="pair">{img}'
            f'<model-viewer src="{code}.glb" alt="{code}" camera-controls loading="lazy" reveal="auto" '
            f'shadow-intensity="1" exposure="1" camera-orbit="30deg 75deg auto" interaction-prompt="none">'
            f'</model-viewer></div></section>')
    return f"""<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>3D 블라인드 평가</title>
<script type="module" src="{MODEL_VIEWER}"></script>
<style>
:root {{ --bg: #faf8f5; --fg: #222; --muted: #666; --card: #fff; --line: #ddd; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg: #1b1b1b; --fg: #eee; --muted: #aaa; --card: #262626; --line: #444; }} }}
body {{ margin: 0; padding: 16px; background: var(--bg); color: var(--fg); font-family: system-ui, sans-serif; }}
main {{ max-width: 1200px; margin: 0 auto; }}
.note {{ color: var(--muted); }}
#filewarn {{ background: #fde68a; color: #222; padding: 8px 12px; border-radius: 6px; }}
.grid {{ display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 540px), 1fr)); gap: 16px; }}
.card {{ background: var(--card); border: 1px solid var(--line); border-radius: 8px; padding: 12px; }}
.card h2 {{ margin: 0 0 8px; font-size: 1.2rem; }}
.card h2 span {{ color: var(--muted); font-weight: normal; font-size: 0.95rem; margin-left: 8px; }}
.pair {{ display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }}
.pair img, .nophoto {{ width: 100%; aspect-ratio: 1; object-fit: contain; background: #0001; border-radius: 4px; }}
model-viewer {{ width: 100%; height: auto; aspect-ratio: 1; background: #8881; border-radius: 4px; }}
</style>
</head>
<body>
<main>
<h1>3D 모델 블라인드 평가</h1>
<p id="filewarn" hidden>파일을 바로 열면 3D 모델이 뜨지 않는다. 이 폴더에서 <code>python -m http.server 8000</code> 을 실행하고 http://localhost:8000/ 으로 연다.</p>
<p>카드마다 왼쪽은 입력 사진, 오른쪽은 3D 모델이다. 끌어서 돌리고 휠로 확대한다. 같은 사진 번호(P..)의 카드가 여러 장 있다. 카드는 섞여 있고 어떤 방법으로 만든 모델인지는 적혀 있지 않다.</p>
<p><code>ratings_template.csv</code> 를 복사해 <code>rater</code> 칸에 이름을 쓰고, 여섯 항목을 1~5 정수로 채운다. 다른 평가자와 상의하지 않는다. 1 = 전혀 다름, 3 = 종을 알아볼 수 있음, 5 = 그 개체로 알아볼 수 있음.</p>
<ul>{items}</ul>
<p class="note">앱의 털(셸 퍼)은 이 화면에 없다. 털 색과 무늬는 텍스처로만 본다.</p>
<div class="grid">
{chr(10).join(cards)}
</div>
</main>
<script>if (location.protocol === "file:") document.getElementById("filewarn").hidden = false;</script>
</body>
</html>
"""


def _write_csv(path: Path, rows, columns, excel: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8-sig" if excel else "utf-8") as f:
        w = csv.DictWriter(f, fieldnames=columns, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k, "") for k in columns})


# --------------------------------------------------------------------------- scoring


def read_key(path) -> dict:
    with open(path, newline="", encoding="utf-8-sig") as f:
        return {r["code"]: r for r in csv.DictReader(f)}


def read_ratings(paths, key: dict) -> list[dict]:
    """[{rater, code, item: int}] from filled templates. The rater is the `rater` cell, or the file name when empty.
    Blank item cells are skipped; anything else outside 1-5 is an error naming the file and row."""
    out, problems = [], []
    for path in paths:
        path = Path(path)
        with open(path, newline="", encoding="utf-8-sig") as f:
            for line, r in enumerate(csv.DictReader(f), start=2):
                code = (r.get("code") or "").strip()
                if not code:
                    continue
                if code not in key:
                    problems.append(f"{path.name}:{line}: code {code} is not in key.csv")
                    continue
                row = {"rater": (r.get("rater") or "").strip() or path.stem, "code": code}
                for item in ITEMS:
                    cell = (r.get(item) or "").strip()
                    if not cell:
                        continue
                    try:
                        value = int(float(cell))
                    except ValueError:
                        value = None
                    if value is None or value != float(cell) or not 1 <= value <= 5:
                        problems.append(f"{path.name}:{line}: {item} = {cell!r} (want an integer 1-5)")
                        continue
                    row[item] = value
                out.append(row)
    if problems:
        raise SystemExit("ratings have problems:\n  " + "\n  ".join(problems))
    seen = set()
    for r in out:
        if (r["rater"], r["code"]) in seen:
            raise SystemExit(f"rater {r['rater']} rated {r['code']} twice")
        seen.add((r["rater"], r["code"]))
    return out


def weighted_kappa(x, y, k: int = 5) -> float:
    """Cohen's kappa with quadratic weights for scores 1..k. NaN when the expected disagreement is 0."""
    if not x:
        return float("nan")
    obs = [[0.0] * k for _ in range(k)]
    for a, b in zip(x, y):
        obs[a - 1][b - 1] += 1
    n = float(len(x))
    rows = [sum(r) / n for r in obs]
    cols = [sum(obs[i][j] for i in range(k)) / n for j in range(k)]
    w = [[(i - j) ** 2 / (k - 1) ** 2 for j in range(k)] for i in range(k)]
    num = sum(w[i][j] * obs[i][j] / n for i in range(k) for j in range(k))
    den = sum(w[i][j] * rows[i] * cols[j] for i in range(k) for j in range(k))
    return float("nan") if den == 0 else 1.0 - num / den


def score(key: dict, ratings: list[dict], baseline: str = BASELINE) -> dict:
    items = ITEMS + ["mean6"]
    per_code: dict = {}                       # (code, item) -> [scores of the raters]
    for r in ratings:
        vals = [r[i] for i in ITEMS if i in r]
        for i in ITEMS:
            if i in r:
                per_code.setdefault((r["code"], i), []).append(r[i])
        if len(vals) == len(ITEMS):
            per_code.setdefault((r["code"], "mean6"), []).append(sum(vals) / len(ITEMS))
    avg = {k: sum(v) / len(v) for k, v in per_code.items()}
    arms = sorted({key[c]["arm"] for c, _ in avg}, key=lambda a: (a != baseline, a))
    by_arm = {a: {i: {key[c]["photo"]: v for (c, it), v in avg.items() if key[c]["arm"] == a and it == i}
                  for i in items} for a in arms}

    means = []
    for a in arms:
        for i in items:
            vals = list(by_arm[a][i].values())
            means.append({"arm": a, "item": i, "n": len(vals),
                          "mean": sum(vals) / len(vals) if vals else float("nan")})
    pairs = []
    if baseline in by_arm:
        for a in arms:
            if a == baseline:
                continue
            for i in items:
                p = paired(by_arm[a][i], by_arm[baseline][i], lower_better=False, tol=0.0)
                pairs.append({"arm": a, "item": i, "vs": baseline, **p})

    raters = sorted({r["rater"] for r in ratings})
    by_rater = {(r["rater"], r["code"]): r for r in ratings}
    agreement = []
    for r1, r2 in itertools.combinations(raters, 2):
        for i in ITEMS + ["all"]:
            xs, ys = [], []
            for code in key:
                a, b = by_rater.get((r1, code)), by_rater.get((r2, code))
                if not a or not b:
                    continue
                for it in (ITEMS if i == "all" else [i]):
                    if it in a and it in b:
                        xs.append(a[it])
                        ys.append(b[it])
            n = len(xs)
            agreement.append({"raters": f"{r1}~{r2}", "item": i, "n": n,
                              "exact": sum(1 for a, b in zip(xs, ys) if a == b) / n if n else float("nan"),
                              "within1": sum(1 for a, b in zip(xs, ys) if abs(a - b) <= 1) / n if n else float("nan"),
                              "kappaQW": weighted_kappa(xs, ys)})
    return {"means": means, "paired": pairs, "agreement": agreement, "raters": raters}


def _f(v, d=2) -> str:
    if isinstance(v, float):
        return "" if math.isnan(v) else f"{v:.{d}f}"
    return str(v)


def score_markdown(result: dict, baseline: str) -> str:
    items = ITEMS + ["mean6"]
    arms = list(dict.fromkeys(m["arm"] for m in result["means"]))
    cell = {(m["arm"], m["item"]): m for m in result["means"]}
    lines = [f"raters: {', '.join(result['raters'])}", "",
             "| arm | photos | " + " | ".join(items) + " |", "|---|---|" + "---|" * len(items)]
    for a in arms:
        n = max(cell[(a, i)]["n"] for i in items)
        lines.append(f"| {a}{' (baseline)' if a == baseline else ''} | {n} | "
                     + " | ".join(_f(cell[(a, i)]["mean"]) for i in items) + " |")
    if result["paired"]:
        lines += ["", f"Paired per photo vs {baseline} (rater means; diff = arm - {baseline}, higher is better; "
                      "two-sided sign test on improve vs worse, ties dropped):", "",
                  "| arm | item | n | mean diff | improve | same | worse | sign p |", "|---|---|---|---|---|---|---|---|"]
        for p in result["paired"]:
            lines.append(f"| {p['arm']} | {p['item']} | {p['n']} | {_f(p['meanDiff'])} | {p['improve']} | "
                         f"{p['same']} | {p['worse']} | {_f(p['signP'], 4)} |")
    if result["agreement"]:
        lines += ["", "Rater agreement (same code and item):", "",
                  "| raters | item | n | exact | within ±1 | weighted kappa (quadratic) |", "|---|---|---|---|---|---|"]
        for g in result["agreement"]:
            lines.append(f"| {g['raters']} | {g['item']} | {g['n']} | {_f(g['exact'])} | {_f(g['within1'])} | "
                         f"{_f(g['kappaQW'])} |")
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build", help="make the blind board")
    b.add_argument("--eval-dir", required=True)
    b.add_argument("--photos-dir", required=True)
    b.add_argument("--out", required=True, help="board/ and _private/ are made inside")
    b.add_argument("--arms", default=",".join(DEFAULT_BOARD_ARMS))
    b.add_argument("--seed", type=int, default=DEFAULT_SEED)
    b.add_argument("--force", action="store_true", help="replace an existing board folder")
    s = sub.add_parser("score", help="join filled ratings with the key")
    s.add_argument("--key", required=True)
    s.add_argument("--ratings", nargs="+", required=True)
    s.add_argument("--baseline", default=BASELINE)
    s.add_argument("--out", help="default <key folder>/scores")
    a = ap.parse_args(argv)
    if sys.stdout.encoding and sys.stdout.encoding.lower() not in ("utf-8", "utf8"):
        sys.stdout.reconfigure(encoding="utf-8")   # the score table has ±
    if a.cmd == "build":
        build(a.eval_dir, a.photos_dir, a.out, [x.strip() for x in a.arms.split(",") if x.strip()], a.seed, a.force)
        return 0
    key = read_key(a.key)
    result = score(key, read_ratings(a.ratings, key), a.baseline)
    out = Path(a.out) if a.out else Path(a.key).parent / "scores"
    out.mkdir(parents=True, exist_ok=True)
    _write_csv(out / "scores_by_arm.csv", result["means"], ["arm", "item", "n", "mean"])
    _write_csv(out / "scores_paired.csv", result["paired"],
               ["arm", "item", "vs", "n", "meanDiff", "improve", "same", "worse", "signP"])
    _write_csv(out / "agreement.csv", result["agreement"], ["raters", "item", "n", "exact", "within1", "kappaQW"])
    md = score_markdown(result, a.baseline)
    (out / "scores_summary.md").write_text(md, encoding="utf-8")
    print(md)
    print(f"-> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
