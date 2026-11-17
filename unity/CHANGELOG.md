# 변경 기록 (Unity 트랙)

Unity 앱의 변경을 날짜순으로 적는다. 2026-10-29~11-17 작업은 연구용 프로젝트(Beside_Reserch)에서 하고 11-17에 이 폴더로 옮겼다. 앱에 영향을 주는 서버 쪽 변경은 "서버"로 표시했다. 그 전 내용은 git 기록을 본다.

## 2026-11-17

- 문서: `unity/docs/FUR_RENDERING.md`, `unity/experiments/`(털 성능 측정과 원본 데이터), `unity/CHANGELOG.md` 추가. `unity/README.md`·`CLAUDE.md`에 쉘 털 v2, 그림자 바닥면, FurLab, Graphics API·MSAA 설정 반영
- 연구용 프로젝트(Beside_Reserch)의 `Assets`·`Packages`·`ProjectSettings`를 `unity/BesideApp/`에 반영. Unity 프로젝트 루트를 `unity/` → `unity/BesideApp/`로 옮겨 문서와 Unity 생성 파일을 분리
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
- GLB에 들어 있는 털 길이 맵·결 맵 읽기(`RuntimeModelLoader.TryReadEmbeddedHair`)
- 서버: AnimalLift `hair.npz`에서 털 길이·결 맵을 만들어 GLB `extras.beside`에 넣기, TANGENT 추가, 코·발바닥 털 제거, 텍스처 가장자리 채우기(`embed_hair.py`, `convert_asset.py`, `inference_service/app.py`)

## 2026-10-29 ~ 10-31 (날짜 대략)

- 연구용 프로젝트로 분리
- 그림자 바닥면(`ShadowCatcher.shader`): AR 바닥 점을 숨기면 그림자가 사라지던 문제 해결
