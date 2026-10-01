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
const allowedTypes = new Set(["image/jpeg", "image/png", "image/webp", "image/gif"]);
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
    showError("장당 5MB 이하의 JPG, PNG, WEBP, GIF 사진을 선택해 주세요. 빈 파일은 사용할 수 없어요.");
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

async function request(url, options = {}, timeoutMs = 15000) {
  const controller = new AbortController();
  activeRequest = controller;
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { ...options, signal: controller.signal, cache: "no-store" });
    const text = await response.text();
    if (!response.ok) {
      if (response.status === 413) throw new Error("사진 용량이 너무 큽니다. 더 작은 사진으로 다시 시도해 주세요.");
      if (response.status === 404) throw new Error("작업을 찾을 수 없습니다. 서버가 재시작됐다면 다시 생성해 주세요.");
      console.error("Backend request failed", response.status, text);
      throw new Error(`요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요. (HTTP ${response.status})`);
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
    const { text } = await request(`/api/jobs/${encodeURIComponent(jobId)}`, {}, Math.min(15000, Math.max(1, remaining)));
    if (performance.now() >= deadline) break;
    const job = parseJob(text, jobId);
    if (job.status === "FAILED") throw new Error("Mock 작업이 실패했습니다. 다시 생성해 주세요.");
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
    const { response } = await request("/api/jobs", { method: "POST", body: data });
    const location = response.headers.get("Location");
    if (response.status !== 202 || !location) throw new Error("서버에서 작업 ID를 받지 못했습니다.");
    const jobUrl = new URL(location, window.location.origin);
    const match = jobUrl.pathname.match(/^\/api\/jobs\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i);
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
