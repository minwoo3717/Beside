# unity — Android AR 클라이언트 (Unity 트랙)

공통 규칙은 루트 [CLAUDE.md](../CLAUDE.md). 여기에는 이 트랙의 규칙·실행·테스트 명령만 적는다.

## 규칙

- 앱은 **Spring API v1(`/api/v1`)만** 호출한다. v0 `/api/jobs`와 Python 추론 서비스는 호출하지 않는다.
- 계약 DTO는 `Assets/Beside/Api/`에 있다(`JobResponse`, `ApiResponses`, `ApiEnums`, `ApiV1Routes`). 필드 이름은 [docs/api/openapi.yaml](../docs/api/openapi.yaml)과 같아야 하며, 계약이 바뀌면 DTO만 고친다. HTTP 호출은 `BesideApiClient`에만 둔다(UI·JobManager에서 UnityWebRequest 직접 사용 금지).
- 알 수 없는 enum 값(`status`, `variant`, `error.code`)은 크래시 대신 `Unknown`으로 처리한다(`ApiEnums.ParseJobStatus` 등). 모르는 JSON 필드는 무시한다.
- 오류 표시 문구는 `Assets/Beside/Api/ErrorMessages.cs`에서만 관리하며 [docs/api/ERROR_CODES.md](../docs/api/ERROR_CODES.md)의 `error.code` 기준이다. 서버 `message`는 로그에만 남기고 사용자에게 보여주지 않는다. 앱 쪽 실패 코드(`NETWORK_ERROR`, `DOWNLOAD_FAILED`, `FILE_CORRUPT`, `LOAD_FAILED`, `WAIT_TIMEOUT`)도 같은 파일에 둔다.
- JSON 역직렬화는 Newtonsoft Json(`com.unity.nuget.newtonsoft-json`)을 쓴다. `JsonUtility`는 nullable 숫자와 null 객체를 처리하지 못한다. (`MetricsRecorder`의 로그 직렬화만 예외로 `JsonUtility`를 쓴다 — 출력 전용이라 nullable 이 없다.)
- GLB는 `Application.persistentDataPath/models/{jobId}-{variant}.glb`에 캐시하고 매직 바이트(`glTF`)를 검증한 뒤 glTFast로 로드한다. 로드·보정·스포너 등록은 `RuntimeModelLoader`만 담당한다. 배치 규칙은 [docs/asset/GLB_SPEC.md](../docs/asset/GLB_SPEC.md) §7 — 현재는 샘플 GLB가 정규화되지 않아 **높이 0.4 m 정규화**를 쓰고, 변환기 정규화가 들어오면 `normalizeMode = LongestAxis, targetSize = 0.6`으로 바꾼다.
- 측정값은 `Application.persistentDataPath/metrics/metrics.csv`에 [docs/METRICS.md](../docs/METRICS.md)의 열 이름 그대로 기록한다(`MetricsRecorder`). 구간별 로그는 `[BesideMetric]` 접두어의 JSON 한 줄(`request_id`, `job_id`, `stage`, `duration_ms`, `error_code`)로 남기고, 모든 서버 요청에 `X-Request-Id`를 붙인다.
- UI는 코드로 만든다(`UiKit`/`DesignKit`, 씬에 Canvas 없음). UI 컨트롤러는 `JobManager`의 이벤트(`StateChanged`, `JobFailed`, `ModelReady`, `ModelPlaced`)만 구독하고 API를 직접 호출하지 않는다. 기본 UI(`MainUIController`)와 디자인 UI(`DesignedUIController`)는 같은 오브젝트에 두고 **하나만 켠다**.
- 플랫폼 플러그인에 의존하는 코드는 Scripting Define Symbol로 감싼다: NativeGallery → `BESIDE_NATIVEGALLERY`. 심볼이 없어도 컴파일되고 대체 경로(화면 캡처)가 동작해야 한다.
- 스크립트 파일은 **삭제 후 재생성하지 않는다** — Unity가 GUID를 새로 매겨 씬·프리팹 참조가 끊긴다. 내용 교체는 덮어쓰기, 이동은 Unity Project 창 안에서 한다.
- Unity 생성 폴더(`Library/`, `Temp/`, `Logs/`, `obj/`, `Build*/`, `UserSettings/`)는 커밋하지 않는다. `Packages/manifest.json`, `ProjectSettings/`는 커밋한다.

## 실행

- **Unity 6 (6000.6.0f1)** + AR Mobile 템플릿(URP). 패키지, 씬 구성, Android 빌드, 폰↔PC 연결 절차는 [README.md](README.md).
- Mock 서버: `cd ..\backend; .\gradlew.bat bootRun` (Java 17+, Java 21은 Lombok 1.18.34+). 연결 확인은 `GET {baseUrl}/api/v1/healthz`.
  - USB: `adb reverse tcp:8080 tcp:8080` 후 폰·에디터 모두 `http://localhost:8080`.
  - Wi-Fi: PC IP 사용(예 `http://192.168.0.10:8080`), 방화벽 8080 허용.
- 서버 종료: 서버 창에서 `Ctrl+C`. 포트가 남아 있으면 `netstat -ano | findstr :8080` → `taskkill /PID <pid> /F`.
- 실패 흐름 테스트: 파일명에 `fail`이 들어간 사진을 업로드하면 Mock 서버가 FAILED를 돌려준다(기본 UI의 "실패 테스트" 버튼). `POST /api/v1/jobs/{id}/retry`로 재시도 흐름까지 확인한다.

## 테스트

- 에디터: Play 모드에서 `PhotoPicker.editorTestImagePaths`로 업로드 → 폴링 → 다운로드 → 미리보기 흐름 확인. XR Simulation으로 바닥 클릭 배치까지 확인한다.
- 기기: Android Logcat 패키지(`Window > Analysis > Android Logcat`) 또는 `adb logcat -s Unity`. 검색어 `BesideMetric`, `RuntimeModelLoader`, `JobManager`.
- `metrics.csv` 회수: `adb pull /sdcard/Android/data/<패키지명>/files/metrics/metrics.csv .` (배치 후 30초가 지나야 한 줄이 써진다).
- 시나리오 표(정상·실패+재시도·복원·캐시·서버 종료·AR 안내)는 [README.md](README.md) "테스트 시나리오".
- [계획] Unity Test Framework(EditMode)로 DTO 파싱(알 수 없는 enum → `Unknown`, 모르는 필드 무시)과 `ErrorMessages` 매핑 누락을 검증한다.
