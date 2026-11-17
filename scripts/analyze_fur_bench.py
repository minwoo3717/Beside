#!/usr/bin/env python3
"""Turn FurBenchmark CSVs into the presentation numbers: tables + charts.

    python scripts/analyze_fur_bench.py fur_bench/*.csv --out fur_report
    python scripts/analyze_fur_bench.py fur_bench/*.csv --out fur_report --quality ratings_scored.csv

Input : CSVs from the app (or text shared from the phone — summary lines above the header are skipped).
Output (in --out):
  summary.csv            one row per (distance, condition): medians over rounds
  summary.md             the same as Markdown tables, ready to paste into slides/docs
  unit_costs.md          ms per shell, ms per 1k strands, fins, texture, and what fits the frame budget
  1_method_cost.png      ① GPU time per fur method, near vs far
  2_shell_scaling.png    ① GPU time vs shell count (linear fit = cost per shell)
  3_optimization.png     ② before/after: shell v1 vs v2, fixed vs adaptive shells, MSAA off/2x/4x
  4_quality_vs_cost.png  ③ quality score vs GPU time (only with --quality, see make_rating_set.py)

Cost metric: gpuMs (GPU frame time). FPS sits at the 30/60 Hz cap for most conditions, so it hides differences;
when the device gives no GPU timing (Player Settings > Frame Timing Stats off) meanMs is used and a note is printed.
Rows flagged 'moved' (the phone moved during the window) are dropped unless --keep-moved.
"""
from __future__ import annotations

import argparse
import csv
import statistics as st
import sys
from collections import defaultdict
from pathlib import Path

DIST_ORDER = ["near", "far", "other"]
DIST_KO = {"near": "가까이", "far": "멀리", "other": "기타", "": "미지정"}
METHOD_ORDER = ["none", "texture", "shell8", "shell16", "shell24", "shell16+fins", "strand5k"]
METHOD_KO = {"none": "털 없음", "texture": "노멀맵만", "shell8": "쉘 8겹", "shell16": "쉘 16겹", "shell24": "쉘 24겹",
             "shell16+fins": "쉘16+핀", "strand5k": "가닥 5천", "shell16_v1": "쉘16 (v1)",
             "shell20_fixed": "쉘20 고정", "shell20_adapt": "쉘20 자동", "shell16_aa1": "쉘16 MSAA끔",
             "shell16_aa2": "쉘16 MSAA2x", "shell16_aa4": "쉘16 MSAA4x"}
# palette: reference categorical slots 1/2 (blue, orange) + text inks (dataviz skill, light mode)
C_NEAR, C_FAR, C_ACCENT = "#2a78d6", "#eb6834", "#1baf7a"
INK, INK2, GRID, SURFACE = "#0b0b0b", "#52514e", "#e4e3df", "#fcfcfb"


