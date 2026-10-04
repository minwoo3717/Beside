"use strict";

const form = document.querySelector("#upload-form");
const photoInput = document.querySelector("#photos");
const generateButton = document.querySelector("#generate-button");
const clearButton = document.querySelector("#clear-photos");
const previewList = document.querySelector("#photo-previews");
const errorMessage = document.querySelector("#error-message");
const result = document.querySelector("#result");
const resultPhoto = document.querySelector("#result-photo");
const placeholder = document.querySelector("#result-placeholder");
const progress = document.querySelector("#job-progress");
const jobReference = document.querySelector("#job-reference");
// API v1 accepts JPEG, PNG and WEBP only (docs/api/openapi.yaml createJob).
const allowedTypes = new Set(["image/jpeg", "image/png", "image/webp"]);
// User-facing copy per error.code, from docs/api/ERROR_CODES.md. The server message is for developers only.
const ERROR_MESSAGES = Object.freeze({
  INVALID_REQUEST: "요청이 올바르지 않아요. 페이지를 새로고침한 뒤 다시 시도해 주세요.",
  NO_PHOTOS: "사진을 1장 이상 선택해 주세요.",
  TOO_MANY_PHOTOS: "사진은 최대 10장까지 올릴 수 있어요.",
  UNSUPPORTED_IMAGE_TYPE: "JPG, PNG, WEBP 사진만 사용할 수 있어요.",
  PAYLOAD_TOO_LARGE: "사진 용량이 너무 커요. 장당 5MB, 전체 20MB 이하로 줄여 주세요.",
  JOB_NOT_FOUND: "작업을 찾을 수 없어요. 사진을 다시 올려 주세요.",
  NOT_FOUND: "요청한 정보를 찾을 수 없어요.",
  INTERNAL_ERROR: "서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요.",
  INFERENCE_FAILED: "3D 모델을 만들지 못했어요. 얼굴과 몸 전체가 잘 보이는 사진으로 다시 시도해 주세요.",
  INFERENCE_TIMEOUT: "생성 시간이 너무 오래 걸려 중단됐어요. 다시 시도해 주세요.",
  INFERENCE_UNAVAILABLE: "생성 서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.",
  CONVERSION_FAILED: "모델 파일을 만드는 중 문제가 생겼어요. 다시 시도해 주세요."
});
let selectedFiles = [];
let previewUrls = [];
let busy = false;
let activeRequest = null;

function showError(message) {
  errorMessage.textContent = message;
  errorMessage.hidden = !message;
}

function setBusy(value) {
  busy = value;
  photoInput.disabled = value;
  clearButton.disabled = value;
  generateButton.disabled = value || selectedFiles.length === 0;
  generateButton.querySelector("span").textContent = value ? "생성 진행 중…" : "3D 모델 생성";
  document.querySelector(".status-panel").setAttribute("aria-busy", String(value));
  progress.hidden = !value;
}

function setStatus(status, description) {
  const badge = document.querySelector("#status-badge");
  badge.textContent = status;
  badge.dataset.status = status;
  document.querySelector("#status-description").textContent = description;
  const order = ["WAITING", "PROCESSING", "COMPLETED"];
  document.querySelectorAll("[data-step]").forEach((step) => {
    step.removeAttribute("aria-current");
    if (step.dataset.step === status) step.setAttribute("aria-current", "step");
    step.classList.toggle("done", order.indexOf(step.dataset.step) < order.indexOf(status));
  });
}

function resetResult() {
  result.hidden = true;
  resultPhoto.removeAttribute("src");
  placeholder.hidden = false;
  jobReference.hidden = true;
  jobReference.textContent = "";
}

function releasePreviews() {
  previewUrls.forEach((url) => URL.revokeObjectURL(url));
  previewUrls = [];
  previewList.replaceChildren();
}

function clearSelection() {
  if (busy) return;
  resetResult();
  releasePreviews();
  selectedFiles = [];
  photoInput.value = "";
  document.querySelector("#selection-count").textContent = "선택한 사진 0장";
  clearButton.hidden = true;
  showError("");
  setStatus("WAITING", "사진을 선택하면 시작할 수 있어요.");
  setBusy(false);
}

photoInput.addEventListener("change", () => {
  const files = Array.from(photoInput.files);
  clearSelection();
  if (files.length === 0) return;
  const totalSize = files.reduce((sum, file) => sum + file.size, 0);
  if (files.length > 10 || totalSize > 18 * 1024 * 1024) {
    showError("사진은 최대 10장, 합계 18MB까지 선택해 주세요.");
    return;
  }
  if (files.some((file) => !allowedTypes.has(file.type) || file.size === 0 || file.size > 5 * 1024 * 1024)) {
    showError("장당 5MB 이하의 JPG, PNG, WEBP 사진을 선택해 주세요. 빈 파일은 사용할 수 없어요.");
    return;
  }
  selectedFiles = files;
  files.forEach((file, index) => {
    const url = URL.createObjectURL(file);
    previewUrls.push(url);
    const item = document.createElement("li");
    const image = document.createElement("img");
    image.src = url;
    image.alt = `선택한 사진 ${index + 1}: ${file.name}`;
    const caption = document.createElement("span");
    caption.textContent = index === 0 ? `대표 · ${file.name}` : file.name;
    caption.title = file.name;
    item.append(image, caption);
    previewList.append(item);
  });
  document.querySelector("#selection-count").textContent = `선택한 사진 ${files.length}장`;
  clearButton.hidden = false;
  setStatus("WAITING", "사진이 준비됐어요. 생성 버튼을 눌러주세요.");
  setBusy(false);
});

