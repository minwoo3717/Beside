# experiments — 실험 기록 (Unity 트랙)

앱 쪽 성능·화질 측정을 여기에 기록한다. 규칙은 [generation/experiments](../../generation/experiments/README.md)와 같다: 파일 하나 = 실험 하나, 이름 `YYYY-MM-DD_주제.md`, 그림·원본 데이터는 같은 이름의 폴더에 둔다. 기록이 없는 결과는 "실험 결과"로 인용하지 않는다.

| 날짜 | 파일 | 한 줄 결론 |
|---|---|---|
| 2026-11-03 | [2026-11-03_fur-benchmark.md](2026-11-03_fur-benchmark.md) | 쉘 1겹당 가까이 1.33 ms / 멀리 0.17 ms. 최적화(가까이): 쉘 셰이더 v2 −16%, 쉘 수 자동 조절 −45%, MSAA 4x→2x −32% |

- 측정 도구: `Assets/Beside/FurLab/` ([README](../BesideApp/Assets/Beside/FurLab/README.md)). 분석: 루트 `scripts/analyze_fur_bench.py`.
- 절대 ms는 기기·발열에 따라 달라진다. 결론은 같은 라운드 안에서 나눈 비율로 낸다.
- 측정 기기·Graphics API·MSAA·거리(화면 차지 %)를 반드시 적는다.
