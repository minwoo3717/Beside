"""Summary statistics and the noise rule used to compare two versions (standard library only).

percentile() interpolates linearly between closest ranks: numpy's default "linear" method, Hyndman & Fan type 7
(also Excel PERCENTILE.INC). compare() pools the samples of every repetition for the medians and uses the spread of
the per-repetition medians as the noise estimate: a difference counts only when it is at least THRESHOLD_PCT percent
AND the ranges [min, max] of the per-repetition medians of the two versions do not overlap. A baseline median of 0
against a non-zero candidate has no rate of change: the verdict is then "n/a", never "same".
"""
from __future__ import annotations

import math
import statistics

THRESHOLD_PCT = 10.0
PERCENTILES = (50, 90, 95, 99)


def _round(value, digits=3):
    return None if value is None else round(float(value), digits)


def percentile(values, p: float) -> float:
    """The p-th percentile (0..100) with linear interpolation (numpy "linear", type 7)."""
    xs = sorted(float(v) for v in values)
    if not xs:
        raise ValueError("percentile of an empty sample")
    if not 0 <= p <= 100:
        raise ValueError("p must be within 0..100")
    if len(xs) == 1:
        return xs[0]
    h = (len(xs) - 1) * p / 100.0
    lo = math.floor(h)
    hi = min(lo + 1, len(xs) - 1)
    return xs[lo] + (h - lo) * (xs[hi] - xs[lo])


def median(values) -> float:
    return percentile(values, 50)


def summary(values) -> dict:
    """{n, min, p50, p90, p95, p99, max, mean, stdev}, rounded to 3 decimals; stdev is the sample (n-1) one.

    An empty sample gives n=0 and None everywhere else; a single value has stdev 0.
    """
    xs = sorted(float(v) for v in values)
    n = len(xs)
    if n == 0:
        return {"n": 0, "min": None, "p50": None, "p90": None, "p95": None, "p99": None, "max": None,
                "mean": None, "stdev": None}
    out = {"n": n, "min": _round(xs[0])}
    for p in PERCENTILES:
        out[f"p{p}"] = _round(percentile(xs, p))
    out["max"] = _round(xs[-1])
    out["mean"] = _round(math.fsum(xs) / n)
    out["stdev"] = _round(statistics.stdev(xs) if n >= 2 else 0.0)
    return out


def _clean(reps):
    """Drop empty repetitions; every repetition becomes a list of floats."""
    return [[float(v) for v in rep] for rep in reps if rep is not None and len(rep) > 0]


def compare(baseline_samples_by_rep, candidate_samples_by_rep, lower_is_better: bool = True,
            threshold_pct: float = THRESHOLD_PCT) -> dict:
    """Compare a candidate version with the baseline.

    Both arguments are lists with one list of samples per repetition (a single value per repetition works the same
    way). Returns the pooled medians, deltaPct = (candidate - baseline) / baseline * 100, the per-repetition median
    ranges, whether they overlap, and the verdict: "better" or "worse" only when |deltaPct| >= threshold_pct and the
    ranges do not overlap, otherwise "same". With lower_is_better=False (throughput) a higher candidate is "better".
    When the baseline median is 0 and the candidate's is not, deltaPct is None and the verdict is "n/a" (not
    comparable): calling that "same" would hide, say, 0 -> 5 as noise.
    Returns None when either side has no samples.
    """
    base = _clean(baseline_samples_by_rep)
    cand = _clean(candidate_samples_by_rep)
    if not base or not cand:
        return None
    b = median([x for rep in base for x in rep])
    c = median([x for rep in cand for x in rep])
    b_meds = [median(rep) for rep in base]
    c_meds = [median(rep) for rep in cand]
    b_range = (min(b_meds), max(b_meds))
    c_range = (min(c_meds), max(c_meds))
    overlap = b_range[0] <= c_range[1] and c_range[0] <= b_range[1]
    if b != 0:
        delta = (c - b) / abs(b) * 100.0
    else:
        delta = 0.0 if c == 0 else None
    if delta is None:
        verdict = "n/a"
    elif abs(delta) >= threshold_pct and not overlap:
        improved = c < b if lower_is_better else c > b
        verdict = "better" if improved else "worse"
    else:
        verdict = "same"
    return {
        "baseline": _round(b),
        "candidate": _round(c),
        "deltaPct": _round(delta, 2),
        "baselineRange": [_round(b_range[0]), _round(b_range[1])],
        "candidateRange": [_round(c_range[0]), _round(c_range[1])],
        "overlap": overlap,
        "reps": [len(base), len(cand)],
        "lowerIsBetter": lower_is_better,
        "verdict": verdict,
    }
