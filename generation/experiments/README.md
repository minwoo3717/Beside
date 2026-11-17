# experiments — 실험 기록 (3D Generation 트랙)

AnimalLift 베이스라인 재현부터 품질·속도 개선까지 모든 실험을 여기에 기록한다. 기록이 없는 결과는 보고서·발표에서 "실험 결과"로 인용하지 않는다(루트 [CLAUDE.md](../../CLAUDE.md) 작업 원칙).

- 파일 하나 = 실험 하나. 이름: `YYYY-MM-DD_exp-NN_주제.md` (예 `2026-10-20_exp-01_baseline-reproduction.md`).
- 내용은 [TEMPLATE.md](TEMPLATE.md) 를 복사해 순서대로 채운다: 실패 사례 → 원인 가설 → 개선 → 비교 실험(지표 표) → 결론·다음 실험.
- 지표 이름은 [docs/METRICS.md](../../docs/METRICS.md) 를 그대로 쓴다. 환경(GPU, 드라이버, 입력 해상도, 커밋)을 반드시 적는다.
- 렌더 이미지는 `YYYY-MM-DD_exp-NN/` 폴더에 작은 크기(가로 ≤ 1024px)로 둔다. 가중치·메시 원본은 커밋하지 않는다.

| 날짜 | 파일 | 한 줄 결론 |
|---|---|---|
| 2026-11-04 | [2026-11-04_model-comparison.md](2026-11-04_model-comparison.md) | 1장당 생성 시간 SF3D 0.67 s, TripoSR 1.63 s, AnimalLift 5.31 s. 고정 토폴로지·털 정보는 AnimalLift만 제공 |
| 2026-11-05 | [2026-11-05_animallift-robustness.md](2026-11-05_animallift-robustness.md) | AnimalLift는 동물이 없는 사진에도 강아지를 냄 → 서버 입력 검사 필요. 사진 선택이 전처리보다 영향이 큼 |
| 2026-11-05 | [2026-11-05_animallift-consistency.md](2026-11-05_animallift-consistency.md) | 같은 개 식별 86~90%, 거의 털색으로 구분됨. 배경 제거가 가장 일관적이나 차이는 작음 |

위 세 기록은 exp 번호를 붙이기 전에 쓴 것이라 파일 이름에 `exp-NN`이 없다. 번호를 정하면 파일 이름을 바꾼다. Unity 쪽 측정(털 렌더링 성능)은 [unity/experiments](../../unity/experiments/README.md)에 있다.
