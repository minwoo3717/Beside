# FurLab: 털 성능 측정 (발표용)

배치된 펫 하나에 털 조건을 차례로 씌우고, 조건마다 프레임 시간과 GPU 시간을 재서 CSV와 스크린샷으로 저장합니다.

## 측정 묶음

| 묶음 | 질문 | 조건 |
|---|---|---|
| **방식 비교** (`method`) | ① 털 방식마다 비용이 얼마인가 | 털 없음, 노멀맵만, 쉘 8·16·24겹, 쉘16+핀, 가닥 5천 |
| **최적화 비교** (`optim`) | ② 우리가 한 최적화가 효과가 있었나 | 쉘16 v1 대 v2, 쉘20 고정 대 자동 조절, 쉘16 MSAA 끔·2x·4x |

- 방식 비교의 쉘은 개수를 고정해서 잽니다(자동 조절 끔). MSAA는 프로젝트 설정을 그대로 씁니다.
- `shell20_adapt`는 앱에 실제로 들어가는 설정입니다. 가까이 갈수록 쉘 수가 줄어듭니다.
- 조건 목록은 Inspector에서 고칠 수 있습니다. 기존 FurBenchmark 컴포넌트는 예전 목록이 저장돼 있으니, 컴포넌트 메뉴(⋮)에서 **Reset conditions to defaults**를 한 번 실행하세요.

## Unity 설정

1. `Assets/Beside/FurLab` 폴더를 덮어씁니다(`ShellFurV1.shader`가 새로 추가됨).
2. 머티리얼을 준비합니다.
   - `FurStrand_Mat`: `Custom/StrandFur`
   - `FurFin_Mat`: `Custom/FinFur`
   - **`FurShellV1_Mat`**(새로 만들기): `Custom/ShellFurV1`. 이 머티리얼이 없으면 v1 조건은 건너뜁니다.
3. FurBenchmark의 Assets 칸을 채웁니다.
   - Shell Material: ModelLoader의 쉘 털 머티리얼
   - **Shell V1 Material**: `FurShellV1_Mat`
   - Strand Material: `FurStrand_Mat`
   - Fin Material: `FurFin_Mat`
4. Player Settings에서 **Frame Timing Stats**를 켭니다. Graphics APIs는 **OpenGLES3를 맨 위**에 둡니다(아래 "Graphics API" 참고).

## 측정 방식 (2026-11-02 1~3차 측정에서 드러난 문제 반영)

1차 측정에서 세 가지 문제가 있었고, 측정 도구에 다음과 같이 반영했습니다.

1. **AR 30 FPS 고정 해제:** 측정 중에는 ARSession의 Match Frame Rate를 끄고, 끝나면 원래대로 돌립니다(CSV `arFrameRateLocked`).
2. **부하 배수 + 기울기 측정:** 조건마다 펫(몸 + 털)을 K번 겹쳐 그려 GPU를 한계까지 일하게 합니다.
   - K는 조건마다 자동으로 정합니다. 프레임 시간이 주사율 간격의 2배가 될 때까지 1, 2, 4… 식으로 두 배씩 올리고, 최대 128입니다(`loadRepeatLimit`).
   - 그다음 **K번 그릴 때**와 **2K번 그릴 때** 각각 10초씩 잽니다.
   - **`msPerPet` = (2K일 때 평균 프레임 시간 − K일 때 평균 프레임 시간) ÷ K**
   - 이 차이에는 매 프레임 한 번만 드는 비용(AR 카메라 화면, UI)과 화면 동기화 대기 시간이 빠집니다. 그래서 펫 1마리를 그리는 실제 GPU 시간이 남습니다.
   - 2차 테스트에서 드러난 문제가 두 가지였습니다.
     - 프레임 시간이 화면 갱신 간격(16.7 ms)의 배수로만 찍혔습니다(33.3, 50, 66.7, 83.3 ms).
     - 털 없음·가닥 조건은 x16으로도 60 FPS 그대로라 GPU 한계에 닿지 않았습니다.
   - 두 문제를 위 방식으로 해결했습니다. 최대 부하에서도 한계에 닿지 않는 조건은 `notGpuBound`로 표시되고, 표에서 ※(상한값)로 나옵니다.
3. **라운드:** 기본 5라운드 × 20초(K 10초 + 2K 10초)입니다. 2026-11-03 최종 측정은 3라운드로 했습니다.
4. **발열 대기:** 배터리 온도가 45°C를 넘으면 43°C로 내려갈 때까지(최대 90초) 기다립니다.
   - 이 부하에서 폰은 41~43°C에서 안정됩니다. 그래서 그 아래로 식히려고 기다리는 건 시간만 듭니다(3차 측정에서 조건마다 3분씩 대기해서 1시간 걸림).
   - 라운드마다 조건 순서를 돌리기 때문에 발열 영향은 모든 조건에 고르게 나뉩니다.
5. **화면 꺼짐 방지:** 측정 중에는 화면이 꺼지지 않게 합니다.

