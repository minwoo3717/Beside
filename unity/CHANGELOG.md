# 변경 기록 (Unity 트랙)

Unity 앱의 변경을 날짜순으로 적는다. 2026-10-29~11-17 작업은 연구용 프로젝트(Beside_Reserch)에서 하고 11-17에 이 폴더로 옮겼다. 앱에 영향을 주는 서버 쪽 변경은 "서버"로 표시했다. 그 전 내용은 git 기록을 본다.

## 2026-11-27 (관측 보강 트랙 D — 추적 헤더·문의 번호·불러오기 이벤트)

멘토링 '로깅·트레이싱·모니터링' 보강 계획의 트랙 D. 계약은 openapi v1.3 후보(C0, `609e84c`): 요청 헤더 `traceparent`, 이벤트 `saved_dog_open`·`saved_dog_change`·`app_error`, 화면 `library`·`camera`, `model_placed` 새 정의(모든 배치 + `again`·`source`).

- 추적 헤더(`BesideApiClient`): 업로드·GLB 내려받기·`/retry`·`/cancel` 에 W3C `traceparent: 00-<X-Request-Id>-<16 hex>-01`(`BesideApiClient.TraceParent`). X-Request-Id 가 32자 소문자 hex(전부 0 아님)일 때만 붙고, span id 는 요청마다 새 난수(전부 0 아님). 상태 조회(폴링)·이벤트·healthz 는 지금처럼 `X-Request-Id` 만 — 30분 대기면 trace 하나에 폴링 span 이 수백 개가 되므로. 서버가 이 trace 에 이어 붙이면 앱 흐름 id = 서버 trace id
- 흐름 id 하나(`JobManager`): `POST /retry` 가 만든 id 를 이어지는 `Flow`(폴링·GLB 내려받기)가 그대로 쓴다(전에는 retry 뒤 새 id). 서버 취소(`/cancel`)는 멈추는 흐름의 id. `Flow` 는 자기 id 를 지역 변수로 들고 있어 취소 뒤 새 흐름이 시작돼도 섞이지 않는다(`first_ar_display` 줄도 그 작업을 만든 흐름의 id). `CurrentRequestId` 공개 — 앱 안에서 거절한 업로드(사진 없음·개수·크기 초과)는 요청을 보내지 않았으므로 null
- 문의 번호(`DesignedUIController.OnJobFailed`): 오류 화면 제목 아래 "작업 번호 xxxxxxxx · 문의 번호 yyyyyyyy"(흐름 id 앞 8자. 업로드 실패는 "사진을 보내지 못했어요 · 문의 번호 …"). Grafana 'Beside 로그' 의 ID 찾기에 이 8자를 넣어 Caddy·backend 줄을 찾는다(B3·E5)
- 이벤트(`ProductEvents.TrackJob` 한 줄, props 는 openapi·METRICS §6.1 값만 — 강아지 이름·메모 같은 사용자 글은 넣지 않음):
  - `saved_dog_open`: 저장한 강아지 불러오기. 성공은 미리보기로 넘어가기 **전에** 보내 `screen=library`, 파일 읽기·GLB 열기 실패는 `ok=false`. `from`(home|ar, 목록을 연 화면), `count`(저장 수), `ageDays`(저장 뒤 지난 날), `loadMs`
  - `saved_dog_change`: `saved`(만든 강아지 자동 저장), `replaced`(꽉 찬 상태에서 하나 지우고 새 강아지 저장 — 이 경로의 `deleted` 는 보내지 않음), `edited`(정보 수정), `deleted`(목록에서 삭제), `skipped`(꽉 찼을 때 '저장하지 않고 계속'·뒤로). `count` 는 바뀐 뒤 수
  - `model_placed`: `JobManager.ModelPlaced`(작업의 첫 배치만) 대신 spawner `objectSpawned` 를 직접 받아 **모든 배치**를 보낸다. 이름 `Runtime_job_<jobId>`·`Runtime_lib_<id>`(`(Clone)` 뗌)에서 `source=job|library` 와 id, 시험용 GLB 주소로 띄운 모델(`Runtime_<file>`)은 `source` 없음. 같은 모델의 두 번째 배치부터 `again=true`(세션 안). `method`·`secSinceArEnter` 규칙 그대로. UI 의 `OnModelPlaced`·`metrics.csv` 의 `e2eMs` 는 그대로(`JobManager.ModelPlaced`)
  - `photos_selected`: 에디터 시험 사진(`PhotoPicker.editorTestImagePaths`)으로 고른 것도 `source=gallery` 로 보낸다(전에는 갤러리 플러그인이 있을 때만이라 에디터 퍼널이 home 에서 멈췄다)
  - 화면 `library`·`camera`: `Show()` 가 이미 `screen_view` 를 보낸다. 서버 목록은 계약(C0)과 서버 코드(A3 `0ab57bc`)에 들어갔다