// Known codes map to ERROR_CODES.md copy; unknown codes fall back to generic text instead of failing (contract rule).
function copyFor(code) {
  return typeof code === "string" && Object.hasOwn(ERROR_MESSAGES, code) ? ERROR_MESSAGES[code] : null;
}

// API v1 error responses are { error: { code, message, jobId? } }.
function httpErrorMessage(status, text) {
  let code = null;
  try { code = JSON.parse(text)?.error?.code ?? null; } catch { /* not the v1 error envelope */ }
  if (copyFor(code)) return copyFor(code);
  if (status === 413) return ERROR_MESSAGES.PAYLOAD_TOO_LARGE;
  if (status === 404) return ERROR_MESSAGES.JOB_NOT_FOUND;
  return `요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요. (HTTP ${status})`;
}

async function request(url, options = {}, timeoutMs = 15000) {
  const controller = new AbortController();
  activeRequest = controller;
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { ...options, signal: controller.signal, cache: "no-store" });
    const text = await response.text();
    if (!response.ok) {
      console.error("Backend request failed", response.status, text);
      throw new Error(httpErrorMessage(response.status, text));
    }
    return { response, text };
  } catch (error) {
    if (error.name === "AbortError") throw new Error("서버 응답 대기 시간이 초과됐습니다. 서버 상태를 확인하고 다시 시도해 주세요.");
    if (error instanceof TypeError) throw new Error("서버에 연결할 수 없습니다. 서버가 실행 중인지 확인해 주세요.");
    throw error;
  } finally {
    clearTimeout(timer);
    if (activeRequest === controller) activeRequest = null;
  }
}

function parseJob(text, jobId) {
  let job;
  try { job = JSON.parse(text); } catch { throw new Error("작업 상태 응답을 읽을 수 없습니다."); }
  if (!job || job.id !== jobId || !["PENDING", "PROCESSING", "COMPLETED", "FAILED"].includes(job.status)) {
    throw new Error("서버에서 올바른 작업 상태를 받지 못했습니다.");
  }
  return job;
}

async function pollJob(jobId) {
  const deadline = performance.now() + 60000;
  while (performance.now() < deadline) {
    const remaining = deadline - performance.now();
    const { text } = await request(`/api/v1/jobs/${encodeURIComponent(jobId)}`, {}, Math.min(15000, Math.max(1, remaining)));
    if (performance.now() >= deadline) break;
    const job = parseJob(text, jobId);
    if (job.status === "FAILED") throw new Error(copyFor(job.error?.code) || "작업이 실패했습니다. 다시 생성해 주세요.");
    if (job.status === "COMPLETED") {
      setStatus("COMPLETED", "완료됐어요! 아래에서 Mock 결과를 확인해 보세요.");
      resultPhoto.src = previewUrls[0];
      placeholder.hidden = true;
      result.hidden = false;
      return;
    }
    setStatus(job.status === "PENDING" ? "WAITING" : "PROCESSING",
      job.status === "PENDING" ? "작업을 접수했어요. Mock 생성을 기다리고 있어요." : "사진을 바탕으로 Mock 생성 과정을 진행하고 있어요.");
    await new Promise((resolve) => setTimeout(resolve, Math.min(1000, Math.max(0, deadline - performance.now()))));
  }
  throw new Error("60초 안에 작업이 완료되지 않았습니다. 다시 시도해 주세요.");
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (busy || selectedFiles.length === 0) return;
  resetResult();
  showError("");
  setBusy(true);
  setStatus("WAITING", "사진을 업로드하고 있어요…");
  try {
    const data = new FormData();
    selectedFiles.forEach((file, index) => data.append("photos", file, `${index + 1}-${file.name}`));
    const { response } = await request("/api/v1/jobs", { method: "POST", body: data });
    const location = response.headers.get("Location");
    if (response.status !== 202 || !location) throw new Error("서버에서 작업 ID를 받지 못했습니다.");
    const jobUrl = new URL(location, window.location.origin);
    const match = jobUrl.pathname.match(/^\/api\/v1\/jobs\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i);
    if (jobUrl.origin !== window.location.origin || !match || jobUrl.search || jobUrl.hash) throw new Error("올바르지 않은 작업 조회 주소입니다.");
    jobReference.textContent = `JOB ${match[1]}`;
    jobReference.hidden = false;
    await pollJob(match[1]);
  } catch (error) {
    console.error("Beside Again Mock workflow failed", error);
    setStatus("FAILED", "진행을 완료하지 못했어요. 오류 내용을 확인해 주세요.");
    showError(error.message || "알 수 없는 오류가 발생했습니다.");
  } finally {
    setBusy(false);
  }
});

clearButton.addEventListener("click", clearSelection);
document.querySelector("#start-over").addEventListener("click", () => { clearSelection(); photoInput.focus(); });
window.addEventListener("pagehide", () => { if (activeRequest) activeRequest.abort(); });
