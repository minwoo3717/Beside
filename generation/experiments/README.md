# experiments — 실험 기록 (3D Generation 트랙)

AnimalLift 베이스라인 재현부터 품질·속도 개선까지 모든 실험을 여기에 기록한다. 기록이 없는 결과는 보고서·발표에서 "실험 결과"로 인용하지 않는다(루트 [CLAUDE.md](../../CLAUDE.md) 작업 원칙).

- 파일 하나 = 실험 하나. 이름: `YYYY-MM-DD_exp-NN_주제.md` (예 `2026-10-20_exp-01_baseline-reproduction.md`).
- 내용은 [TEMPLATE.md](TEMPLATE.md) 를 복사해 순서대로 채운다: 실패 사례 → 원인 가설 → 개선 → 비교 실험(지표 표) → 결론·다음 실험.
- 지표 이름은 [docs/METRICS.md](../../docs/METRICS.md) 를 그대로 쓴다. 환경(GPU, 드라이버, 입력 해상도, 커밋)을 반드시 적는다.
- 렌더 이미지는 `YYYY-MM-DD_exp-NN/` 폴더에 작은 크기(가로 ≤ 1024px)로 둔다. 가중치·메시 원본은 커밋하지 않는다.

| 날짜 | 파일 | 한 줄 결론 |
|---|---|---|
| 2026-11-26 (계획) | [2026-11-26_exp-04_ab-input-texture.md](2026-11-26_exp-04_ab-input-texture.md) | **[계획]** exp-02 베이스라인 품질, exp-03 입력 3안, exp-04 텍스처 패딩을 한 세션으로(11/27 Pod 생성, 11/28 블라인드 채점). 주 지표를 미리 정함. 도구 `tools/eval_*.py` 는 [구현됨] |
| 2026-11-17 | [2026-11-17_exp-01_baseline-runpod.md](2026-11-17_exp-01_baseline-runpod.md) | **[실험 결과]** 베이스라인 1건: A40 에서 inferMs 3.9~5.7 s, convertMs 0.85~1.1 s, gpuPeakMB 5,120, 삼각형 7,774, GLB 약 1.3 MB(표본 4건). 공개 주소 전체 12~16 s 중 5~7 s 는 VM ↔ Pod 중계 전송. 품질 채점은 [계획] |
| 2026-11-05 (틀만) | [2026-11-05_exp-03_input-preprocess.md](2026-11-05_exp-03_input-preprocess.md) | **[계획]** 원본(늘림) / 크롭+패딩 / 크롭+배경 제거 3D 비교 — 절차와 표만, 실행 전. 실행은 2026-11-26 기록의 세션으로 옮김 |
| 2026-11-05 | [2026-11-05_animallift-robustness.md](2026-11-05_animallift-robustness.md) | **[실험 결과]** AnimalLift 는 동물이 없는 사진에도 강아지를 냄 → 서버 입력 검사 필요. 사진 선택이 전처리보다 영향이 큼(RTX 4060 Ti, 공개 사진 6장 + AI 생성 사진) |
| 2026-11-05 | [2026-11-05_animallift-consistency.md](2026-11-05_animallift-consistency.md) | **[실험 결과]** 같은 개 식별 86~90%, 거의 털색으로 구분. 배경 제거가 가장 일관적이나 차이는 작음(AI 생성 5마리) |
| 2026-11-04 | [2026-11-04_model-comparison.md](2026-11-04_model-comparison.md) | **[실험 결과]** 1장당 생성 시간 SF3D 0.67 s, TripoSR 1.63 s, AnimalLift 5.31 s(RTX 4060 Ti). 고정 토폴로지·털 정보는 AnimalLift 만 |

위 세 기록(2026-11-04·05)은 Unity 트랙이 브랜치 `Unity`(0811aae)에 쓴 것을 2026-11-21 main 에 그대로 옮겼다. exp 번호를 정하기 전에 써서 이름에 `exp-NN` 이 없다(이름 규칙의 예외, 번호는 3D 담당과 정한 뒤 바꾼다). 측정 스크립트·원본은 리포 밖이다. 읽을 때 주의:

- 시간·메모리 정의가 [METRICS](../../docs/METRICS.md) 와 다르다. 1장당 생성 시간은 기울기 방식(모델 로딩 제외)이라 `inferMs` 가 아니고, VRAM 은 nvidia-smi 최대 − 직전(캐시 포함)이라 `gpuPeakMB`(torch 최대 할당)가 아니다. 그래서 exp-01 의 A40 값과 직접 비교하지 않는다.
- robustness 3차·consistency 의 원본 / 크롭패딩 / 배경 제거 비교는 모양·색 지표로 먼저 잰 것이다. exp-03 의 사람 채점 비교는 여전히 [계획]이다.
- 위 규칙의 환경 항목 중 GPU 드라이버와 커밋(AnimalLift·비교 모델·측정 스크립트)을 적지 않았고, [TEMPLATE.md](TEMPLATE.md) 순서(실패 사례 → 원인 가설 → 개선 → 비교 실험 → 결론)도 따르지 않은 조사 기록이다. 그래서 같은 조건으로 다시 돌릴 수 없고, 수치는 같은 기록 안의 비교(같은 GPU·같은 사진에서 모델끼리, 조건끼리)로만 인용한다. 드라이버·커밋을 알면 각 기록의 환경 표에 더한다.

Unity 쪽 측정(털 렌더링 성능)은 [unity/experiments](../../unity/experiments/README.md)에 있다.