METHODS_MD = """## 비교한 방법

공통: 모델은 AnimalLift가 만든 메시(정점 4,310개 / 삼각형 7,774개)와 색 텍스처입니다. 털의 길이와 결은 AnimalLift `hair.npz`에서 만든 **길이 맵·결 맵**을 씁니다. 개마다 다르고, 서버가 GLB 안에 넣어 보냅니다.

| 방법 | 어떻게 그리나 | 비용을 좌우하는 것 | 장점 / 한계 |
|---|---|---|---|
| **털 없음** | 메시에 색 텍스처만 입혀 한 번 그립니다. 털은 텍스처에 그려진 무늬뿐입니다. | 메시가 덮는 화면 픽셀 | 기준선 |
| **노멀맵만** | 표면에 털결 모양의 요철 정보(노멀맵)를 입혀, 빛 반사만 털처럼 보이게 합니다. 형상은 추가하지 않습니다. | 텍스처 1장을 더 읽는 정도 | 거의 공짜 / 윤곽이 매끈해서 털이 솟아 보이지 않음 |
| **쉘 N겹** | 같은 메시를 N번 더 그립니다. 한 겹마다 표면에서 조금씩 바깥(털 결 방향)으로 밀어낸 "껍질"입니다. 각 껍질에서 노이즈 텍스처로 털 자리만 남기고 나머지 픽셀은 버리므로, 겹쳐 보면 털 가닥처럼 보입니다. | **겹 수 × 털이 덮는 화면 픽셀** | 짧고 빽빽한 털·넓은 면적에 강함 / 가까이서 층이 보이고 윤곽이 얇음 |
| **쉘 + 핀** | 쉘에 더해, 메시 모서리마다 바깥으로 세운 얇은 판(핀)에 털 무늬를 그립니다. 윤곽선에 걸린 핀만 남기고 나머지는 크기 0으로 접습니다. | 쉘 비용 + 윤곽선 픽셀 | 실루엣이 보송해짐 / 비용 소폭 증가 |
| **가닥 5천** | 털 한 가닥을 4마디짜리 얇은 리본(카메라를 향하는 띠)으로 직접 그립니다. 5,000가닥을 몸 표면에 고르게 뿌리고, 뿌리를 메시 삼각형에 붙여 애니메이션을 따라가게 합니다(GPU가 현재 자세를 직접 읽음). | 가닥 수 × 마디 수 (화면 면적은 작음) | 긴 털·실루엣에 강함 / 수가 적으면 가시처럼 보임 → 꼬리·귀 전용이 적합 |

## 비교한 최적화

| 항목 | 내용 |
|---|---|
| **쉘 셰이더 v1 → v2** | v1은 털이 표면에 수직으로 서 있고, 털 위치를 해시 격자로 정하며, 조명을 픽셀마다 계산합니다. v2는 털을 결 방향으로 눕히고, 밉맵이 있는 노이즈 텍스처로 털 위치를 정하며, 털 방향 하이라이트를 넣었습니다. 조명은 정점 단위로 옮겼고, 바깥 껍질부터 그려서 가려진 픽셀을 GPU가 건너뛸 수 있게 했습니다. |
| **쉘 수 고정 → 자동 조절** | 펫이 화면의 20% 이상을 차지하면 쉘 수를 줄이기 시작해서, 60%에서 최소 10겹이 됩니다. 쉘 비용이 화면 면적에 비례하므로, 가까울 때 겹 수를 줄여 비용 폭증을 막습니다. 멀리서는 그대로 20겹입니다. |
| **MSAA 끔 / 2x / 4x** | 픽셀 하나를 여러 샘플(2x = 2개, 4x = 4개)로 나눠 가장자리를 부드럽게 하는 안티에일리어싱입니다. v2 쉘은 alpha-to-coverage를 써서, MSAA가 켜져 있으면 털 끝이 부드러워집니다. 끄면 비용은 크게 줄지만 털 가장자리가 거칠어질 수 있습니다. |

## 측정 방법

- **지표: 펫 1마리 렌더 시간(ms).** 펫(몸 + 털)을 한 프레임에 K번, 2K번 겹쳐 그려 GPU를 한계까지 쓰게 합니다. 그다음 (2K일 때 평균 프레임 시간 − K일 때 평균 프레임 시간) ÷ K를 계산합니다.
  - 이 빼기에서 매 프레임 한 번만 드는 비용(AR 카메라 화면, UI)과 화면 갱신 대기 시간이 사라지므로, 펫만의 GPU 시간이 남습니다.
  - K는 조건마다 자동으로 정합니다. 프레임 시간이 33 ms를 넘을 때까지 두 배씩 올립니다.
- **조건:**
  - 같은 모델, 같은 자세(Idle), 같은 폰 위치(거치대 고정)에서 잽니다. 측정 중에는 AR 30 FPS 고정을 해제합니다.
  - 조건마다 준비 5초 후 K에서 10초, 2K에서 10초를 잽니다. 3라운드를 돌리고, 라운드마다 조건 순서를 바꿉니다.
- **거리:** 가까이 = 펫이 화면의 약 87%, 멀리 = 약 20%(바운딩 박스 기준).
- **비율 비교:** 같은 라운드 안에서 두 조건을 나눈 비율의 중앙값입니다. 발열(1라운드 36~40°C → 2·3라운드 약 44°C)과 배치 차이의 영향을 없애기 위해 이 방식으로 결론을 냅니다.
"""


