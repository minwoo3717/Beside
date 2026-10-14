# unity — Android AR 클라이언트 (Unity 트랙)

공통 규칙은 루트 [CLAUDE.md](../CLAUDE.md). 여기에는 이 트랙의 규칙·실행·테스트 명령만 적는다.

## 규칙

### API·데이터
- 앱은 **Spring API v1(`/api/v1`)만** 호출한다. v0 `/api/jobs`와 Python 추론 서비스는 호출하지 않는다.
- 계약 DTO는 `Assets/Beside/Api/`에 있다(`JobResponse`, `ApiResponses`, `ApiEnums`, `ApiV1Routes`). 필드 이름은 [docs/api/openapi.yaml](../docs/api/openapi.yaml)과 같아야 하며, 계약이 바뀌면 DTO만 고친다. HTTP 호출은 `BesideApiClient`에만 둔다(UI·JobManager에서 UnityWebRequest 직접 사용 금지).
- 알 수 없는 enum 값(`status`, `variant`, `error.code`)은 크래시 대신 `Unknown`으로 처리한다(`ApiEnums.ParseJobStatus` 등). 모르는 JSON 필드는 무시한다.
- 오류 표시 문구는 `Assets/Beside/Api/ErrorMessages.cs`에서만 관리하며 [docs/api/ERROR_CODES.md](../docs/api/ERROR_CODES.md)의 `error.code` 기준이다. 서버 `message`는 로그에만 남기고 사용자에게 보여주지 않는다. 앱 쪽 실패 코드(`NETWORK_ERROR`, `DOWNLOAD_FAILED`, `FILE_CORRUPT`, `LOAD_FAILED`, `WAIT_TIMEOUT`)도 같은 파일에 둔다.
- JSON 역직렬화는 Newtonsoft Json(`com.unity.nuget.newtonsoft-json`)을 쓴다. `JsonUtility`는 nullable 숫자와 null 객체를 처리하지 못한다. (`MetricsRecorder`의 로그 직렬화만 예외 — 출력 전용이라 nullable 이 없다.)
- 서버 주소는 `JobManager`가 소유한다. 런타임 변경은 `JobManager.SetBaseUrl`(PlayerPrefs `beside.baseUrl`, 비우면 Inspector 기본값), 저장 전 확인은 `TestBaseUrl`. UI는 주소를 따로 저장하지 않는다. 주소를 바꾸면 저장된 jobId는 지운다(다른 서버의 작업).

### 모델·배치
- GLB는 `Application.persistentDataPath/models/{jobId}-{variant}.glb`에 캐시하고 매직 바이트(`glTF`)를 검증한 뒤 glTFast로 로드한다. 로드·보정·스포너 등록은 `RuntimeModelLoader`만 담당한다.
- 배치 규칙은 [docs/asset/GLB_SPEC.md](../docs/asset/GLB_SPEC.md) §7: 생성기 출력은 최장 축 1 m·머리 +Z·발바닥 원점, 앱 기본값은 `normalizeMode = LongestAxis`, `targetSize = 0.6`, `modelFacing = PlusZ`. 정면이 다른 에셋은 GLB를 고치지 말고 `modelFacing`으로 맞춘다(예전 OBJ 기반 샘플은 MinusX).
- 배치는 템플릿 `ObjectSpawner` 경로로만 한다(바닥 터치, `CenterPlacer.PlaceAtCenter` → `TrySpawnObject`). 개수 제한(기본 1, 추가 터치 무시)과 바닥 전용(법선이 수직 ±25°) 규칙은 `SpawnLimiter` 한 곳에서 지킨다. 배치된 모델 지우기도 `SpawnLimiter.ClearAll`을 쓴다.
- 바닥 인식은 `PlaneScanGate`가 켜고 끈다(디자인 UI의 `scanOnlyInAR`): AR 화면에서만 `ARPlaneManager`를 켜고, 배치된 모델이 없을 때 진입하면 `ARSession.Reset()`으로 처음부터 인식한다. 다른 코드에서 `ARPlaneManager.enabled`를 직접 바꾸지 않는다. 배치 후 바닥 점 숨김은 `PlaneVisibility`(렌더러만 끄고 인식은 유지).
- 리깅 GLB의 클립 이름은 `Idle`, `Walk`, `Sit`, `TailWag`, `HeadTilt`(GLB_SPEC rigged 확정 전 임시 계약). 재생은 `PetAnimationPlayer`만 한다(glTFast 레거시 `Animation`). 클립 이름이 바뀌면 `PetAnimationPlayer`와 디자인 UI 버튼만 고친다.
- 셸 퍼는 `ShellFurRenderer`가 그린다. 리깅 메시는 프레임당 한 번만 `BakeMesh`하고 모든 껍질이 공유한다(껍질마다 굽지 않는다).
- **SMAL 파생 데이터(리깅 GLB, 웨이트, `.pkl`)는 커밋하지 않는다.** 학술용 라이선스다.