- 웹 재현 `docs/ui-preview`: 오류 화면에 문의 번호 한 줄. PR #39 의 저장한 강아지·카메라 화면은 아직 없다 [계획]
- 앱 오류 `app_error`(D3, 새 `Metrics/AppErrorReporter.cs` — `.meta` 는 Unity 가 만듦): 씬을 고치지 않고 `[RuntimeInitializeOnLoadMethod(BeforeSceneLoad)]` 로 스스로 생긴다(`BesideAppErrorReporter`, DontDestroyOnLoad). `Application.logMessageReceivedThreaded` 의 Exception·Error·Assert 만 받고, `[BesideMetric]`·`[BesideEvent]` 로 시작하는 줄은 뺀다(작업 실패는 `job_failed_shown` 이 센다). 콜백은 어느 스레드에서나 불리므로 계산·큐 넣기만 하고 try/catch 로 감싸며 `Debug.*` 를 부르지 않는다(로그가 다시 콜백으로 돌아온다). 메인 스레드 `Update` 가 `ProductEvents.TrackJob("app_error", 지금 작업 id 또는 없음, kind, sig)` 와 logcat 한 줄 `[BesideEvent] app_error sig=… kind=… type=… frame=…` 을 낸다. **메시지 원문은 서버로 보내지 않는다**
  - `sig` = FNV-1a 32(UTF-8) & 0x7fffffff. 예외는 `예외 종류|첫 스택 줄`, 그 밖은 `메시지 첫 줄에서 따옴표 안·hex 덩어리(0x…, 숫자가 섞인 6자 이상)·숫자를 지운 것|첫 스택 줄`(IL2CPP 릴리스는 Error 스택이 비어 모든 오류가 같은 sig 가 되는 것을 막는다). 첫 스택 줄은 `UnityEngine.Debug`·`Logger`·`Assertions` 자기 줄을 건너뛴 호출 위치이고, ` (at …)`·` [0x…]`·` in <…>`(파일·IL 오프셋·빌드 id — 빌드마다 바뀜) 앞에서 자른다. hex 덩어리는 앞뒤가 영문자·숫자가 아닐 때 지운다(`\b` 를 쓰지 않음: `_` 가 단어 글자라 `Runtime_job_<id>` 의 id 가 남아 작업마다 sig 가 갈리던 것을 고침 — .NET 정규식으로 두 작업 id 가 같은 글로 줄어드는 것을 확인)
  - 실행(세션)당 20건, 같은 sig 는 3건까지. 끄기는 `BESIDE_NO_PRODUCT_EVENTS`(설치하지 않음). `Application.quitting`·파괴 때 구독을 풀고, 도메인 리로드 없는 Play 를 위해 static 을 `SubsystemRegistration` 에서 비운다
  - RUNBOOK(E5 가 반영할 글): `/admin` 이벤트 CSV·Grafana '앱 이벤트' 에서 `app_error` 의 `sig` 를 보고, 팀 기기라면 `adb logcat -s Unity | findstr /C:"app_error sig=<sig>"` 로 `type`·`frame` 을 찾는다. 원래 오류 줄(메시지·스택)은 logcat 에서 그 줄보다 앞(보통 바로 앞)에 있다. 팀 밖 기기는 logcat 이 없으므로 sig 로 같은 오류끼리 묶어 세기만 한다
