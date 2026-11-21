// Pure helpers of the pet library page (no DOM, no fetch): labels, checks, routes and the waiting text.
// Loaded by /library/index.html as window.BesideLibrary and by node:test (src/test/js/library-core.test.cjs) via require.
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.BesideLibrary = api;
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  const DASH = "—";
  const MB = 1024 * 1024;
  // Same upload rules as the Mock UI (static/app.js) and the API (docs/api/openapi.yaml createJob).
  const PHOTO_RULES = Object.freeze({ maxCount: 10, maxEachBytes: 5 * MB, maxTotalBytes: 18 * MB,
    types: Object.freeze(["image/jpeg", "image/png", "image/webp"]) });
  const NAME_MAX = 40;
  const NOTE_MAX = 500;
  // Polling of a job made here: every 2 s, for up to 20 min (a real GPU run takes minutes, plus the queue).
  const POLL_MS = 2000;
  const MAX_WAIT_MS = 20 * 60 * 1000;
  const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

  // PetResponse.species (docs/api/openapi.yaml). Unknown values read as "알 수 없음", never a crash.
  const SPECIES = Object.freeze([
    Object.freeze({ value: "DOG", label: "강아지" }),
    Object.freeze({ value: "CAT", label: "고양이" }),
    Object.freeze({ value: "OTHER", label: "기타" })
  ]);
  // JobResponse.status. Unknown values are UNKNOWN.
  const STATUS_LABELS = Object.freeze({ PENDING: "대기", PROCESSING: "생성 중", COMPLETED: "완료", FAILED: "실패" });
  // Copy per error.code from docs/api/ERROR_CODES.md (HTTP and job failure codes). The server message is for developers.
  const ERROR_MESSAGES = Object.freeze({
    INVALID_REQUEST: "요청이 올바르지 않아요. 입력을 확인하고 다시 시도해 주세요.",
    NO_PHOTOS: "사진을 1장 이상 선택해 주세요.",
    TOO_MANY_PHOTOS: "사진은 최대 10장까지 올릴 수 있어요.",
    UNSUPPORTED_IMAGE_TYPE: "JPG, PNG, WEBP 사진만 사용할 수 있어요.",
    // First-photo check before the 202 (real·prod, ERROR_CODES.md '대표 사진 검사'): the same photo is refused again.
    UNREADABLE_IMAGE: "대표 사진을 읽을 수 없어요. 다른 사진을 골라 주세요.",
    IMAGE_TOO_SMALL: "대표 사진의 해상도가 너무 낮아요. 더 큰 사진을 골라 주세요.",
    NO_ANIMAL_DETECTED: "대표 사진에서 강아지나 고양이를 찾지 못했어요. 반려동물이 잘 보이는 사진을 골라 주세요.",
    TOO_MANY_ANIMALS: "대표 사진에 반려동물이 여러 마리 있어요. 한 마리만 나온 사진을 골라 주세요.",
    ANIMAL_TOO_SMALL: "반려동물이 너무 작게 나왔어요. 더 가까이에서 찍은 사진을 골라 주세요.",
    IMAGE_TOO_DARK: "대표 사진이 너무 어두워요. 밝은 곳에서 찍은 사진을 골라 주세요.",
    IMAGE_TOO_BLURRY: "대표 사진이 흔들렸어요. 선명한 사진을 골라 주세요.",
    PAYLOAD_TOO_LARGE: "사진 용량이 너무 커요. 장당 5MB, 전체 20MB 이하로 줄여 주세요.",
    JOB_NOT_FOUND: "작업을 찾을 수 없어요. 목록을 새로고침해 주세요.",
    ASSET_NOT_FOUND: "요청한 파일이 없어요.",
    NOT_FOUND: "요청한 정보를 찾을 수 없어요.",
    PET_NOT_FOUND: "반려동물을 찾을 수 없어요. 목록을 새로고침해 주세요.",
    JOB_NOT_COMPLETED: "아직 모델을 만들고 있어요. 잠시 후 다시 확인해 주세요.",
    JOB_NOT_FAILED: "실패한 작업만 다시 시도할 수 있어요.",
    JOB_NOT_PENDING: "이미 생성이 시작돼 취소할 수 없어요.",
    JOB_IN_PROGRESS: "아직 만들고 있는 작업은 지울 수 없어요. 취소하거나 끝난 뒤 지워 주세요.",
    INTERNAL_ERROR: "서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요.",
    QUEUE_FULL: "지금 생성 요청이 많아요. 잠시 후 다시 시도해 주세요.",
    INFERENCE_FAILED: "3D 모델을 만들지 못했어요. 얼굴과 몸 전체가 잘 보이는 사진으로 다시 시도해 주세요.",
    INFERENCE_TIMEOUT: "생성 시간이 너무 오래 걸려 중단됐어요. 다시 시도해 주세요.",
    INFERENCE_UNAVAILABLE: "생성 서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.",
    CONVERSION_FAILED: "모델 파일을 만드는 중 문제가 생겼어요. 다시 시도해 주세요.",
    CANCELED: "취소한 작업이에요."
  });
  // Same clips, labels and order as static/share.js and Unity PetAnimationPlayer (library-core.test.cjs compares them).
  const IDLE = "Idle";
  const ANIMATIONS = Object.freeze([
    Object.freeze({ name: "Walk", label: "걷기", once: false }),
    Object.freeze({ name: "Sit", label: "앉기", once: true }),
    Object.freeze({ name: "TailWag", label: "꼬리", once: false }),
    Object.freeze({ name: "HeadTilt", label: "갸웃", once: true }),
    Object.freeze({ name: IDLE, label: "기본", once: false })
  ]);

  function isNumber(value) {
    return typeof value === "number" && Number.isFinite(value);
  }

  function speciesLabel(value) {
    const known = SPECIES.find((species) => species.value === value);
    return known ? known.label : "알 수 없음";
  }

  /** The status itself when known, else "UNKNOWN" (for data-status styling). */
  function statusKey(status) {
    return Object.hasOwn(STATUS_LABELS, status) ? status : "UNKNOWN";
  }

  function statusLabel(status) {
    return Object.hasOwn(STATUS_LABELS, status) ? STATUS_LABELS[status] : "알 수 없음";
  }

  function isFinished(status) {
    return status === "COMPLETED" || status === "FAILED";
  }

  /** POST /cancel takes PENDING jobs only. */
  function canCancel(job) {
    return Boolean(job) && job.status === "PENDING";
  }

  /** Copy for an error.code; unknown codes fall back to a generic line with the HTTP status when there is one. */
  function errorMessage(code, httpStatus) {
    if (typeof code === "string" && Object.hasOwn(ERROR_MESSAGES, code)) return ERROR_MESSAGES[code];
    if (httpStatus === 413) return ERROR_MESSAGES.PAYLOAD_TOO_LARGE;
    return isNumber(httpStatus)
      ? `요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요. (HTTP ${httpStatus})`
      : "요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.";
  }

  /** "#/pets/{uuid}" → { view: "pet", petId }, anything else → { view: "home" }. */
  function parseRoute(hash) {
    const match = /^#\/pets\/([^/?#]+)\/?$/.exec(typeof hash === "string" ? hash : "");
    if (match && UUID.test(match[1])) return { view: "pet", petId: match[1].toLowerCase() };
    return { view: "home" };
  }

  function petRoute(petId) {
    return `#/pets/${encodeURIComponent(petId)}`;
  }

  /** The job detail page (ratings, share link, QR) lives at /job/?id=. */
  function jobDetailUrl(jobId) {
    return `/job/?id=${encodeURIComponent(jobId)}`;
  }

  function assetUrl(jobId) {
    return `/api/v1/jobs/${encodeURIComponent(jobId)}/asset?variant=base`;
  }

  /** "2026.11.06 14:05" in the viewer's time zone; "—" for a missing or broken value. */
  function formatDateTime(iso) {
    const date = new Date(typeof iso === "string" ? iso : NaN);
    if (Number.isNaN(date.getTime())) return DASH;
    const pad = (value) => String(value).padStart(2, "0");
    return `${date.getFullYear()}.${pad(date.getMonth() + 1)}.${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
  }

  /** Remaining time as the app shows it: "약 N분" (rounded up), "1분 이내" under a minute; null when unknown. */
  function formatRemaining(ms) {
    if (!isNumber(ms) || ms < 0) return null;
    if (ms < 60000) return "1분 이내";
    return `약 ${Math.ceil(ms / 60000)}분`;
  }

  /**
   * One line for a running job: "앞에 2명 · 약 3분", "곧 시작해요 · 약 1분", "GPU 서버를 기다리는 중이에요",
   * "3D 모델을 만드는 중 · 약 2분 남음"; the outcome for finished jobs.
   */
  function waitText(job) {
    if (!job || typeof job !== "object") return "";
    const remaining = formatRemaining(job.estimate && job.estimate.remainingMs);
    if (job.status === "PENDING") {
      const queue = job.queue || {};
      if (queue.reason === "INFERENCE_OFFLINE") return "GPU 서버가 준비되기를 기다리는 중이에요";
      const ahead = isNumber(queue.ahead) ? queue.ahead : null;
      const position = ahead && ahead > 0 ? `앞에 ${ahead}명` : "곧 시작해요";
      return remaining ? `${position} · ${remaining}` : position;
    }
    if (job.status === "PROCESSING") {
      if (job.estimate && job.estimate.remainingMs === 0) return "3D 모델을 만드는 중 · 평소보다 오래 걸리고 있어요";
      return remaining ? `3D 모델을 만드는 중 · ${remaining} 남음` : "3D 모델을 만드는 중이에요";
    }
    if (job.status === "COMPLETED") return "완성됐어요";
    if (job.status === "FAILED") return errorMessage(job.error && job.error.code);
    return "상태를 알 수 없어요";
  }

  /** null when the selection can be uploaded, else the Korean reason. Accepts File-like { size, type } objects. */
  function checkPhotos(files) {
    const list = Array.from(files || []);
    if (list.length === 0) return "사진을 1장 이상 선택해 주세요.";
    const total = list.reduce((sum, file) => sum + (isNumber(file.size) ? file.size : 0), 0);
    if (list.length > PHOTO_RULES.maxCount || total > PHOTO_RULES.maxTotalBytes) {
      return "사진은 최대 10장, 합계 18MB까지 선택해 주세요.";
    }
    if (list.some((file) => !PHOTO_RULES.types.includes(file.type) || !(file.size > 0) || file.size > PHOTO_RULES.maxEachBytes)) {
      return "장당 5MB 이하의 JPG, PNG, WEBP 사진을 선택해 주세요. 빈 파일은 사용할 수 없어요.";
    }
    return null;
  }

  /** null when the pet form is fine, else the reason (same limits as the server: name 1~40 after trim, note ≤ 500). */
  function checkPetForm(form) {
    const name = String((form && form.name) || "").trim();
    const note = String((form && form.note) || "").trim();
    if (!name) return "이름을 입력해 주세요.";
    if (name.length > NAME_MAX) return `이름은 ${NAME_MAX}자까지 쓸 수 있어요.`;
    if (note.length > NOTE_MAX) return `메모는 ${NOTE_MAX}자까지 쓸 수 있어요.`;
    return null;
  }

  /** Body of POST /pets or PATCH /pets/{id} from the form: trimmed, empty note as null. */
  function petBody(form) {
    const note = String((form && form.note) || "").trim();
    return { name: String((form && form.name) || "").trim(), species: (form && form.species) || "DOG", note: note || null };
  }

  /** Jobs that belong to no pet (made by the app): petId null or missing. */
  function unassigned(jobs) {
    return Array.from(jobs || []).filter((job) => job && (job.petId === null || job.petId === undefined));
  }

  /** "사진 3장" from uploadedFiles. */
  function photoCount(job) {
    const files = job && Array.isArray(job.uploadedFiles) ? job.uploadedFiles.length : 0;
    return `사진 ${files}장`;
  }

  /** The job to show in 3D first: the cover, else the newest COMPLETED one, else null. */
  function viewerJob(pet) {
    const jobs = (pet && Array.isArray(pet.jobs)) ? pet.jobs : [];
    const cover = jobs.find((job) => job.id === (pet && pet.coverJobId) && job.status === "COMPLETED");
    return cover || jobs.find((job) => job.status === "COMPLETED") || null;
  }

  /** Browser notification text when a job made here ends. */
  function finishNotice(job, petName) {
    const who = petName ? `${petName}의 ` : "";
    if (job && job.status === "COMPLETED") return { title: `${who}3D 모델이 완성됐어요`, body: "보관함에서 확인해 보세요." };
    return { title: `${who}3D 모델을 만들지 못했어요`, body: waitText(job) };
  }

  function animationLabel(name) {
    const known = ANIMATIONS.find((clip) => clip.name === name);
    return known ? known.label : String(name);
  }

  /** One chip per clip in the GLB: known clips in the Unity order, then the others in file order (as share.js). */
  function animationChips(available) {
    const names = Array.from(available || [], String).filter((name, index, all) => name && all.indexOf(name) === index);
    const known = ANIMATIONS.filter((clip) => names.includes(clip.name)).map((clip) => ({ ...clip }));
    const others = names.filter((name) => !ANIMATIONS.some((clip) => clip.name === name))
      .map((name) => ({ name, label: name, once: false }));
    return known.concat(others);
  }

  function defaultAnimation(available) {
    const names = Array.from(available || [], String);
    return names.includes(IDLE) ? IDLE : (names[0] || null);
  }

  return Object.freeze({
    PHOTO_RULES, NAME_MAX, NOTE_MAX, POLL_MS, MAX_WAIT_MS, SPECIES, ANIMATIONS,
    speciesLabel, statusKey, statusLabel, isFinished, canCancel, errorMessage,
    parseRoute, petRoute, jobDetailUrl, assetUrl, formatDateTime, formatRemaining, waitText,
    checkPhotos, checkPetForm, petBody, unassigned, photoCount, viewerJob, finishNotice,
    animationLabel, animationChips, defaultAnimation
  });
});
