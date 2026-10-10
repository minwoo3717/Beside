# unity — Beside Android AR 클라이언트 (Unity 트랙)

Unity 트랙의 소스 폴더다. **실행 가능한 Unity 프로젝트**로, `Assets/`, `Packages/`, `ProjectSettings/` 가 올라가 있다. Unity 가 생성하는 폴더(`Library/`, `Temp/`, `Logs/`, `obj/`)는 `.gitignore` 로 제외되며 프로젝트를 열면 자동으로 다시 만들어진다. 규칙과 명령 요약은 [CLAUDE.md](CLAUDE.md).

```text
unity/
├─ CLAUDE.md
├─ README.md
├─ Packages/                   # manifest.json (설치 패키지 목록)
├─ ProjectSettings/            # Android 빌드 설정, XR 설정, Define Symbols
└─ Assets/
   ├─ Scenes/SampleScene.unity # 유일한 씬. 빌드에 포함
   ├─ MobileARTemplateAssets/  # Unity AR Mobile 템플릿(ObjectSpawner, 메뉴, 코칭 UI)
   ├─ Samples/XR Interaction Toolkit/3.5.1/
   ├─ Plugins/NativeGallery/   # 갤러리 선택 플러그인 (unitypackage 로 설치)
   ├─ Models/                  # 테스트용 강아지 모델·텍스처·털 지도
   └─ Beside/
      ├─ Api/
      │  ├─ ApiV1Routes.cs        # 경로 상수와 URL 빌더 (= docs/api/openapi.yaml)
      │  ├─ ApiEnums.cs           # JobStatus / AssetVariant + Unknown 처리
      │  ├─ JobResponse.cs        # JobResponse, Timings, AssetInfo, JobError, UploadedFile
      │  ├─ ApiResponses.cs       # JobCreatedResponse, JobListResponse, ErrorResponse, HealthResponse
      │  ├─ BesideApiClient.cs    # UnityWebRequest 경계: 업로드·조회·다운로드·retry·healthz
      │  └─ ErrorMessages.cs      # error.code → 사용자 문구, 재시도 가능 여부 (= ERROR_CODES.md)
      ├─ Job/JobManager.cs        # 업로드 → 폴링 → 캐시/다운로드 → 로드 수명주기, 복원, retry, 측정, 서버 주소 변경
      ├─ Loader/
      │  ├─ RuntimeModelLoader.cs # glTFast 로드, 정면·크기·바닥·콜라이더 보정, 머티리얼 교체, 스포너 등록
      │  ├─ ShellFur.shader       # URP 셸 퍼 셰이더 (털 길이·방향 지도 사용)
      │  └─ ShellFurRenderer.cs   # 셸 퍼 렌더링 + 스프링 물리 (일반 메시 / 리깅 메시 BakeMesh)
      ├─ Pet/PetAnimationPlayer.cs # 리깅 GLB 의 클립 재생 (Idle/Walk/Sit/TailWag/HeadTilt)
      ├─ Metrics/MetricsRecorder.cs # metrics.csv 기록, 구간별 JSON 로그, FPS·메모리 샘플링
      ├─ AR/
      │  ├─ ARGuidance.cs         # 카메라 권한·AR 미지원·바닥 미인식 안내
      │  ├─ CenterPlacer.cs       # 화면 중앙 배치 (바닥만)
      │  ├─ SpawnLimiter.cs       # 배치 개수 제한(기본 1, 추가 터치 무시), 벽·천장 배치 차단
      │  ├─ PlaneVisibility.cs    # 모델 배치 후 바닥 점 숨김
      │  └─ PlaneScanGate.cs      # AR 화면에서만 바닥 인식 (진입 시 세션 리셋)
      └─ UI/
         ├─ MainUIController.cs   # 기본 UI (테스트용 Canvas)
         ├─ UiKit.cs              # uGUI 위젯 빌더
         ├─ PhotoPicker.cs        # NativeGallery 선택 / 에디터 경로 / 화면 캡처 대체
         ├─ ModelPreview.cs       # 배치 전 3D 미리보기 (RenderTexture)
         └─ Designed/
            ├─ DesignKit.cs       # 디자인 토큰, 둥근 위젯
            └─ DesignedUIController.cs # 시안 적용 UI: 홈·사진 확인·만드는 중·미리보기·AR·실패·서버 설정
```