- 확인: Unity 6000.6.0f1 batchmode(`-batchmode -nographics -quit`)로 Assembly-CSharp 컴파일 `error CS` 0(D2 나머지를 넣은 뒤, D3 `AppErrorReporter.cs` 를 넣은 뒤, 정규식을 고친 뒤 다시 컴파일 — 매번 Assembly-CSharp 재빌드). `Assets/Beside` 경고는 모두 기존 `CS0618`(`FindFirstObjectByType` 폐기 예정)이고 바꾼 줄·새 파일에서 새로 난 경고는 없다. batchmode 가 `ProjectSettings` 3개 파일의 줄 끝만 LF 로 다시 썼다(내용 diff 없음, 커밋하지 않음). 에디터 Play(XR Simulation + 로컬 mock)·기기(SM-S947N)에서 헤더·이벤트(`app_error` 포함)가 실제로 가는지는 [계획](E2, 사람)

## 2026-11-27 (기기 확인 피드백)

기기(SM-S947N)에서 확인하며 고친 것. 작업은 브랜치 fix/unity-metrics-one-row 를 복사한 로컬 프로젝트에서 하고 이 브랜치로 옮겼다. 옮긴 코드는 컴파일(오류 0, FurLab StructLayout 오탐 제외)만 다시 확인했다.

- 미리보기(`ModelPreview`): 화면 크기에 맞춘 렌더 텍스처(최대 1280), FOV 기준 화면 85% 채우기, 쉘 털 전체 겹, 그림자 끔(`showShadow` 기본 false). 털 자글거림 완화
- 쉘 털(`ShellFur.shader`): 털 패스 `ColorMask RGB`(알파가 미리보기 합성에 새던 문제), 광택 `_SpecTint`·`_SheenPower`·`_SheenScale` 로 회색 번들거림 줄임. TANGENT 가 없는 GLB(glTFast 는 노멀맵이 없으면 TANGENT 를 만들지 않음)에서 엉터리 TANGENT 로 빗질하던 문제: `_UseTangents`(로더가 메시에 TANGENT 가 있을 때만 1) + TANGENT 없을 때 `_NoTangentComb`·`_NoTangentCombDir` 로 아래·뒤쪽으로 살짝 눕힘. 눈·코 털 다시 안 남, 털이 수직으로 서던 것 완화
- 털 길이 이음매(`RuntimeModelLoader.WeldSeamFurLength`): UV 이음매의 같은 위치 정점들이 털 길이 맵 값을 평균해 uv2 에 넣는다(머리·등 척추선의 털 빠진 줄)
- AR 안내 문구는 모델마다 한 번(× 를 누르면 같은 모델에서는 다시 안 뜸, 새 모델이면 다시 뜸). `reshowOnNewMessage` 0
- 홈·생성 중 화면이 손가락을 따라 위아래로 움직이던 문제: 스크롤 `Clamped` + `DesignKit.ScrollWhenOverflow`(내용이 넘칠 때만 스크롤)
- 그림자 계단 현상: URP-Performant 메인 라이트 그림자 2048, Soft Shadow High
- 저장한 강아지(새 `Library/ModelLibrary.cs`): 만든 강아지를 최대 5마리까지 폰에 자동 저장(`persistentDataPath/library`: index.json, GLB 복사본, 사진). 이름(20자)·키(5~200cm, 비우면 자동)·메모(60자) 편집, 키를 바꾸면 모델 크기에 반영. 꽉 차면 지울 강아지를 고르거나 저장 건너뛰기. 홈 카드 "저장한 강아지", AR 의 "사진 찍기" 타일을 "불러오기"로 바꿈 — **`photo_saved` 이벤트는 더 이상 나가지 않는다**
- "지금 촬영하기": 바로 찍던 것을 카메라 화면(안내 문구 + 셔터 버튼)으로 바꿈. 찍는 동안 AR 에 놓인 강아지는 숨김. 카메라로 찍은 사진의 확인 화면 버튼은 "다시 찍기"
- 아이콘·예시 이미지 `.meta` 17개를 Sprite 로(홈 버튼 아이콘이 안 보이던 문제). 씬의 `FurBenchmark`·`FpsOverlay` 끔
- 알아둘 것: 서버 `ProductEventCatalog.SCREENS` 에 `library`·`camera` 가 없어 이 두 화면의 `screen_view` 는 서버에서 버려진다 → 계약에 추가함(openapi `609e84c`, 서버 코드는 트랙 A3 — 위 '관측 보강' 항목)