def fnum(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def read_rows(paths):
    rows = []
    for p in paths:
        text = Path(p).read_text(encoding="utf-8-sig").splitlines()
        start = next((i for i, l in enumerate(text) if l.startswith("runId,")), None)
        if start is None:
            print(f"skip {p}: no header line starting with runId,")
            continue
        body = [text[start]] + [l for l in text[start + 1:] if l.strip() and not l.startswith("runId,")]
        rows += list(csv.DictReader(body))
    return rows


def fit(points):
    if len(points) < 2:
        return None
    xs, ys = zip(*points)
    mx, my = st.mean(xs), st.mean(ys)
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx == 0:
        return None
    b = sum((x - mx) * (y - my) for x, y in points) / sxx
    return my - b * mx, b


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv", nargs="+")
    ap.add_argument("--out", default="fur_report")
    ap.add_argument("--target-fps", type=float, default=30.0)
    ap.add_argument("--headroom", type=float, default=0.8, help="share of the frame the app may use")
    ap.add_argument("--keep-moved", action="store_true")
    ap.add_argument("--quality", help="CSV with columns condition,score (make_rating_set.py --score writes it)")
    args = ap.parse_args()

    rows = read_rows(args.csv)
    if not rows:
        print("no rows")
        return 1
    moved = [r for r in rows if "moved" in (r.get("note") or "")]
    if not args.keep_moved:
        rows = [r for r in rows if r not in moved]
    has_gpu = all(fnum(r.get("gpuMs")) for r in rows)
    loaded = all(fnum(r.get("msPerPet")) for r in rows) and any((fnum(r.get("loadRepeat")) or 1) > 1 for r in rows)
    metric = "msPerPet" if loaded else ("gpuMs" if has_gpu else "meanMs")
    refresh = fnum(rows[0].get("refreshHz")) or 60
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    # ---- medians per (distance, condition)
    groups = defaultdict(list)
    order = []
    for r in rows:
        key = (r.get("distanceLabel", ""), r["condition"])
        if key not in groups:
            order.append(key)
        groups[key].append(r)

    def med(rs, k):
        v = [fnum(r.get(k)) for r in rs]
        v = [x for x in v if x is not None]
        return st.median(v) if v else None

    summary = {}
    for key in order:
        rs = groups[key]
        summary[key] = {k: med(rs, k) for k in ("msPerPet", "loadRepeat", "gpuMs", "meanMs", "p95Ms", "low1Fps", "avgFps", "memMB", "tempC",
                                                "coveragePct", "shellsDrawn", "furTris", "msaa", "shells")}
        summary[key]["n"] = len(rs)
        summary[key]["unbound"] = any("notGpuBound" in (r.get("note") or "") for r in rs)
        mv = [fnum(r.get(metric)) for r in rs if fnum(r.get(metric)) is not None]
        summary[key]["lo"], summary[key]["hi"] = (min(mv), max(mv)) if mv else (None, None)
        summary[key]["r0"] = rs[0]
    dists = [d for d in DIST_ORDER if any(k[0] == d for k in summary)] + \
            sorted({k[0] for k in summary if k[0] not in DIST_ORDER})

    def val(d, cond, k=None):
        s = summary.get((d, cond))
        return None if s is None else s[k or metric]

    def cost(d, cond):
        v, base = val(d, cond), val(d, "none")
        return None if v is None or base is None else v - base

    # ---- summary.csv / summary.md
    cols = ["distance", "condition", "rounds", "gpuMs", "costMs", "meanMs", "p95Ms", "avgFps", "low1Fps", "memMB",
            "shellsDrawn", "msaa", "coveragePct", "tempC"]
    with open(out / "summary.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(cols)
        for (d, c), s in summary.items():
            w.writerow([d, c, s["n"]] + [("" if s.get(k) is None else round(s[k], 3)) for k in cols[3:4]]
                       + [("" if cost(d, c) is None else round(cost(d, c), 3))]
                       + [("" if s.get(k) is None else round(s[k], 3)) for k in cols[5:]])

    fmt = lambda v, p=1: "–" if v is None else f"{v:.{p}f}"
    md = [f"# 털 렌더링 성능 측정 결과\n",
          f"- 기기: {rows[0].get('device')} ({rows[0].get('gpu')}, {rows[0].get('api')}), 화면 {rows[0].get('screen')} @ {refresh:.0f} Hz",
          f"- 지표: **{metric}** (조건별 {min(s['n'] for s in summary.values())}~{max(s['n'] for s in summary.values())}회 측정의 중앙값; 두 묶음에 모두 있는 조건은 횟수가 많음)"
          + (" — 펫을 프레임당 K번, 2K번 겹쳐 그려 GPU를 한계까지 쓰게 한 뒤 (평균 프레임 시간(2K) − 평균 프레임 시간(K)) ÷ K."
             " 매 프레임 한 번만 드는 비용(AR 카메라 화면, UI)과 화면 동기화 대기가 빠진, 펫 1마리를 그리는 실제 GPU 시간."
             " K는 조건마다 자동 보정" if loaded else
             ("" if has_gpu else " — GPU 시간이 없어 프레임 시간으로 대체 (Frame Timing Stats 켜기)")),
          f"- 제외한 행(측정 중 폰 이동): {len(moved) if not args.keep_moved else 0}"]
    fps_all = [fnum(r.get("avgFps")) for r in rows if fnum(r.get("avgFps"))]
    locked_col = [r.get("arFrameRateLocked") for r in rows if r.get("arFrameRateLocked") not in (None, "")]
    ar_locked = any(v == "1" for v in locked_col) if locked_col else (fps_all and max(fps_all) < 31 and refresh > 45)
    if ar_locked:
        md.append(f"- 주의: 상한을 {refresh:.0f} Hz로 풀었지만 모든 조건이 30 FPS 이하 → AR 카메라 프레임레이트(30)에 고정됨. "
                  "FPS로는 여유를 볼 수 없어 GPU 시간과 1% low로 비교한다.")
    spreads = [(s["hi"] - s["lo"]) / s[metric] for s in summary.values() if s.get("hi") is not None and s.get(metric)]
    if spreads:
        md.append(f"- 라운드 간 편차: 중앙값 대비 평균 ±{st.mean(spreads) * 50:.0f}% (작은 차이는 측정 오차 안일 수 있음)")
    md.append("")
    md.append(METHODS_MD)
    md.append("# 결과\n")
    for d in dists:
        md.append(f"## 거리: {DIST_KO.get(d, d)}\n")
        md.append(f"| 조건 | {metric} (중앙값) | 라운드 범위 | 털 추가 비용 ms | 평균 FPS | 1% low FPS | p95 ms | 쉘(실제) | MSAA | 화면 차지 % |")
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
        for (dd, c), s in summary.items():
            if dd != d:
                continue
            md.append(f"| {METHOD_KO.get(c, c)}{' ※' if s['unbound'] else ''} | {fmt(s[metric], 2)} | {fmt(s['lo'], 2)}–{fmt(s['hi'], 2)} | {fmt(cost(d, c), 2)} | "
                      f"{fmt(s['avgFps'], 1)} | {fmt(s['low1Fps'], 0)} | {fmt(s['p95Ms'])} | {fmt(s['shellsDrawn'], 0)} | "
                      f"{fmt(s['msaa'], 0)} | {fmt(s['coveragePct'], 0)} |")
        md.append("")
    if any(s["unbound"] for s in summary.values()):
        md.append("※ 부하가 충분히 크지 않았던 조건(프레임이 주사율 상한에 가까움): 참고값으로 읽는다.\n")
    (out / "summary.md").write_text("\n".join(md), encoding="utf-8")

    # ---- unit costs
    budget = 1000.0 / args.target_fps * args.headroom
    uc = [f"# 단가와 예산 ({metric})\n", f"프레임 예산: {budget:.1f} ms (= 1000/{args.target_fps:.0f} × {args.headroom})\n"]
    shell_fits = {}
    for d in dists:
        uc.append(f"## {DIST_KO.get(d, d)}")
        base = val(d, "none")
        pts = [(fnum(summary[(d, c)]["r0"]["shells"]), val(d, c)) for c in ("shell8", "shell16", "shell24")
               if (d, c) in summary and val(d, c) is not None]
        f_ = fit(pts)
        if f_:
            shell_fits[d] = f_
            a, b = f_
            uc.append(f"- 쉘 1겹당: **{b:.3f} ms** (절편 {a:.2f} ms)" + (f" → 예산 안 최대 약 {int((budget - a) / b)}겹" if b > 0 else ""))
        for name, c in (("텍스처(노멀맵)만", "texture"), ("가닥 5천", "strand5k")):
            if cost(d, c) is not None:
                uc.append(f"- {name}: 털 없음 대비 {cost(d, c):+.2f} ms")
        if val(d, "shell16") is not None and val(d, "shell16+fins") is not None:
            uc.append(f"- 핀 추가: 쉘16 대비 {val(d, 'shell16+fins') - val(d, 'shell16'):+.2f} ms")
        for a_, b_, name in (("shell16_v1", "shell16", "쉘 셰이더 v1 → v2"),
                             ("shell20_fixed", "shell20_adapt", "쉘 수 고정 → 자동 조절"),
                             ("shell16_aa4", "shell16_aa1", "MSAA 4x → 끔")):
            va, vb = val(d, a_), val(d, b_)
            if va is not None and vb is not None and va > 0:
                uc.append(f"- {name}: {va:.2f} → {vb:.2f} ms ({(vb - va) / va * 100:+.0f}%)")
        if base is not None:
            uc.append(f"- 털 없음 프레임: {base:.2f} ms → 털에 쓸 수 있는 시간 {budget - base:.2f} ms")
        uc.append("")
    # paired comparison: ratio of two conditions measured in the same run and round (same heat, same placement)
    pairs = [("shell16", "shell16_v1", "쉘 셰이더 v2 ÷ v1"), ("shell20_adapt", "shell20_fixed", "쉘 자동 조절 ÷ 고정"),
             ("shell16_aa2", "shell16_aa4", "MSAA 2x ÷ 4x"), ("shell16_aa1", "shell16_aa4", "MSAA 끔 ÷ 4x"),
             ("shell8", "shell16", "쉘 8겹 ÷ 16겹"), ("shell24", "shell16", "쉘 24겹 ÷ 16겹"),
             ("shell16+fins", "shell16", "쉘16+핀 ÷ 쉘16"), ("strand5k", "shell8", "가닥 5천 ÷ 쉘 8겹")]
    cell = defaultdict(dict)
    for r in rows:
        if fnum(r.get(metric)) is not None:
            cell[(r.get("distanceLabel", ""), r["runId"], r["round"])][r["condition"]] = fnum(r[metric])
    uc.append("## 같은 라운드 안 비율 비교 (발열·배치 영향 제거)\n")
    uc.append("| 거리 | 비교 | 라운드별 비율 | 중앙값 | 변화 |")
    uc.append("|---|---|---|---:|---:|")
    for d in dists:
        for a_, b_, name in pairs:
            ratios = [v[a_] / v[b_] for (dd, _, _), v in sorted(cell.items()) if dd == d and a_ in v and b_ in v and v[b_] > 0]
            if ratios:
                m = st.median(ratios)
                uc.append(f"| {DIST_KO.get(d, d)} | {name} | {' / '.join(f'{x:.2f}' for x in ratios)} | {m:.2f} | {(m - 1) * 100:+.0f}% |")
    uc.append("")
    (out / "unit_costs.md").write_text("\n".join(uc), encoding="utf-8")
    print("\n".join(md))
    print("\n".join(uc))

    # ---- charts
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        from matplotlib import font_manager
    except ImportError:
        print("matplotlib not installed: pip install matplotlib  (tables were written)")
        return 0
    for fam in ("Malgun Gothic", "AppleGothic", "NanumGothic", "Noto Sans CJK KR", "Noto Sans CJK JP"):
        if any(fam == f.name for f in font_manager.fontManager.ttflist):
            plt.rcParams["font.family"] = fam
            break
    plt.rcParams.update({"axes.unicode_minus": False, "figure.facecolor": SURFACE, "axes.facecolor": SURFACE,
                         "axes.edgecolor": GRID, "axes.labelcolor": INK2, "xtick.color": INK2, "ytick.color": INK2,
                         "text.color": INK, "font.size": 11})
    ylabel = "펫 1마리 렌더 시간 (ms)" if loaded else ("GPU 프레임 시간 (ms)" if has_gpu else "프레임 시간 (ms)")
    dcolor = {"near": C_NEAR, "far": C_FAR, "other": C_ACCENT}

    def style(ax, title):
        ax.set_title(title, loc="left", fontsize=13, color=INK, pad=10)
        ax.grid(axis="x" if ax.get_xlabel() else "y", color=GRID, linewidth=0.8)
        ax.set_axisbelow(True)
        for s_ in ("top", "right"):
            ax.spines[s_].set_visible(False)

    def grouped_hbar(ax, conds, labels):
        n = len(dists)
        hgt = 0.8 / max(n, 1)
        for j, d in enumerate(dists):
            ys = [i + (j - (n - 1) / 2) * hgt for i in range(len(conds))]
            vs = [val(d, c) for c in conds]
            ok = [(y, v, c) for y, v, c in zip(ys, vs, conds) if v is not None]
            ax.barh([o[0] for o in ok], [o[1] for o in ok], height=hgt - 0.04, color=dcolor.get(d, C_ACCENT), label=DIST_KO.get(d, d))
            for y, v, c in ok:
                lo, hi = summary[(d, c)]["lo"], summary[(d, c)]["hi"]
                if lo is not None and hi is not None and hi > lo:
                    ax.plot([lo, hi], [y, y], color=INK, linewidth=1.2)
                mark = " ※" if summary[(d, c)]["unbound"] else ""
                ax.text(max(v, hi or v), y, f" {v:.2f}{mark}" if loaded else f" {v:.1f}", va="center", fontsize=9, color=INK2)
        ax.set_yticks(range(len(conds)), labels)
        ax.invert_yaxis()
        ax.set_xlabel(ylabel)
        if len(dists) > 1:
            ax.legend(frameon=False, loc="lower right")

    # 1 method cost
    conds = [c for c in METHOD_ORDER if any((d, c) in summary for d in dists)]
    if conds:
        fig, ax = plt.subplots(figsize=(8, 0.55 * len(conds) * max(1, len(dists)) + 1.6))
        grouped_hbar(ax, conds, [METHOD_KO.get(c, c) for c in conds])
        if not loaded:   # per-pet times are far below the frame budget; the line would only squash the bars
            ax.axvline(1000 / args.target_fps, color=INK2, linewidth=1, linestyle=(0, (4, 3)))
            ax.text(1000 / args.target_fps, -0.7, f" {args.target_fps:.0f} FPS 한계", fontsize=9, color=INK2)
        style(ax, "① 털 방식별 렌더 비용 (막대 = 중앙값, 검은 선 = 라운드 범위)")
        fig.tight_layout()
        fig.savefig(out / "1_method_cost.png", dpi=200)
        plt.close(fig)

    # 2 shell scaling
    if shell_fits:
        fig, ax = plt.subplots(figsize=(7, 4.2))
        for d, (a, b) in shell_fits.items():
            pts = [(fnum(summary[(d, c)]["r0"]["shells"]), val(d, c)) for c in ("shell8", "shell16", "shell24") if (d, c) in summary]
            xs, ys = zip(*pts)
            ax.plot(xs, ys, "o", markersize=8, color=dcolor.get(d, C_ACCENT), label=f"{DIST_KO.get(d, d)}: {b:.2f} ms/겹")
            ax.plot([0, 26], [a, a + b * 26], linewidth=2, color=dcolor.get(d, C_ACCENT), alpha=0.6)
            if val(d, "none") is not None:
                ax.plot([0], [val(d, "none")], "s", markersize=7, color=dcolor.get(d, C_ACCENT))
        ax.set_xlim(-1, 26)
        ax.set_xlabel("쉘 수 (0 = 털 없음)")
        ax.set_ylabel(ylabel)
        ax.legend(frameon=False)
        style(ax, "① 쉘 수에 따른 비용 (직선 기울기 = 쉘 1겹 단가)")
        ax.grid(axis="y", color=GRID, linewidth=0.8)
        fig.tight_layout()
        fig.savefig(out / "2_shell_scaling.png", dpi=200)
        plt.close(fig)

    # 3 optimization (small multiples, each its own scale)
    panels = [("셰이더 v1 → v2", [("shell16_v1", "v1"), ("shell16", "v2")]),
              ("쉘 수 고정 → 자동 조절", [("shell20_fixed", "고정 20"), ("shell20_adapt", "자동")]),
              ("MSAA", [("shell16_aa1", "끔"), ("shell16_aa2", "2x"), ("shell16_aa4", "4x")])]
    panels = [(t, items) for t, items in panels if any((d, c) in summary for d in dists for c, _ in items)]
    if panels:
        fig, axes = plt.subplots(1, len(panels), figsize=(4.2 * len(panels), 4.2), squeeze=False)
        for ax, (title, items) in zip(axes[0], panels):
            n = len(dists)
            wdt = 0.8 / max(n, 1)
            for j, d in enumerate(dists):
                xs = [i + (j - (n - 1) / 2) * wdt for i in range(len(items))]
                vs = [val(d, c) for c, _ in items]
                ax.bar([x for x, v in zip(xs, vs) if v is not None], [v for v in vs if v is not None], width=wdt - 0.04,
                       color=dcolor.get(d, C_ACCENT), label=DIST_KO.get(d, d))
                for x, v, (c, _) in zip(xs, vs, items):
                    if v is not None:
                        lo, hi = summary[(d, c)]["lo"], summary[(d, c)]["hi"]
                        if lo is not None and hi is not None and hi > lo:
                            ax.plot([x, x], [lo, hi], color=INK, linewidth=1.2)
                        ax.text(x + wdt * 0.3, v, f"{v:.1f}", ha="left", va="center", fontsize=9, color=INK2)
            ax.set_xticks(range(len(items)), [lab for _, lab in items])
            ax.set_ylabel(ylabel)
            style(ax, title)
            ax.grid(axis="y", color=GRID, linewidth=0.8)
        if len(dists) > 1:
            axes[0][0].legend(frameon=False)
        fig.suptitle("② 최적화 전후 비교", x=0.01, ha="left", fontsize=14, color=INK)
        fig.tight_layout()
        fig.savefig(out / "3_optimization.png", dpi=200)
        plt.close(fig)

    # 4 quality vs cost
    if args.quality:
        q = {r["condition"]: fnum(r["score"]) for r in csv.DictReader(open(args.quality, encoding="utf-8-sig"))}
        d0 = "near" if "near" in dists else dists[0]
        keep = set(METHOD_ORDER) | {"shell16_v1"}          # MSAA / adaptive variants look the same: leave them out
        pts = [(c, val(d0, c), q[c]) for c in q if c in keep and val(d0, c) is not None and q[c] is not None]
        if pts:
            fig, ax = plt.subplots(figsize=(7, 4.6))
            ax.scatter([p[1] for p in pts], [p[2] for p in pts], s=70, color=C_NEAR, edgecolor=SURFACE, linewidth=2, zorder=3)
            for c, x, y in pts:
                ax.annotate(METHOD_KO.get(c, c), (x, y), xytext=(6, 4), textcoords="offset points", fontsize=9, color=INK2)
            ax.set_xlabel(f"{ylabel} — {DIST_KO.get(d0, d0)}")
            ax.set_ylabel("자연스러움 점수 (1~5, 블라인드 평가 평균)")
            style(ax, "③ 화질 대비 비용 (왼쪽 위일수록 좋음)")
            ax.grid(color=GRID, linewidth=0.8)
            fig.tight_layout()
            fig.savefig(out / "4_quality_vs_cost.png", dpi=200)
            plt.close(fig)

    print(f"\nwritten to {out.resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