## 상태

- **[구현됨]** 계약 DTO (`Assets/Beside/Api/*.cs`): [openapi.yaml](../docs/api/openapi.yaml) v1 과 필드 이름 1:1. 알 수 없는 `status`/`variant` 는 `Unknown`.
- **[구현됨]** API 클라이언트: multipart 업로드(`Idempotency-Key`, `X-Request-Id`), 상태 조회, `DownloadHandlerFile` 다운로드, `POST /retry`, `healthz`, `ErrorResponse` 봉투 파싱.
- **[구현됨]** JobManager: 업로드 → 2초 폴링 → `persistentDataPath/models/{jobId}-base.glb` 캐시 확인 → 다운로드 → GLB 매직 검증 → glTFast 로드 → ObjectSpawner 0번 칸 교체. 앱 재실행 시 저장된 jobId 로 복원, 서버 실패는 `/retry`, 앱 실패는 재실행. **서버 주소를 앱에서 바꿔 저장**(`SetBaseUrl`, PlayerPrefs).
- **[구현됨]** 런타임 로더: 정면 보정(`modelFacing`, 머리 → +Z), 최장 축 0.6 m 정규화(기본), 발바닥 원점, BoxCollider, glTFast 머티리얼 → URP/Lit 교체(빌드 셰이더 누락 방지), 선택적 셸 퍼(리깅 메시 포함).
- **[구현됨]** 리깅 GLB 애니메이션: glTFast 레거시 Animation 으로 클립 재생, AR 화면 동작 버튼(걷기/앉기/꼬리/갸웃/기본), 실제 재생 중인 클립에 맞춘 버튼 강조. 에디터 단축키 1~5.
- **[구현됨]** AR 배치 규칙: 1마리 제한(추가 터치 무시), 바닥에만 배치(벽·천장 차단), 배치 후 바닥 점 숨김, **"AR로 배치하기" 이후에만 바닥 인식**(다른 화면에서는 인식 중지, 진입 시 세션 리셋).
- **[구현됨]** 측정: 구간별 JSON 로그(`[BesideMetric]`), `metrics.csv`(e2eMs 는 첫 AR 배치 시점), 배치 후 30초 FPS·메모리.
- **[구현됨]** UI 두 종류: 기본 UI(`MainUIController`)와 디자인 UI(`DesignedUIController`). 둘 다 붙이고 하나만 켠다(화면은 `OnEnable` 에서 생성되므로 꺼진 쪽은 나타나지 않는다). 디자인 UI: 갤러리 선택, 사진 확인, 진행 단계, 미리보기, AR 3단계 안내, 상단 안내 닫기(×), 하단 버튼 숨기기/보기, 서버 설정 화면, 템플릿 UI 자동 숨김.
- **[검증됨]** 실제 Android 기기: 바닥 인식·배치·조작, 서버 GLB 런타임 로드, v1 Mock 서버 전체 흐름(갤러리 → 생성 → 미리보기 → AR 배치), AR 화면 진입 후 바닥 인식.
- **[검증됨]** 에디터: 리깅 GLB 애니메이션·정면·크기, 동작 버튼, 배치 제한·벽 차단·바닥 점 숨김, `metrics.csv` 기록.
- **[미검증]** 리깅 모델 털(셸 퍼 BakeMesh), Cloudflare 터널 경유 전체 흐름, 실패 시나리오(fail.jpg, 서버 종료, 생성 중 앱 종료), 기기 `metrics.csv` 회수. [PLAN.md](../docs/PLAN.md) 3·4단계.
- **[계획]** 리깅 다음 단계(아래 "리깅 GLB" 참고): 바닥 위 걷기, GLB_SPEC rigged 계약, 추론 → 리깅 연결, 실제 사진 E2E 측정, 자세 따라하기. 그 밖에 터치 상호작용, 분류 모델(Jev) 연동, 크래시 수집(Sentry/Crashlytics).