## 2026-11-26 (제품 이벤트, 안건 #24)

- `Metrics/ProductEvents.cs`(새 파일, `.meta` GUID 새로 매김): 화면 보기·버튼 누름을 `POST /api/v1/events`(서버 "다음 태그 전 추가", [docs/METRICS.md](../docs/METRICS.md) §6)로 보낸다. `installId` = PlayerPrefs `beside.installId` 의 난수 GUID(기기 ID 아님), `sessionId` = 실행마다 새 GUID, `appVersion` = `Application.version`(`[0-9A-Za-z._+-]` 32자로 다듬음), platform `android`(에디터 포함). 메모리 큐 + `persistentDataPath/metrics/events.jsonl`(오프라인 보관, 최대 500줄), 30초마다·20개가 쌓이면·`OnApplicationPause(true)` 에 보냄. 2xx·4xx 는 지우고, 연결 오류·5xx 는 남겨 5·10·20초 뒤 다시, 그 뒤는 30초마다. 서버 주소는 `JobManager.BaseUrl`, 요청은 `BesideApiClient.PostEvents`(`X-Request-Id` 같은 헤더). 로그 `[BesideEvent]`
- 씬은 고치지 않았다: 씬 YAML 에 컴포넌트를 손으로 넣으면 fileID·GUID 를 맞춰야 해서, 씬에 없으면 `[RuntimeInitializeOnLoadMethod]` 로 스스로 생긴다(`BesideProductEvents`, DontDestroyOnLoad). 끄는 법: 씬에 `ProductEvents` 를 두고 Inspector `Send Events` 끄기, 또는 Scripting Define Symbol `BESIDE_NO_PRODUCT_EVENTS`
- 이벤트 12개: 디자인 UI 의 한 줄 호출 — `Show(Page)` → `screen_view`(같은 화면 연속은 한 번), 갤러리·화면 촬영 → `photos_selected`, "이 사진으로 만들기"·이어보기 카드·"다시 시도" → `generate_tap`(new·resume·retry), 진행 화면 "취소" → `job_cancel_tap`(단계·경과 초), 털 표현 → `fur_toggle`, "사진 찍기" → `photo_saved`, "다른 사진으로 다시 만들기"·"새로 만들기"·"다른 사진 고르기" → `start_over`, "화면 가운데에 바로 놓기" → 다음 배치가 `method=center`. `ProductEvents` 가 직접 구독: `JobManager.JobFailed`→`job_failed_shown`, `ModelReady`→`model_ready`(캐시·loadMs·waitMs — `JobManager.LastReady` 추가), `ModelPlaced`→`model_placed`, `ARGuidance.GuidanceChanged`→`ar_issue_shown`, `PetAnimationPlayer.ClipStarted`→`animation_play`. 기본 UI(`MainUIController`)는 화면·버튼 이벤트를 보내지 않는다(구독한 다섯은 보냄)
- `ApiV1Routes.Events`·`MaxEventsPerBatch`, DTO `ProductEventBatch`·`ProductEvent`·`ProductEventsAccepted`(Newtonsoft 전용이라 앞의 둘은 `[Serializable]` 없음 — 붙이면 props 사전 때문에 분석기 경고 UAC1015)
- 확인: Unity 6000.6.0f1 의 Roslyn 으로 Assembly-CSharp 컴파일 오류 0, 경고 39 — 바꾸기 전(50efc57)과 같은 경고(줄 번호만 다름). `UNITY_ANDROID` + `BESIDE_NATIVEGALLERY`(에디터 기호 뺌)와 `BESIDE_NO_PRODUCT_EVENTS` 로도 오류 0·경고 39. 에디터 Play·기기에서 실제로 보내 보기는 [계획]

## 2026-11-25 (코드 리뷰 고침)

