# unity — Beside Android AR 클라이언트 (Unity 트랙)

Unity 트랙의 소스 폴더다. 아직 **실행 가능한 Unity 프로젝트가 아니다**: `Assets/Beside/` 아래의 C# 파일(계약 DTO + 틀 3개)만 있고 씬·Packages·ProjectSettings 는 없다. 규칙과 명령 요약은 [CLAUDE.md](CLAUDE.md).

Unity 프로젝트는 팀원이 Unity Hub 로 생성한 뒤 이 폴더의 `Assets/Beside/` 를 프로젝트의 `Assets/Beside/` 로 복사한다. 프로젝트를 이 `unity/` 폴더 안에 바로 생성하면 복사가 필요 없고 Unity 생성 폴더(`Library/`, `Temp/` 등)는 이미 `.gitignore` 에 있다 — 둘 중 어느 쪽으로 할지는 1단계 시작 때 정한다.

```text
unity/
├─ CLAUDE.md
├─ README.md
└─ Assets/Beside/
   ├─ Api/
   │  ├─ ApiV1Routes.cs        # 경로 상수와 URL 빌더 (= docs/api/openapi.yaml)
   │  ├─ ApiEnums.cs           # JobStatus / AssetVariant + Unknown 처리
   │  ├─ JobResponse.cs        # JobResponse, Timings, AssetInfo, JobError, UploadedFile
   │  ├─ ApiResponses.cs       # JobCreatedResponse, JobListResponse, ErrorResponse, ErrorDetail, HealthResponse
   │  └─ BesideApiClient.cs    # UnityWebRequest 경계 (틀, TODO)
   ├─ Job/JobManager.cs        # 업로드 → 폴링 → 다운로드 수명주기 (틀, TODO)
   └─ UI/MainUIController.cs   # 버튼·상태·오류 표시 연결 (틀, TODO)
```

## 상태

- **[구현됨]** 계약 DTO (`Assets/Beside/Api/*.cs`): [openapi.yaml](../docs/api/openapi.yaml) v1 과 필드 이름 1:1, 순수 C#(UnityEngine 참조 없음). 알 수 없는 `status`/`variant` 는 `Unknown`.
- **[구현됨]** 틀 3개: `BesideApiClient` 는 `NotImplementedException`, `JobManager`/`MainUIController` 는 빈 TODO 메서드. 통신이나 성공을 흉내 내지 않는다.
- **[계획]** 갤러리 선택, 업로드, 폴링, GLB 다운로드/캐시, glTFast 로드, AR 배치, `metrics.csv` — [docs/PLAN.md](../docs/PLAN.md) 1·2단계.

## 환경 (제안값 — 계약 회의에서 확정)

| 항목 | 값 |
|---|---|
| Unity | **2022.3 LTS**, URP 템플릿 |
| 패키지 | AR Foundation 5.1.x · Google ARCore XR Plugin 5.1.x · glTFast (`com.unity.cloud.gltfast` 6.x) · NativeGallery (yasirkula, OpenUPM `com.yasirkula.nativegallery`) · **Newtonsoft Json** (`com.unity.nuget.newtonsoft-json` 3.x) |
| Android | Minimum API Level **24** (Android 7.0, ARCore 최소), Target API 33 이상, Scripting Backend IL2CPP, ARM64, Graphics API 는 ARCore 가 요구하는 OpenGLES3/Vulkan |
| 권한 | `INTERNET` (UnityWebRequest 사용 시 자동 포함), `CAMERA` (AR Foundation 이 추가), 사진 접근은 NativeGallery 가 처리 |
| 개발용 HTTP | Mock 서버가 `http://` 라 cleartext 허용이 필요하다 (아래) |

JSON 역직렬화는 Newtonsoft 를 쓴다. `JsonUtility` 는 `progress`(nullable), `asset`/`error`(null 객체), 문자열 enum 을 처리하지 못한다.

### 개발용 cleartext 허용 (릴리스 전 제거)

`Assets/Plugins/Android/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- 개발용: 같은 Wi-Fi 의 PC Mock 서버(http)를 허용한다. 릴리스 빌드에서는 제거한다. -->
    <base-config cleartextTrafficPermitted="true" />
</network-security-config>
```

`Assets/Plugins/Android/AndroidManifest.xml` 의 `<application>` 에 `android:networkSecurityConfig="@xml/network_security_config"` 를 추가한다(Project Settings → Player → Publishing Settings → Custom Main Manifest).

## 폰 ↔ PC Mock 서버 연결 절차