## 환경 (실제 프로젝트 값)

| 항목 | 값 |
|---|---|
| Unity | **6 (6000.6.0f1)**, AR Mobile 템플릿 (URP). 다른 6000.x 버전으로 열면 업그레이드 안내가 뜬다. 2022.3 이하로는 열 수 없다 |
| 패키지 | AR Foundation 6.6.x · Google ARCore XR Plugin 6.6.x · XR Interaction Toolkit 3.5.1 (Starter Assets, AR Starter Assets 샘플) · Input System 1.20 · glTFast (`com.unity.cloud.gltfast`) · Newtonsoft Json (`com.unity.nuget.newtonsoft-json`) · NativeGallery (yasirkula, unitypackage) |
| Scripting Define Symbols | `BESIDE_NATIVEGALLERY` — NativeGallery 가 설치된 경우에만. 없으면 갤러리 코드가 꺼진 채 컴파일되고 화면 캡처가 사진을 대신한다 |
| Android | Minimum API Level 24 이상(템플릿 기본값), Scripting Backend IL2CPP, ARM64, Graphics API 는 템플릿 기본값 |
| 권한 | `INTERNET`, `CAMERA`(AR Foundation), 사진 접근은 NativeGallery |
| 개발용 HTTP | Player Settings → Other Settings → **Allow downloads over HTTP: Always allowed** (같은 Wi-Fi·USB 의 `http://` 서버용. 터널(https)만 쓰면 `Not allowed` 로 되돌려도 된다. 릴리스 전 `Not allowed`) |
| 외부 접속 (선택) | `cloudflared` (`winget install --id Cloudflare.cloudflared --source winget`) |

JSON 역직렬화는 Newtonsoft 를 쓴다. `JsonUtility` 는 `progress`(nullable), `asset`/`error`(null 객체), 문자열 enum 을 처리하지 못한다.

## 프로젝트 열기