### UI
- UI는 코드로 만든다(`UiKit`/`DesignKit`, 씬에 Canvas 없음). 화면 생성은 **`OnEnable`**에서 한다(`Awake`는 컴포넌트가 꺼져 있어도 실행되므로 꺼진 UI가 화면에 남는다). `OnDisable`에서 숨긴다.
- UI 컨트롤러는 `JobManager` 이벤트(`StateChanged`, `JobFailed`, `ModelReady`, `ModelPlaced`)와 위 AR 컴포넌트의 공개 메서드만 쓰고 API를 직접 호출하지 않는다. 기본 UI(`MainUIController`)와 디자인 UI(`DesignedUIController`)는 같은 오브젝트에 두고 **하나만 켠다**.
- 화면 상태(버튼·강조)는 이벤트 한 번이 아니라 **실제 상태**(배치된 모델 유무, 재생 중인 클립)를 보고 맞춘다. 이벤트는 작업당 한 번만 오는 것이 있다(`ModelPlaced`).
- 템플릿 UI(Create/Options/Object Menu/Greeting 등)는 디자인 UI가 실행 시 숨긴다(`hideTemplateObjects`). Coaching UI는 남긴다.

### 프로젝트
- 플랫폼 플러그인에 의존하는 코드는 Scripting Define Symbol로 감싼다: NativeGallery → `BESIDE_NATIVEGALLERY`. 심볼이 없어도 컴파일되고 대체 경로(화면 캡처)가 동작해야 한다.
- 템플릿 샘플 클래스는 버전마다 네임스페이스가 다를 수 있다. 꼭 필요하지 않으면 타입 이름으로 찾는다(`SpawnLimiter`의 `ARInteractorSpawnTrigger` 처리).
- 스크립트 파일은 **삭제 후 재생성하지 않는다** — Unity가 GUID를 새로 매겨 씬·프리팹 참조가 끊긴다. 내용 교체는 덮어쓰기, 이동은 Unity Project 창 안에서 한다.
- Unity 생성 폴더(`Library/`, `Temp/`, `Logs/`, `obj/`, `Build*/`, `UserSettings/`)는 커밋하지 않는다. `Packages/manifest.json`, `ProjectSettings/`는 커밋한다.
- 측정값은 `Application.persistentDataPath/metrics/metrics.csv`에 [docs/METRICS.md](../docs/METRICS.md)의 열 이름 그대로 기록한다(`MetricsRecorder`, 열 추가 금지). 구간별 로그는 `[BesideMetric]` 접두어의 JSON 한 줄(`request_id`, `job_id`, `stage`, `duration_ms`, `error_code`)로 남기고, 모든 서버 요청에 `X-Request-Id`를 붙인다.

## 실행

- **Unity 6 (6000.6.0f1)** + AR Mobile 템플릿(URP). 패키지, 씬 구성(Inspector 값), Android 빌드, 연결 절차는 [README.md](README.md).
- Mock 서버: `cd ..\backend; .\gradlew.bat bootRun` (Java 17+, Java 21은 Lombok 1.18.34+). 연결 확인은 `GET {baseUrl}/api/v1/healthz` 또는 앱 "서버 설정 → 연결 확인".
  - USB: `adb reverse tcp:8080 tcp:8080` 후 `http://localhost:8080`.
  - Wi-Fi: `http://<PC IP>:8080`, 방화벽 8080 허용.
  - 외부 네트워크: `cloudflared tunnel --url http://localhost:8080` → 출력된 `https://…trycloudflare.com`을 앱 서버 설정에 저장(실행마다 주소가 바뀐다, 테스트 후 `Ctrl+C`).
- 서버 종료: 서버 창에서 `Ctrl+C`. 포트가 남아 있으면 `netstat -ano | findstr :8080` → `taskkill /PID <pid> /F`.
- 실패 흐름 테스트: 파일명에 `fail`이 들어간 사진을 업로드하면 Mock 서버가 FAILED를 돌려준다(기본 UI의 "실패 테스트" 버튼). `POST /api/v1/jobs/{id}/retry`로 재시도 흐름까지 확인한다.
- 리깅 샘플: `backend/storage/results/sample-dog.glb`(로컬 교체본, `git update-index --assume-unchanged`로 커밋 제외).

## 테스트

- 에디터: Play 모드에서 `PhotoPicker.editorTestImagePaths`로 업로드 → 폴링 → 다운로드 → 미리보기 → XR Simulation 바닥 클릭 배치. 리깅 모델은 키보드 1~5(Idle/Walk/Sit/TailWag/HeadTilt).
- 리깅 모델 털(로컬): 앱에서 "처음부터" → `ModelLoader`의 Test Glb Url + Test Hair Length/Flow Url → Play.
- 기기: Android Logcat 패키지(`Window > Analysis > Android Logcat`) 또는 `adb logcat -s Unity`. 검색어 `BesideMetric`, `RuntimeModelLoader`, `JobManager`, `SpawnLimiter`, `PlaneScanGate`.
- `metrics.csv` 회수: `adb pull /sdcard/Android/data/<패키지명>/files/metrics/metrics.csv .` (배치 후 30초가 지나야 한 줄이 써진다).
- 시나리오 표(정상·바닥 인식 시점·배치 제한·벽 차단·바닥 점·애니메이션·서버 변경·실패+재시도·복원·캐시·서버 종료·AR 안내)는 [README.md](README.md) "테스트 시나리오".
- [계획] Unity Test Framework(EditMode)로 DTO 파싱(알 수 없는 enum → `Unknown`, 모르는 필드 무시), `ErrorMessages` 매핑 누락, `JobManager.NormalizeUrl`을 검증한다.