1. PC: `cd backend; .\gradlew.bat bootRun` (기본 profile mock).
2. 폰과 PC 를 같은 Wi-Fi 에 연결. PC 의 IPv4 주소 확인: `ipconfig` (예 `192.168.0.10`).
3. Windows 방화벽에서 8080 인바운드 허용: 첫 실행 때 Java 허용 팝업, 또는 관리자 PowerShell 에서 `netsh advfirewall firewall add rule name="Beside 8080" dir=in action=allow protocol=TCP localport=8080`.
4. 폰 브라우저에서 `http://192.168.0.10:8080/api/v1/healthz` 가 `{"status":"ok","profile":"mock","workerType":"mock"}` 를 보이면 연결된 것이다.
5. PC 에서 `.\scripts\e2e_mock.ps1 -BaseUrl http://192.168.0.10:8080` 으로 전체 흐름을 먼저 점검한다.
6. 앱의 `JobManager.baseUrl` 에 `http://192.168.0.10:8080` 을 입력한다. 에디터에서는 `http://localhost:8080`.

## API 사용 규칙 (v1)

- 업로드: `UnityWebRequest.Post(url, List<IMultipartFormSection>)` + `MultipartFormFileSection("photos", bytes, fileName, "image/jpeg")`. 서버는 **파트의 Content-Type** 으로 jpeg/png/webp 를 검사하므로 섹션마다 올바른 타입을 넣는다. 1~10장, 장당 ≤5MB, 전체 ≤20MB.
- 202 응답의 `JobCreatedResponse.jobId` 를 쓴다(`Location` 헤더에도 같은 값).
- 폴링은 1~2초 간격. Mock 은 약 5초에 끝나지만 real 은 수 분이 걸릴 수 있으므로 `maxWaitSeconds` 는 real 연동 때 늘린다(제안 600).
- `COMPLETED` → `asset.url`(서버 루트 기준 상대 경로)을 `baseUrl` 과 결합해 `DownloadHandlerFile` 로 `Application.persistentDataPath/models/{jobId}-base.glb` 에 저장 → glTFast 로드. 같은 jobId 는 다시 받지 않는다.
- `FAILED` → `error.code` 로 [ERROR_CODES.md](../docs/api/ERROR_CODES.md) 의 문구 표시, "다시 시도" 버튼은 `POST /api/v1/jobs/{jobId}/retry`(202 → 다시 폴링). Mock 서버에서는 파일명에 `fail` 이 들어간 사진으로 이 흐름을 재현한다.
- 모든 오류 응답은 `ErrorResponse` 봉투(400/404/409/413/500). 알 수 없는 `code`·`status` 는 크래시 대신 일반 문구/`Unknown`.
- 배치 규칙(기본 스케일 0.6, 핀치 0.2~1.5, 원점 발바닥 중심, 정면 +Z)은 [GLB_SPEC.md](../docs/asset/GLB_SPEC.md) §7.

## metrics.csv

- 위치: `Application.persistentDataPath/metrics/metrics.csv` — Android 에서는 `/storage/emulated/0/Android/data/<패키지명>/files/metrics/metrics.csv`.
- 헤더(순서 고정, [METRICS.md](../docs/METRICS.md)): `jobId,uploadMs,waitMs,downloadMs,loadMs,e2eMs,avgFps,minFps,memMB,device`
- 회수: `adb pull /sdcard/Android/data/<패키지명>/files/metrics/metrics.csv .`

## 틀 3개의 역할

| 스크립트 | 역할 |
|---|---|
| `Api/BesideApiClient.cs` | 일반 C# 클래스. UnityWebRequest 기반 업로드·작업 조회·에셋 다운로드의 경계와 코루틴 시그니처 |
| `Job/JobManager.cs` | MonoBehaviour. API Client 호출, 상태 관리, 폴링 간격·최대 대기, 취소, 다운로드 완료 전달 |
| `UI/MainUIController.cs` | MonoBehaviour. 사진 선택·생성 요청 버튼과 상태·오류·완료 표시 연결 |

Unity 프로젝트에 넣을 때: 씬에 빈 GameObject 를 만들고 `JobManager` 와 `MainUIController` 를 추가, `MainUIController.jobManager` 참조 연결, `JobManager.baseUrl` 설정, `MainUIController.editorTestImagePaths` 에 테스트 사진 경로 입력. `BesideApiClient` 는 컴포넌트가 아니다.

## 직접 할 일

- Unity 프로젝트 생성과 패키지 설치 (PLAN 1단계 첫 주).
- backend 의 `sample-dog.glb` 가 0바이트라 교체되기 전까지 glTFast 로드 테스트는 별도의 유효한 GLB 로 한다.