1. 저장소를 받는다. `unity/` 가 Unity 프로젝트 루트다.
2. Unity Hub → Projects → **Add** → **Add project from disk** → `unity/` 폴더 선택. Unity 6000.6.0f1 이 없으면 Hub 가 설치를 안내한다.
3. 처음 열 때 `Library/` 를 만들며 수 분 걸린다. Package Manager 가 `Packages/manifest.json` 의 패키지를 자동 설치한다.
4. **NativeGallery 는 git 저장소에 포함돼 있다** (`Assets/Plugins/NativeGallery/`). 없거나 빠졌으면 [releases](https://github.com/yasirkula/UnityNativeGallery/releases) 의 `NativeGallery.unitypackage` 를 받아 Project 창에 드래그 → Import 한다. git URL 설치는 PC 에 Git 이 있어야 한다.
5. Console 에 빨간 오류가 없어야 한다. 노란 경고(`FindFirstObjectByType is obsolete`, `type 'long?' is skipped by serializer`, `LoadGltfBinary is obsolete`)는 무시한다.

## 씬 구성 (SampleScene)

씬은 저장돼 있어 보통 손댈 것이 없다. 참조가 끊겼을 때(Inspector 에 "Missing Script") 아래대로 복구한다.

| 오브젝트 | 컴포넌트 | 설정 |
|---|---|---|
| `ModelLoader` | `RuntimeModelLoader` | Shell Prefab = `RuntimeShell`, Replace Slot 0, **Normalize Mode LongestAxis, Target Size 0.6, Model Facing PlusZ** (리깅 GLB 기준. 예전 OBJ 기반 샘플은 Height 0.4 / MinusX), Base Material Template = `Dog_Mat`, Fur Material Template = `Dog_Fur_Mat`, Test 칸 비움 |
| `Beside` | `JobManager` | Base Url = 빌드 기본 주소(아래 연결 절차). 앱 서버 설정에서 저장한 주소가 있으면 그쪽이 우선 |
| `Beside` | `PhotoPicker` | Editor Test Image Paths 에 에디터용 테스트 사진 절대 경로 (선택) |
| `Beside` | `ModelPreview`, `ARGuidance`, `MetricsRecorder` | 기본값 |
| `Beside` | `SpawnLimiter` | Max Instances 1, Mode Block, Floor Only 체크, Verbose(확인 후 해제) |
| `Beside` | `PlaneVisibility` | Hide When Placed 체크 |
| `Beside` | `DesignedUIController` **또는** `MainUIController` | 하나만 체크. 디자인 UI: Font(Noto Sans KR TTF, 선택), Hero Image(선택), Show AR Instructions, Reshow On New Message, **Scan Only In AR** 체크, Hide Template Objects |
| `Beside` | `PlaneScanGate`, `CenterPlacer`, `PetAnimationPlayer` | 디자인 UI 가 없으면 자동으로 붙인다. 수동 추가 불필요 |
| `XR Origin (AR Rig)` | `ARPlaneManager` | **Detection Mode = Horizontal** (벽 인식 안 함) |
| `XR Origin > Camera Offset > Object Spawner` | `ObjectSpawner` | **Apply Random Angle At Spawn 해제** (정면이 카메라를 향함) |
| 같은 오브젝트 | `ARInteractorSpawnTrigger` | **Require Horizontal Up Surface 체크** |

`RuntimeShell` 은 템플릿 샘플 프리팹에서 모델(`Visuals`)·콜라이더(`Cube_COL`)·**Interaction Affordance** 를 뺀 껍데기(XR Grab Interactable, AR Transformer 만 남김)다. Interaction Affordance 를 남기면 지워진 `Visuals` 를 찾느라 매 프레임 NullReferenceException 이 난다. 로더가 이 안에 GLB 를 넣어 스포너에 등록한다. `Dog_Fur_Mat` 의 Shader 는 `Custom/ShellFur` 여야 한다.

## 폰 ↔ 서버 연결

앱에서 주소를 바꾸려면 **홈 오른쪽 위 "서버 설정"** → 주소 입력(`https://` 생략 가능, 경로 없이) → **연결 확인**(healthz) → **저장**. 저장한 주소는 앱을 다시 켜도 유지되고, 주소를 바꾸면 이전 서버의 작업 기록은 지워진다. "기본 주소로 되돌리기" = Inspector 의 `JobManager.baseUrl`.

### A. USB (adb reverse) — Wi-Fi 가 막힌 환경

1. PC: `cd backend; .\gradlew.bat bootRun` (Java 17 이상. Java 21 은 Lombok 1.18.34 이상 필요).
2. 폰 USB 디버깅 켜고 연결. adb 는 Unity 에 포함: `C:\Program Files\Unity\Hub\Editor\6000.6.0f1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK\platform-tools\adb.exe`.
3. `.\adb devices` 로 `device` 확인 후 `.\adb reverse tcp:8080 tcp:8080`.
4. 폰 브라우저에서 `http://localhost:8080/api/v1/healthz` 가 `{"status":"ok","profile":"mock","workerType":"mock"}` 를 보이면 연결된 것이다.
5. 서버 주소 = `http://localhost:8080` (에디터와 같음).
6. USB 를 뽑거나 폰을 재부팅하면 reverse 가 풀린다. 다시 3번.

### B. 같은 Wi-Fi

1. PC IPv4 확인: `ipconfig` (예 `192.168.0.10`).
2. 방화벽 8080 인바운드 허용: 관리자 PowerShell 에서 `netsh advfirewall firewall add rule name="Beside 8080" dir=in action=allow protocol=TCP localport=8080`.
3. 폰 브라우저에서 `http://192.168.0.10:8080/api/v1/healthz` 확인. 공유기가 AP 격리를 하면 폰 핫스팟에 PC 를 연결한다.
4. 서버 주소 = `http://192.168.0.10:8080`.

### C. 다른 네트워크 / 모바일 데이터 (Cloudflare 빠른 터널)

1. 백엔드 실행 (A-1).
2. 새 PowerShell 에서 `cloudflared tunnel --url http://localhost:8080`. 출력 박스의 `https://…trycloudflare.com` 주소를 복사하고 **창을 켜둔다**.
3. 폰(모바일 데이터)에서 `{주소}/api/v1/healthz` 확인. 502 면 백엔드가 꺼진 것이다.
4. 앱 서버 설정에 주소 저장.
- 빠른 터널 주소는 실행할 때마다 바뀐다. 고정 주소가 필요하면 Cloudflare 계정의 named tunnel 또는 ngrok 고정 도메인을 쓴다(앱은 `ngrok-skip-browser-warning` 헤더를 이미 보낸다).
- 주소를 아는 누구나 접속할 수 있다. 테스트 중에만 켜고(`Ctrl+C` 로 종료), 주소를 레포·발표 자료에 적지 않는다. 인증은 백엔드 계약 회의 안건.

### D. 흐름 사전 점검

PC 에서 `.\scripts\e2e_mock.ps1 -BaseUrl http://localhost:8080` 으로 서버 쪽 전체 흐름을 먼저 확인한다. `backend/storage/results/sample-dog.glb` 는 현재 **리깅 GLB** 로 교체해 쓰고 있다(아래 "리깅 GLB").

## 빌드 (Android)

1. `File > Build Profiles` → 플랫폼 **Android**, Scene List 에 `SampleScene` 체크. 다른 플랫폼이면 **Switch Platform**.
2. `Edit > Project Settings > XR Plug-in Management > Android` 에서 **Google ARCore** 체크, Project Validation 경고는 **Fix All**.
3. `Player > Other Settings` 확인: Package Name(기본값 유지. 두 빌드를 같이 설치하려면 변경), Minimum API Level 24+, IL2CPP, ARM64, **Allow downloads over HTTP**(위 환경 표), Scripting Define Symbols 에 `BESIDE_NATIVEGALLERY`(NativeGallery 설치 시).
4. 폰을 USB 로 연결하고 Build Profiles 의 **Run Device** 에서 선택(안 보이면 Refresh).
5. **Build And Run**. 저장 경로는 영문 폴더(예 `Builds/`). 첫 빌드는 Gradle 때문에 5~10분 걸린다.
6. 설치 후 카메라 권한 허용 → 홈 화면(디자인 UI) → 필요하면 "서버 설정"에서 주소 저장.

APK 만 만들려면 **Build** 로 `.apk` 를 저장해 `adb install -r 파일.apk` 로 설치한다.

### 빌드·실행 문제 해결

| 증상 | 원인 | 조치 |
|---|---|---|
| `The name 'NativeGallery' does not exist` | 심볼은 있는데 플러그인 없음 | NativeGallery 설치, 또는 심볼 `BESIDE_NATIVEGALLERY` 제거 |
| 폰에서 모델이 분홍색 | 셰이더가 빌드에서 제외 | 로더가 URP/Lit 로 교체하므로 보통 안 생김. 생기면 `ModelLoader` 의 Base Material Template(`Dog_Mat`) 연결 확인 |
| 생성 요청 시 `NETWORK_ERROR` | 서버 미실행, adb reverse 풀림, Wi-Fi 다름, 터널 꺼짐/주소 바뀜, HTTP 차단 | 서버 설정의 "연결 확인", healthz, Allow downloads over HTTP 확인 |
| 받은 모델 대신 기존 모델이 배치됨 | 로드 실패로 스포너 0번 칸이 교체되지 않음 | Logcat 에서 `RuntimeModelLoader` 오류 확인 |
| 모델이 옆이나 뒤를 봄 | GLB 정면 축과 `Model Facing` 불일치 | 리깅 GLB 는 PlusZ, 예전 OBJ 기반은 MinusX. 뒤를 보면 반대 부호 |
| 모델이 여러 마리 생김 | `SpawnLimiter` 미부착 | `Beside` 에 추가, Console 의 `[SpawnLimiter] spawner=…` 로그 확인 |
| 매 프레임 `NullReferenceException … MaterialHelperBase` | `RuntimeShell` 에 Interaction Affordance 가 남음 | 프리팹에서 삭제 |
| 기본 UI 패널이 디자인 UI 위에 겹침 | 두 UI 컨트롤러가 모두 켜짐 | 하나만 체크 |
| Inspector 에 "Missing Script" | 스크립트를 지웠다 넣어 GUID 변경 | 위 씬 구성 표대로 재설정. 파일 교체는 덮어쓰기, 이동은 Unity Project 창 안에서 |
| Gradle 빌드 실패 | 경로에 한글, SDK/JDK 경로 | 영문 경로, `Preferences > External Tools` 에서 Unity 내장 SDK/JDK 사용 |
| 폰이 Run Device 에 없음 | 케이블·드라이버·USB 디버깅 | 데이터 케이블, 제조사 USB 드라이버, 폰의 디버깅 허용 팝업 |

폰 로그는 Package Manager 의 **Android Logcat** 설치 후 `Window > Analysis > Android Logcat` 에서 `BesideMetric`, `RuntimeModelLoader`, `JobManager`, `SpawnLimiter`, `PlaneScanGate` 로 검색한다.

## 테스트 시나리오

| 시나리오 | 방법 | 기대 결과 |
|---|---|---|
| 정상 흐름 | 사진 선택 → 생성 → 미리보기 → "AR로 배치하기" → 바닥 터치 | 서버 모델 배치, 30초 뒤 `metrics.csv` 한 줄 추가 |
| 바닥 인식 시점 | 홈·미리보기에서 폰을 움직인 뒤 "AR로 배치하기" | 이전 화면에서는 점이 생기지 않고, AR 화면에서 비춘 바닥부터 인식 |
| 배치 제한 | 바닥을 여러 번 터치 | 1마리만 유지, 추가 터치 무시 |
| 벽 차단 | 벽·천장 터치 / 가운데 놓기 | 생성 안 됨 |
| 바닥 점 숨김 | 배치 → "다시 배치" | 배치 후 점 사라짐, 지우면 다시 표시 |
| 애니메이션 | 리깅 GLB 배치 → 걷기/앉기/꼬리/갸웃/기본 | 동작 전환, 강조가 실제 동작을 따라감, 다시 배치하면 "기본" |
| 서버 변경 | 서버 설정 → 터널 주소 → 연결 확인 → 저장 | "연결됨 · mock/mock", 모바일 데이터로 전체 흐름 |
| 실패 + 재시도 | 파일명에 `fail` 이 든 사진(기본 UI 의 "실패 테스트") → "다시 시도" | 실패 화면 → `/retry` → 다시 폴링 → 완료 |
| 복원 | 생성 중 앱 강제 종료 → 재실행 | 저장된 jobId 로 폴링 재개 |
| 캐시 | 완료된 작업 상태에서 앱 재실행 | 재다운로드 없이 로드(로그 `cache hit`) |
| 서버 종료 | 서버 끄고 요청 | `NETWORK_ERROR` 문구 + 다시 시도 |
| AR 안내 | 카메라 권한 거부 / AR 화면에서 12초 이상 바닥 미인식 | 안내, 권한은 "설정 열기" |

## 리깅 GLB (SMAL 기반)

AnimalLift(SMAL 계열 canonical 메시) 출력에 SMAL 뼈대·웨이트를 입힌 GLB. 현재 샘플은 `backend/storage/results/sample-dog.glb` 로 Mock 서버가 내려준다.

| 항목 | 값 |
|---|---|
| 뼈대 | 36개 (Root, Pelvis, Spine1~5, Chest, 네 다리 각 4, Neck, Head, Jaw, Tail1~7, Ear_L/R) |
| 클립 | `Idle`(4 s, 반복) · `Walk`(1 s, 반복, 제자리) · `TailWag`(0.5 s, 반복) · `HeadTilt`(3 s, 1회 후 Idle) · `Sit`(3 s, 1회 후 Idle) |
| 정점당 뼈 | 최대 4 (Unity 기본 Skin Weights 와 호환) |
| 좌표 | 머리 +Z, 발바닥 y = 0, 최장 축 1 m (GLB_SPEC 정규화) → 앱 Model Facing PlusZ, LongestAxis 0.6 |
| 텍스처 | 1장(`pet_body`), 기존 OBJ 와 같은 UV 라 털 지도 공용 |

- **SMAL 은 학술용 라이선스**라 리깅 GLB 도 파생물로 취급해 공개 레포에 커밋하지 않는다. `sample-dog.glb` 는 이미 추적 중인 파일이므로 `.gitignore` 만으로는 부족하다: 로컬은 `git update-index --assume-unchanged backend/storage/results/sample-dog.glb`, 레포에서 빼려면 백엔드 담당과 `git rm --cached` 후 `.gitignore`.
- 서버는 아직 털 지도를 내려주지 않는다. 리깅 모델 털은 `ModelLoader` 의 Test Glb Url + Test Hair Length/Flow Url(로컬 파일)로만 확인할 수 있다(먼저 앱에서 "처음부터"로 저장된 작업을 지운다).
- 다음 단계: ① 리깅 모델 털 확인 ② Mock 서버 설정·.gitignore 정리 ③ 바닥 위 걷기(이동 + Walk) ④ GLB_SPEC 에 rigged 추가(노드·클립 이름, 정점당 뼈 4개 이하, 털 지도 전달) ⑤ 추론 서비스에 "추론 → 리깅" 연결 ⑥ 실제 사진 E2E + 측정(리깅·털 켜고 끈 FPS 비교) ⑦ 사진·영상 자세 따라하기(확장).

## 폰 ↔ GPU PC real 서버 (실제 모델 E2E, PLAN 3단계 10-20 주 선행)

real 프로파일은 Spring 과 Python 추론 서비스가 **같은 PC(팀원 GPU PC)** 에서 돈다(결정 2026-10-05). 그 PC 에서 `.\scripts\run_real.ps1` 로 두 서버를 띄운다([backend/README.md](../backend/README.md) 'GPU PC 에 real 배치'). 앱 쪽에서 Mock 때와 달라지는 것:

1. `JobManager.baseUrl` = `http://<GPU PC IP>:8080`. 폰 브라우저에서 `/api/v1/healthz` 가 `"profile":"real","workerType":"real"` 을 보이면 연결된 것이다. 방화벽 8080 허용은 Mock 과 같다.
2. 수 분이 걸린다. `maxWaitSeconds` 를 600 으로 올린다(REVIEW_CHECKLIST #7 제안값). 앱의 대기는 서버 큐 대기(PENDING)도 포함한다: 서버는 `/infer` 를 한 번에 1건만 부르므로 두 사람이 동시에 올리면 뒤 작업은 앞 작업이 끝날 때까지 PENDING 이다.
3. `progress` 는 PROCESSING 동안 `null` 이다(추론 서비스가 진행률을 주지 않는다). 퍼센트 대신 `timings.queuedMs`/`processingMs` 로 경과 시간을 보여 준다.
4. 패스스루(`-SampleGlb`)로 띄운 서버는 어떤 사진을 올려도 같은 모델을 돌려준다 — 연동 확인용이지 결과가 아니다. 실제 모델이 돌 때만 `metrics.csv` 를 측정값으로 기록한다.

## API 사용 규칙 (v1)

- 업로드: `UnityWebRequest.Post(url, List<IMultipartFormSection>)` + `MultipartFormFileSection("photos", bytes, fileName, mime)`. 서버는 **파트의 Content-Type** 으로 jpeg/png/webp 를 검사한다. 1~10장, 장당 ≤5MB, 전체 ≤20MB — `PhotoPicker` 가 갤러리 사진을 긴 변 1600px JPEG 로 재인코딩해 제한을 맞춘다.
- 202 응답의 `JobCreatedResponse.jobId` 를 쓴다(없으면 `Location` 헤더).
- 폴링 2초 간격(`pollingIntervalSeconds`), 최대 대기 600초(`maxWaitSeconds`).
- `COMPLETED` → `asset.url` 을 `baseUrl` 과 결합해 `DownloadHandlerFile` 로 `{jobId}-base.glb` 저장 → 매직 바이트 검증 → glTFast 로드. 같은 jobId 는 다시 받지 않는다.
- `FAILED` → `ErrorMessages.ForCode(error.code)` 문구, 재시도 가능하면 `POST /retry`.
- 모든 오류 응답은 `ErrorResponse` 봉투. 알 수 없는 `code`·`status` 는 일반 문구/`Unknown`.
- 배치 규칙은 [GLB_SPEC.md](../docs/asset/GLB_SPEC.md) §7: 생성기가 최장 축 1 m·머리 +Z·발바닥 원점으로 정규화하고, 앱은 최장 축 0.6 m 로 표시한다(`RuntimeModelLoader` 기본값). 털 지도 PNG 방식과 `variant=hair` 방식 중 어느 쪽으로 갈지는 계약 회의에서 정한다.

## metrics.csv

- 위치: `Application.persistentDataPath/metrics/metrics.csv` — Android 에서는 `/storage/emulated/0/Android/data/<패키지명>/files/metrics/metrics.csv`.
- 헤더(순서 고정, [METRICS.md](../docs/METRICS.md)): `jobId,uploadMs,waitMs,downloadMs,loadMs,e2eMs,avgFps,minFps,memMB,device`
- `e2eMs` 는 업로드 시작부터 **첫 AR 배치(ObjectSpawner.objectSpawned)** 까지(미리보기·바닥 찾는 시간 포함). 배치 후 30초 FPS 를 샘플링한 뒤 한 줄을 쓴다. 에디터 FPS 는 기기와 다르므로 측정표는 기기 값으로 만든다.
- 회수: `adb pull /sdcard/Android/data/<패키지명>/files/metrics/metrics.csv .`
- 구간별 로그는 Logcat 에서 `BesideMetric` 으로 검색(JSON 한 줄, `stage`: upload / status_wait / download / model_load / ready / first_ar_display).
- 비교 실험 시 열을 늘리지 말고 조건(Wi-Fi / 터널, 리깅·털 on/off)을 jobId 별로 따로 기록한다.

## 직접 할 일

- 리깅 모델 털 확인, 바닥 위 걷기 (위 "리깅 GLB" 다음 단계).
- 실패 시나리오·기기 `metrics.csv` 회수 검증 (PLAN 3·4단계). Wi-Fi 와 터널의 업로드·다운로드 시간 비교.
- 디자인 UI 마감: Noto Sans KR TTF 넣고 `Font` 연결, 홈 Hero Image 준비.
- 계약 회의: GLB_SPEC rigged, 털 지도 전달 방식, 서버 인증(터널 공개 대비), `sample-dog.glb` 레포 처리.
- 크래시 수집(Sentry 또는 Firebase Crashlytics) 연결, 측정값 서버 전송 방식.
