using System.Collections.Generic;

namespace Beside.Api
{
    /// <summary>
    /// error.code -> user-facing Korean text (docs/api/ERROR_CODES.md) plus app-side codes.
    /// Server "message" is never shown; unknown codes fall back to a generic line.
    /// </summary>
    public static class ErrorMessages
    {
        // App-side codes (not from the server)
        public const string DownloadFailed = "DOWNLOAD_FAILED";
        public const string FileCorrupt = "FILE_CORRUPT";
        public const string LoadFailed = "LOAD_FAILED";
        public const string WaitTimeout = "WAIT_TIMEOUT";

        static readonly Dictionary<string, string> Text = new Dictionary<string, string>
        {
            // HTTP errors
            { "INVALID_REQUEST", "요청이 올바르지 않아요. 앱을 최신 버전으로 업데이트해 주세요." },
            { "NO_PHOTOS", "사진을 1장 이상 선택해 주세요." },
            { "TOO_MANY_PHOTOS", "사진은 최대 10장까지 올릴 수 있어요." },
            { "UNSUPPORTED_IMAGE_TYPE", "JPG, PNG, WEBP 사진만 사용할 수 있어요." },
            { "PAYLOAD_TOO_LARGE", "사진 용량이 너무 커요. 장당 5MB, 전체 20MB 이하로 줄여 주세요." },
            { "JOB_NOT_FOUND", "작업을 찾을 수 없어요. 사진을 다시 올려 주세요." },
            { "ASSET_NOT_FOUND", "요청한 모델 파일이 없어요." },
            { "NOT_FOUND", "요청한 정보를 찾을 수 없어요." },
            { "JOB_NOT_COMPLETED", "아직 모델을 만들고 있어요. 잠시 후 다시 확인해 주세요." },
            { "JOB_NOT_FAILED", "실패한 작업만 다시 시도할 수 있어요." },
            { "INTERNAL_ERROR", "서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요." },
            // Job failures (status = FAILED)
            { "INFERENCE_FAILED", "3D 모델을 만들지 못했어요. 얼굴과 몸 전체가 잘 보이는 사진으로 다시 시도해 주세요." },
            { "INFERENCE_TIMEOUT", "생성 시간이 너무 오래 걸려 중단됐어요. 다시 시도해 주세요." },
            { "INFERENCE_UNAVAILABLE", "생성 서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요." },
            { "CONVERSION_FAILED", "모델 파일을 만드는 중 문제가 생겼어요. 다시 시도해 주세요." },
            // App-side
            { ApiError.Network, "서버에 연결할 수 없어요. 네트워크를 확인해 주세요." },
            { ApiError.BadResponse, "서버 응답을 이해할 수 없어요. 앱을 최신 버전으로 업데이트해 주세요." },
            { DownloadFailed, "모델을 다운로드하지 못했어요. 다시 시도해 주세요." },
            { FileCorrupt, "받은 모델 파일이 손상되었어요. 다시 시도해 주세요." },
            { LoadFailed, "모델을 불러오지 못했어요." },
            { WaitTimeout, "생성이 너무 오래 걸리고 있어요. 잠시 후 다시 확인해 주세요." },
        };

        /// <summary>Codes where "다시 시도" makes sense. Server-side job failures use POST /retry; others re-run the step.</summary>
        static readonly HashSet<string> Retryable = new HashSet<string>
        {
            "INFERENCE_FAILED", "INFERENCE_TIMEOUT", "INFERENCE_UNAVAILABLE", "CONVERSION_FAILED",
            "INTERNAL_ERROR", "JOB_NOT_COMPLETED",
            ApiError.Network, DownloadFailed, FileCorrupt, WaitTimeout,
        };

        public static string ForCode(string code) =>
            code != null && Text.TryGetValue(code, out var t) ? t : "문제가 생겼어요. 잠시 후 다시 시도해 주세요.";

        public static bool IsRetryable(string code) => code != null && Retryable.Contains(code);

        /// <summary>Server job failure codes: retry goes through POST /api/v1/jobs/{id}/retry.</summary>
        public static bool IsJobFailureCode(string code) =>
            code == "INFERENCE_FAILED" || code == "INFERENCE_TIMEOUT" || code == "INFERENCE_UNAVAILABLE" || code == "CONVERSION_FAILED";
    }
}