- 취소가 서버에도 간다: `JobManager.CancelByUser(forget)` 가 앱 흐름을 멈추고, 작업이 서버 대기열에 있으면(Waiting, PROCESSING 아님) `POST /api/v1/jobs/{id}/cancel`(API v1.2)을 보낸다. 업로드·`/retry` 요청이 가는 중(Uploading)에 누르면 요청은 이미 떠났으므로 흐름이 화면 밖에서 끝까지 기다렸다가 서버가 받은 작업을 취소한다(`cancelGen`). 디자인 UI 진행 화면 '취소'(저장된 작업도 잊음)와 기본 UI '취소'(작업은 남김, 단 서버에서 취소된 작업은 잊음 — 남기면 다음 실행에 취소 화면만 다시 뜬다)가 이것을 쓴다. 응답은 로그만(409 `JOB_NOT_PENDING` = 이미 생성 시작이라 작업을 남김). 전에는 앱만 멈춰 버린 작업이 GPU 를 썼다. `BesideApiClient.CancelJob`·`ApiV1Routes.Cancel` 추가
- `ErrorMessages`: `CANCELED`(다시 시도 가능, `/retry` 로 처음부터)와 `JOB_NOT_PENDING` 문구(ERROR_CODES.md 그대로). 웹 보관함에서 취소한 작업을 앱이 열면 일반 오류 문구가 나왔다
- 서버 주소 저장: 주소가 바뀔 때만 작업 사진을 지움(같은 주소로 저장하면 남은 작업의 사진만 지워졌다). `JobManager.ClearSavedJob` 은 작업을 먼저 잊고 `Idle` 을 알려, 홈에 지운 작업의 이어보기 카드가 남지 않는다. 저장 뒤 설정 화면에 머물러 '저장했어요' 가 보인다(웹 재현 `app.js` 도 실제로 쓰는 주소 — 빈 칸은 기본 주소 — 가 바뀔 때만 저장된 작업을 지움)
- 홈 서버 상태: 주소를 바꾸면 옛 주소로 보낸 healthz 응답을 버린다(세대 번호 `healthGen`). 늦게 온 옛 '연결 안 됨' 이 새 주소에 남던 문제
- 웹 재현 `serve.py`: `/res/` 경로가 `BesideUI` 폴더 안인지 `commonpath` 로 확인(문자열 앞부분 비교라 `../BesideUI.meta` 가 열렸다)
- 확인: Unity 6000.6.0f1 의 Roslyn 으로 Assembly-CSharp 컴파일 오류 0, 경고 39(2026-11-21 기준과 같은 수). 에디터 Play·기기 확인은 [계획]

## 2026-11-23

- `metrics.csv` 작업당 한 줄(METRICS §3, 코드 리뷰 보고 남은 1건): 이미 적힌 jobId 는 다시 쓰지 않고(재개·다시 시도·캐시 실행) 30초 FPS 측정도 건너뜀. 같은 세션에서 올린 작업을 이어 받으면 그때 잰 uploadMs 를 씀

## 2026-11-22

- 디자인 UI를 시안 E("Beside 앱 UI 개선 시안")로 바꿈: 홈·사진 확인·진행·미리보기·AR(찾는 중/배치 전/배치 후)·오류·서버 설정 7개 화면. `DesignKit` 토큰·위젯 교체, `DesignedUIController` 다시 작성(같은 GUID, 같은 이벤트·공개 메서드만 사용)
- 홈: 이어보기 카드("만들던 모델이 있어요", 작업 첫 사진 썸네일, 완료 여부 표시)와 좋은 사진 예시 3장(○○✕). 서버 설정은 홈 아래 줄(연결 상태 점, 30초마다 healthz)
- `JobManager.resumeOnStart`(기본 켬)·`ResumeOnStart`·`SavedJobId` 추가. 디자인 UI가 켜져 있으면 시작 때 자동 복원 대신 홈 카드에서 이어 감. 기본 UI 동작은 그대로
- `ModelPreview.SetBackground` 추가: 미리보기 3D 배경을 흰 카드색으로
- 진행 단계 표시: `DesignKit.Spin`을 `DesignKit.Spinner`로 바꿈 — 시안대로 4분의 1 호가 0.9초에 한 바퀴 일정하게 돌고, 끝은 둥글게(점 두 개). 각도를 시계에서 계산해 다음 단계로 넘어가도 이어서 돈다. 호가 늘었다 줄어드는 안드로이드식은 멈칫거려 보여서 쓰지 않음(웹 재현 `.spin`과 같은 움직임)
- 리소스: Noto Sans KR Regular/Bold(OFL, 가변 글꼴에서 400·700 굵기로 뽑고 한글·영문·기호만 남긴 부분 글꼴, 각 2.4MB), 흰색 선 아이콘 14개(128px), 예시 사진 3장(512px) — `Assets/Beside/UI/Resources/BesideUI/`
- 확인: Unity 2021.3 참조 DLL + uGUI 2018.3 + 외부 패키지 대체 스텁으로 Assets/Beside 전체 컴파일 — 바꾼 코드 오류 0, 남은 8개는 그 참조에 없는 Unity 6 API(기존 코드 포함). Unity 6 편집기 컴파일·에디터 Play·기기 확인은 [계획]