**Graphics API:** Player → Other Settings → Auto Graphics API를 끄고 **OpenGLES3를 맨 위, Vulkan을 그 아래**에 둡니다. Vulkan만 남기면 SM-S947N에서 ARCore 카메라 화면이 검게 나옵니다(2026-11-02 확인). 최종 측정도 OpenGLES3(ANGLE)로 돌았습니다.

**거리:** 패널에 "화면 차지 %"가 실시간으로 나오니, 이 값으로 폰 위치를 맞춥니다. 가까이는 1차와 같은 80~90%, 멀리는 **20~30%**입니다(1차는 3%로 너무 멀었음).

**MSAA 끔 조건:** v2 털은 MSAA가 없으면 대부분 그려지지 않습니다. 비용만 싸 보이는 것이니, 화질 비교와 결론에서는 빼세요.

## 측정 방법

1. 새로 만들기는 하지 않습니다. **같은 모델 하나**로 모든 측정을 합니다.
2. 펫을 배치하고 **버튼 숨기기**를 누른 뒤 폰을 거치대에 고정합니다.
3. 네 손가락으로 탭해서 패널을 열고 다음을 고릅니다.
   - 묶음: 방식 비교 / 최적화 비교 / 전체
   - 거리: **가까이**(펫이 화면 절반) / **멀리**(화면 1/5)
4. 먼저 **빠른** 측정으로 화면이 정상인지 확인합니다. 그다음 **정식** 측정을 합니다(5라운드 × 조건당 약 26초 + 부하 보정 약 20초).
5. 측정은 총 4번 합니다.

   | 묶음 | 가까이 | 멀리 |
   |---|---|---|
   | 방식 비교 | 약 16분 | 약 16분 |
   | 최적화 비교 | 약 16분 | 약 16분 |

   측정 사이에 폰을 5분쯤 식혀 주세요(배터리 온도도 기록됩니다).

   최종 측정 결과는 [unity/experiments/2026-11-03_fur-benchmark.md](../../../experiments/2026-11-03_fur-benchmark.md)에 있습니다.
6. 끝나면 **전체 공유**로 CSV를 보내고, **스크린샷→갤러리**로 조건별 스크린샷을 갤러리의 Beside 앨범에 저장합니다.

측정 중에는 30 FPS 상한을 풀고, 조건마다 MSAA를 바꿨다가 끝나면 원래대로 돌려놓습니다.

## 분석 (PC)

```bash
pip install matplotlib
python scripts/analyze_fur_bench.py fur_bench/*.csv --out fur_report
```

`scripts/`는 저장소 루트에 있습니다.

| 파일 | 내용 |
|---|---|
| `summary.md` / `summary.csv` | 거리별·조건별 GPU ms, 털 추가 비용, 평균 FPS, 1% low, p95, 메모리 |
| `unit_costs.md` | 쉘 1겹당 ms, 핀·가닥·텍스처 추가 비용, 최적화 전후 개선율(%), 예산 안 최대 쉘 수 |
| `1_method_cost.png` | ① 방식별 GPU 비용 (가까이 대 멀리) |
| `2_shell_scaling.png` | ① 쉘 수에 따른 비용 직선 (기울기 = 쉘 1겹 단가) |
| `3_optimization.png` | ② v1→v2, 고정→자동, MSAA 비교 |
| `4_quality_vs_cost.png` | ③ 화질 점수 대 비용 (`--quality` 사용 시) |

## 화질 평가 (③)

```bash
python scripts/make_rating_set.py prepare <스크린샷 폴더> --out rating --distance near
#   rating/A01.png ... 와 board.png(한 장에 모은 이미지)를 평가자에게 보냅니다. key.csv는 보여주지 않습니다.
#   평가자마다 sheet.csv에 열을 하나씩 추가해서 1~5점(5 = 진짜 털 같음)을 적습니다.
python scripts/make_rating_set.py score rating
python scripts/analyze_fur_bench.py fur_bench/*.csv --out fur_report --quality rating/scores.csv
```

## 지표

- **msPerPet**(주 지표): 펫 1마리를 그리는 GPU 시간입니다(위 "부하 배수 + 기울기 측정"). FPS는 상한(30/60)에 걸려 차이가 안 보이므로, 비용은 이 값으로 비교합니다.
- **gpuMs**: Frame Timing Stats가 주는 프레임당 GPU 시간입니다. 기기에서 값이 흔들려 보조 지표로만 씁니다.
- **avgFps, low1Fps**: 평균 FPS와 가장 느린 1% 프레임의 FPS입니다(끊김 정도).
- **coveragePct**: 펫이 화면에서 차지하는 비율입니다. 쉘 비용은 이 값에 비례합니다.
- **shellsDrawn**: 실제로 그린 쉘 수입니다. 자동 조절 조건에서 줄어든 것이 보입니다.
- 측정 중 펫 크기가 30% 넘게 바뀐 행은 `moved`로 표시되고 분석에서 빠집니다.
