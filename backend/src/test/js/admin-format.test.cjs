// Pure helpers of the admin dashboard (static/admin/admin-format.js). Run: node --test backend/src/test/js/admin-format.test.cjs
const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const F = require(path.resolve(__dirname, "../../main/resources/static/admin/admin-format.js"));

test("durations in ms read as ms, seconds, minutes and hours", () => {
  assert.equal(F.formatDuration(null), "—");
  assert.equal(F.formatDuration(undefined), "—");
  assert.equal(F.formatDuration(0), "0ms");
  assert.equal(F.formatDuration(850), "850ms");
  assert.equal(F.formatDuration(3000), "3초");
  assert.equal(F.formatDuration(3249), "3.2초");
  assert.equal(F.formatDuration(9999), "9.9초");
  assert.equal(F.formatDuration(42_500), "42초");
  assert.equal(F.formatDuration(59_999), "59초");
  assert.equal(F.formatDuration(60_000), "1분");
  assert.equal(F.formatDuration(245_000), "4분 5초");
  assert.equal(F.formatDuration(3_600_000), "1시간");
  assert.equal(F.formatDuration(3_720_000), "1시간 2분");
  assert.equal(F.formatDuration(-5), "0ms");
});

test("bytes use 1024 steps", () => {
  assert.equal(F.formatBytes(null), "—");
  assert.equal(F.formatBytes(512), "512 B");
  assert.equal(F.formatBytes(1536), "1.5 KB");
  assert.equal(F.formatBytes(5_242_880), "5.0 MB");
  assert.equal(F.formatBytes(100 * 1024 ** 3), "100.0 GB");
  assert.equal(F.formatMegabytes(9120), "9,120 MB");
  assert.equal(F.formatMegabytes(null), "—");
});

test("ratios read as percent without a useless .0", () => {
  assert.equal(F.formatPercent(5 / 6), "83.3%");
  assert.equal(F.formatPercent(1), "100%");
  assert.equal(F.formatPercent(0), "0%");
  assert.equal(F.formatPercent(null), "—");
  assert.equal(F.formatCount(12345), "12,345");
});

test("relative times count back from now", () => {
  const now = Date.parse("2026-11-06T12:00:00Z");
  assert.equal(F.formatRelative("2026-11-06T11:59:55Z", now), "방금");
  assert.equal(F.formatRelative("2026-11-06T11:59:30Z", now), "30초 전");
  assert.equal(F.formatRelative("2026-11-06T11:57:00Z", now), "3분 전");
  assert.equal(F.formatRelative("2026-11-06T10:00:00Z", now), "2시간 전");
  assert.equal(F.formatRelative("2026-11-02T12:00:00Z", now), "4일 전");
  assert.equal(F.formatRelative("2026-11-06T12:00:30Z", now), "방금"); // server clock slightly ahead
  assert.equal(F.formatRelative("not a date", now), "—");
});

test("unknown job statuses become UNKNOWN instead of breaking the table", () => {
  assert.equal(F.statusKey("PROCESSING"), "PROCESSING");
  assert.equal(F.statusLabel("PROCESSING"), "생성 중");
  assert.equal(F.statusLabel("COMPLETED"), "완료");
  assert.equal(F.statusKey("CANCELED"), "UNKNOWN");
  assert.equal(F.statusLabel("CANCELED"), "알 수 없음");
  assert.equal(F.statusKey(undefined), "UNKNOWN");
  assert.equal(F.statusKey("toString"), "UNKNOWN");
});

test("percentile pairs show p50 / p95, or a dash without samples", () => {
  assert.equal(F.formatPercentiles({ n: 5, p50: 3400, p95: 5500 }), "3.4초 / 5.5초");
  assert.equal(F.formatPercentiles({ n: 0, p50: null, p95: null }), "—");
  assert.equal(F.formatPercentiles(undefined), "—");
});

test("GPU card says 정보 없음 while overview.inference is null", () => {
  assert.deepEqual(F.inferenceSummary(null), { state: "UNKNOWN", label: "GPU 상태: 정보 없음", details: [] });
  assert.equal(F.inferenceSummary(undefined).label, "GPU 상태: 정보 없음");

  const now = Date.parse("2026-11-06T12:00:00Z");
  const up = F.inferenceSummary({ state: "UP", device: "cuda", modelLoaded: true, checkedAt: "2026-11-06T11:59:58Z" }, now);
  assert.equal(up.state, "UP");
  assert.equal(up.label, "GPU 상태: 정상");
  assert.deepEqual(up.details, ["장치 cuda", "모델 불러옴", "확인 방금"]);

  assert.equal(F.inferenceSummary({ state: "down" }).label, "GPU 상태: 꺼짐");
  assert.equal(F.inferenceSummary({ status: "WARMING" }).label, "GPU 상태: WARMING");
  assert.equal(F.inferenceSummary({}).label, "GPU 상태: 알 수 없음");
});

test("disk summary gives the used share of the file system", () => {
  const disk = F.diskSummary({ usableBytes: 25 * 1024 ** 3, totalBytes: 100 * 1024 ** 3, storageBytes: 1024 });
  assert.equal(disk.usedRatio, 0.75);
  assert.equal(disk.label, "남은 25.0 GB / 전체 100.0 GB");
  assert.deepEqual(F.diskSummary(null), { usedRatio: null, label: "—" });
  assert.deepEqual(F.diskSummary({ usableBytes: 0, totalBytes: 0 }), { usedRatio: null, label: "—" });
});

test("export and stats URLs leave passthrough out unless the box is ticked", () => {
  assert.equal(F.runsCsvUrl(false), "/api/v1/admin/runs?format=csv");
  assert.equal(F.runsCsvUrl(true), "/api/v1/admin/runs?format=csv&includePassthrough=true");
  assert.equal(F.statsUrl(false), "/api/v1/admin/stats");
  assert.equal(F.statsUrl(true), "/api/v1/admin/stats?includePassthrough=true");
});

test("job ids are shortened for the table", () => {
  assert.equal(F.shortId("3fa85f64-5717-4562-b3fc-2c963f66afa6"), "3fa85f64");
  assert.equal(F.shortId("abc"), "abc");
  assert.equal(F.shortId(null), "—");
});