## 2026-11-21 (main 병합)

- 브랜치 `Unity`(4e6f190·2d2e235·0811aae)를 main 에 옮김(`feat/unity-branch-port`). Unity 프로젝트는 `unity/` 그대로 두고(브랜치의 `unity/BesideApp/` 이동은 따르지 않음) 쉘 털 v2·FurLab·그림자 바닥면·FpsOverlay·씬·문서·측정 기록을 그 경로로 합침. main 이 그 뒤 고친 앱 코드(QUEUE_FULL, 다시 시도, 모델 해제 등 6개 파일)는 main 것을 유지하고, `RuntimeModelLoader` 에는 그림자 바닥면만 더함
- 털 맵 형식은 main 의 GLB_SPEC §4.1(`extras.beside`, 변환기 `convert_asset.py`) 그대로. 브랜치 서버의 TANGENT 추가·결 맵 G 부호 변경은 계약 결정 전이라 옮기지 않음 → main 서버 GLB 에는 TANGENT 가 없어 쉘 털 v2 는 털 결(빗질)을 끄고 법선 방향으로 그림
- `ShellFurRenderer.OnDestroy` 가 `FurPose.Release` 를 불러 지워진 모델의 굽기 메시를 바로 해제
- `UniversalRenderPipelineGlobalSettings.asset`: Unity 6000.6.0f1 편집기가 비운 런타임 목록 커밋
- 포트 리뷰 뒤 고침: AR 배치 전 미리보기의 RenderTexture 에 URP 에셋의 MSAA 수를 줌(`ModelPreview` — 없으면 alpha-to-coverage 인 쉘 털 v2 가 미리보기에서만 듬성해진다), 결과 화면 삼각형 수에서 그림자 바닥면 Quad 를 빼고 리깅 메시(`SkinnedMeshRenderer`)도 셈(`DesignedUIController.FillStats`), 포트가 더한 경고 5개(FurLab 의 `FindFirstObjectByType` → `FindAnyObjectByType`, 쓰이지 않는 `ShellFurRenderer.bakedFrame` 삭제)
- 확인: Unity 6000.6.0f1 의 Roslyn 으로 Assembly-CSharp 컴파일 오류 0, 경고 39(같은 방법으로 컴파일한 main 과 같은 경고, 줄 번호만 다름). 에디터 Play·기기 확인은 [계획]

## 2026-11-17

- 문서: `unity/docs/FUR_RENDERING.md`, `unity/experiments/`(털 성능 측정과 원본 데이터), `unity/CHANGELOG.md` 추가. `unity/README.md`·`CLAUDE.md`에 쉘 털 v2, 그림자 바닥면, FurLab, Graphics API·MSAA 설정 반영
- 연구용 프로젝트(Beside_Reserch)의 `Assets`·`Packages`·`ProjectSettings`를 브랜치 `Unity` 에 반영(그 브랜치는 프로젝트 루트를 `unity/BesideApp/` 로 옮겼으나, 2026-11-21 main 병합 때는 `unity/` 를 그대로 두었다)
- `FurLab/README.md`: Graphics API 안내를 바로잡음(Vulkan만 → **OpenGLES3 먼저**), 주 지표를 msPerPet으로, 부하 반복 상한 128
- ProjectSettings: Graphics API OpenGLES3 + Vulkan, Frame Timing Stats 켬, QualitySettings 안티에일리어싱 4x

