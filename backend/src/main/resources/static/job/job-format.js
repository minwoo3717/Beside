// Pure helpers of the job detail page /job/?id={jobId} (no DOM, no fetch): the job id in the address, the rater name
// and rating body rules (same as the server, docs/api/openapi.yaml putJobRating), URLs and Korean messages.
// Loaded by /job/index.html as window.BesideJob and by node:test (src/test/js/job-format.test.cjs) via require.
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.BesideJob = api;
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  const DASH = "—";
  const MAX_RATER_LENGTH = 40;
  const MAX_COMMENT_LENGTH = 500;
  const RATER_STORAGE_KEY = "beside.rater";
  const JOB_ID = /^[A-Za-z0-9-]{1,64}$/;
  // docs/METRICS.md §4: names, labels and what to look at. The order is the contract's.
  const RATING_ITEMS = Object.freeze([
    Object.freeze({ name: "faceShape", label: "얼굴 형태", hint: "주둥이 길이, 이마·눈 위치, 전체 윤곽" }),
    Object.freeze({ name: "ears", label: "귀 모양/위치", hint: "선 귀/접힌 귀, 크기, 붙은 위치" }),
    Object.freeze({ name: "bodyProportion", label: "체형 비율", hint: "다리 길이, 몸통 길이/높이 비, 꼬리" }),
    Object.freeze({ name: "furColor", label: "털 색상", hint: "주 색상과 밝기" }),
    Object.freeze({ name: "pattern", label: "무늬 재현", hint: "반점·줄무늬·얼굴 마스크의 위치와 경계" }),
    Object.freeze({ name: "textureArtifacts", label: "텍스처 번짐/seam", hint: "UV 경계선, 늘어남, 뭉개짐 (5 = 결함 없음)" })
  ]);
  // docs/api/ERROR_CODES.md job failure codes → the app's Korean text.
  const JOB_ERROR_TEXT = Object.freeze({
    INFERENCE_FAILED: "3D 모델을 만들지 못했어요. 얼굴과 몸 전체가 잘 보이는 사진으로 다시 시도해 주세요.",
    INFERENCE_TIMEOUT: "생성 시간이 너무 오래 걸려 중단됐어요. 다시 시도해 주세요.",
    INFERENCE_UNAVAILABLE: "생성 서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.",
    CONVERSION_FAILED: "모델 파일을 만드는 중 문제가 생겼어요. 다시 시도해 주세요.",
    INTERNAL_ERROR: "서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요."
  });
  // HTTP error codes this page can meet when saving a rating or creating a share link.
  const REQUEST_ERROR_TEXT = Object.freeze({
    INVALID_REQUEST: "입력값을 확인해 주세요.",
    JOB_NOT_FOUND: "작업을 찾을 수 없어요.",
    ASSET_NOT_FOUND: "모델 파일이 없어요.",
    JOB_NOT_COMPLETED: "완료된 작업만 평가하거나 공유할 수 있어요.",
    INTERNAL_ERROR: "서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요."
  });

  function codePoints(text) {
    return Array.from(text).length;
  }

  /** The id of ?id=… when it looks like a job id, else null. */
  function jobIdFromSearch(search) {
    let id = null;
    try {
      id = new URLSearchParams(typeof search === "string" ? search : "").get("id");
    } catch (badQuery) {
      return null;
    }
    id = id === null ? "" : id.trim();
    return JOB_ID.test(id) ? id : null;
  }

  /**
   * Rater name as the server stores it: trimmed, 1~40 characters, no / \ ; or control characters, not only dots.
   * { ok: true, value } | { ok: false, message } (Korean).
   */
  function normalizeRater(name) {
    const value = typeof name === "string" ? name.trim() : "";
    const length = codePoints(value);
    if (length === 0) return { ok: false, message: "평가자 이름을 적어 주세요." };
    if (length > MAX_RATER_LENGTH) return { ok: false, message: `평가자 이름은 ${MAX_RATER_LENGTH}자까지예요.` };
    // eslint-disable-next-line no-control-regex
    if (/[\/\\;\u0000-\u001f\u007f-\u009f]/.test(value) || /^\.+$/.test(value)) {
      return { ok: false, message: "평가자 이름에 / \\ ; 는 쓸 수 없어요." };
    }
    return { ok: true, value };
  }

  /**
   * The PUT body from the form: every item an integer 1~5 (numbers or numeric strings from radio values),
   * the comment trimmed (empty → omitted). { ok: true, body } | { ok: false, message, missing: [names] }.
   */
  function ratingBody(scores, comment) {
    const body = {};
    const missing = [];
    for (const item of RATING_ITEMS) {
      const raw = scores ? scores[item.name] : undefined;
      const value = typeof raw === "string" && /^[1-5]$/.test(raw) ? Number(raw) : raw;
      if (Number.isInteger(value) && value >= 1 && value <= 5) body[item.name] = value;
      else missing.push(item.name);
    }
    if (missing.length > 0) {
      const labels = RATING_ITEMS.filter((item) => missing.includes(item.name)).map((item) => item.label);
      return { ok: false, message: `${labels.join(", ")} 점수를 골라 주세요.`, missing };
    }
    const text = typeof comment === "string" ? comment.trim() : "";
    if (codePoints(text) > MAX_COMMENT_LENGTH) {
      return { ok: false, message: `코멘트는 ${MAX_COMMENT_LENGTH}자까지예요.`, missing: [] };
    }
    if (text) body.comment = text;
    return { ok: true, body };
  }

  function jobUrl(jobId) {
    return `/api/v1/jobs/${encodeURIComponent(jobId)}`;
  }

  /** The base GLB the page shows (GET downloadAsset without variant = base). */
  function assetUrl(jobId) {
    return `${jobUrl(jobId)}/asset`;
  }

  function photoUrl(jobId, index) {
    return `${jobUrl(jobId)}/photos/${Number(index)}`;
  }

  function ratingsUrl(jobId) {
    return `${jobUrl(jobId)}/ratings`;
  }

  function ratingUrl(jobId, rater) {
    return `${ratingsUrl(jobId)}/${encodeURIComponent(rater)}`;
  }

  function sharesUrl(jobId) {
    return `${jobUrl(jobId)}/shares`;
  }

  function adminJobUrl(jobId) {
    return `/api/v1/admin/jobs/${encodeURIComponent(jobId)}`;
  }

  /** ShareCreatedResponse.url (/s/{token}) as an absolute link on this server, or null for anything else. */
  function shareLink(origin, url) {
    if (typeof url !== "string" || !/^\/s\/[A-Za-z0-9_-]+$/.test(url)) return null;
    try {
      return new URL(url, origin).href;
    } catch (badOrigin) {
      return null;
    }
  }

  /** Average score → "3.67"; null → "—". */
  function formatScore(value) {
    return typeof value === "number" && Number.isFinite(value) ? value.toFixed(2) : DASH;
  }

  /** Korean text for JobResponse.error.code; unknown codes get the general text (never a crash). */
  function jobErrorText(code) {
    return JOB_ERROR_TEXT[code] || "알 수 없는 이유로 실패했어요.";
  }

  /** Korean text for a failed request: the v1 error code when known, else the HTTP status. */
  function requestErrorText(status, code) {
    if (REQUEST_ERROR_TEXT[code]) return REQUEST_ERROR_TEXT[code];
    if (status === 401 || status === 403) return "로그인이 필요해요. 페이지를 새로 고쳐 주세요.";
    if (!status) return "서버에 연결할 수 없어요.";
    return `요청이 실패했어요 (HTTP ${status}).`;
  }

  /** PENDING or PROCESSING: keep polling. */
  function isInFlight(status) {
    return status === "PENDING" || status === "PROCESSING";
  }

  /** The rating of {@code rater} in a JobRatingList, or null (prefills the form when a rater edits their score). */
  function ratingOf(list, rater) {
    const items = list && Array.isArray(list.items) ? list.items : [];
    return items.find((item) => item && item.rater === rater) || null;
  }

  return Object.freeze({
    DASH, MAX_RATER_LENGTH, MAX_COMMENT_LENGTH, RATER_STORAGE_KEY, RATING_ITEMS,
    jobIdFromSearch, normalizeRater, ratingBody, jobUrl, assetUrl, photoUrl, ratingsUrl, ratingUrl, sharesUrl,
    adminJobUrl, shareLink, formatScore, jobErrorText, requestErrorText, isInFlight, ratingOf
  });
});
