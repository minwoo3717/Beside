# 변경 기록 (Unity 트랙)

Unity 앱의 변경을 날짜순으로 적는다. 2026-10-29~11-17 작업은 연구용 프로젝트(Beside_Reserch)에서 하고 11-17에 이 폴더로 옮겼다. 앱에 영향을 주는 서버 쪽 변경은 "서버"로 표시했다. 그 전 내용은 git 기록을 본다.

## 2026-11-21 (main 병합)

- 브랜치 `Unity`(4e6f190·2d2e235·0811aae)를 main 에 옮김(`feat/unity-branch-port`). Unity 프로젝트는 `unity/` 그대로 두고(브랜치의 `unity/BesideApp/` 이동은 따르지 않음) 쉘 털 v2·FurLab·그림자 바닥면·FpsOverlay·씬·문서·측정 기록을 그 경로로 합침. main 이 그 뒤 고친 앱 코드(QUEUE_FULL, 다시 시도, 모델 해제 등 6개 파일)는 main 것을 유지하고, `RuntimeModelLoader` 에는 그림자 바닥면만 더함
- 털 맵 형식은 main 의 GLB_SPEC §4.1(`extras.beside`, 변환기 `convert_asset.py`) 그대로. 브랜치 서버의 TANGENT 추가·결 맵 G 부호 변경은 계약 결정 전이라 옮기지 않음 → main 서버 GLB 에는 TANGENT 가 없어 쉘 털 v2 는 털 결(빗질)을 끄고 법선 방향으로 그림
- `ShellFurRenderer.OnDestroy` 가 `FurPose.Release` 를 불러 지워진 모델의 굽기 메시를 바로 해제
- `UniversalRenderPipelineGlobalSettings.asset`: Unity 6000.6.0f1 편집기가 비운 런타임 목록 커밋
- 확인: Unity 6000.6.0f1 의 Roslyn 으로 Assembly-CSharp 컴파일 오류 0(경고 44, main 39 + FurLab 의 FindFirstObjectByType 4 + 쓰이지 않는 필드 1). 에디터 Play·기기 확인은 [계획]

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