## 2026-11-05

- 실험(generation/experiments): AnimalLift 방해 요소(서로 다른 개 6장, 같은 개 10상황, 서버 전처리 3조건), 같은 개 일관성(5마리 × 10상황 × 3조건)

## 2026-11-04

- 실험(generation/experiments): 3D 생성 모델 비교(AnimalLift / SF3D / TripoSR, RTX 4060 Ti)

## 2026-11-03

- 털 성능 최종 측정(SM-S947N, 가까이·멀리 × 방식 비교·최적화 비교). 결과 문서, 그래프, 털 방식 그림
- `analyze_fur_bench.py`: 같은 라운드 비율 표, 측정 방법 설명 섹션, GPU 한계 미도달 표시(※)

## 2026-11-02

- FurBenchmark 재측정 대응(1~3차 측정에서 드러난 문제)
  - 측정 중 AR 30 FPS 고정 해제, 끝나면 복원
  - 부하 반복(K/2K) 기울기 방식 `msPerPet`, 조건별 K 자동 보정(상한 128)
  - 조건별 MSAA 적용, 발열 대기(45→43°C, 최대 90초), 측정 중 화면 꺼짐 방지
  - 조건별 스크린샷 갤러리 저장, 결과 공유(텍스트·NativeShare)
  - 최적화 묶음 조건이 0개로 나오던 문제(직렬화된 예전 목록): 기본 조건 자동 보충, "Reset conditions to defaults" 메뉴
- 쉘 v1 비교용 셰이더 `ShellFurV1.shader` 추가
- Graphics API를 Vulkan만 두었을 때 AR 카메라가 검게 나오는 문제 → OpenGLES3를 첫 번째로 되돌림

## 2026-11-01

- **쉘 털 v2** (`ShellFur.shader`, `ShellFurRenderer.cs`, `FurNoise.cs`)
  - 털이 결 맵 방향으로 누움, 밉맵 노이즈 텍스처, Kajiya-Kay 하이라이트, 정점 단위 조명, 바깥 겹부터 그리기, alpha-to-coverage
  - 쉘 수 자동 조절(화면 20%→60%에서 20겹→10겹): 가까이서 FPS가 절반으로 떨어지던 문제 대응
- 리깅 모델에서 털이 0.36배 크기로 다리 사이에 그려지던 문제 → `FurPose.DrawMatrix` + `_FurUnitScale`
- 털 측정 도구 **FurLab**: 가닥(`StrandFur`), 핀(`FinFur`), 공용 자세 굽기(`FurPose`), 조건 정의(`FurVariants`), 측정(`FurBenchmark`), 부하 반복(`PetLoadRepeater`), FPS 표시(`FpsOverlay`)
- GLB에 들어 있는 털 길이 맵·결 맵 읽기(`RuntimeModelLoader.TryReadEmbeddedHair`): 코드는 2026-10-05(f8cc1f2)부터 있었고 main 에는 PR #1(e0e1c2b)로 들어감. 이날부터 서버(아래)가 그 형식을 채움
- 서버(브랜치 `Unity`): AnimalLift `hair.npz`에서 털 길이·결 맵을 만들어 GLB `extras.beside`에 넣기, TANGENT 추가, 코·발바닥 털 제거, 텍스처 가장자리 채우기(`embed_hair.py`, `convert_asset.py`, `inference_service/app.py`). main 은 같은 `extras.beside` 형식을 `convert_asset.py` 의 `embed_hair` 로 넣는다(PR #25, 2026-11-21, GLB_SPEC §4.1). 2026-11-21 병합 때 코·발바닥 털 제거(`--hair-meta`)·텍스처 채우기(`--pad-texture`)는 기본 꺼진 선택 기능으로 옮겼고, TANGENT·결 맵 G 부호와 `embed_hair.py` 는 옮기지 않았다

## 2026-10-29 ~ 10-31 (날짜 대략)

- 연구용 프로젝트로 분리
- 그림자 바닥면(`ShadowCatcher.shader`): AR 바닥 점을 숨기면 그림자가 사라지던 문제 해결
