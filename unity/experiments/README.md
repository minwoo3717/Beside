# experiments — 실험 기록 (Unity 트랙)

앱 쪽 성능·화질 측정을 여기에 기록한다. 규칙은 [generation/experiments](../../generation/experiments/README.md)를 따르되 이름은 `YYYY-MM-DD_주제.md`(exp 번호 없음)로 한다: 파일 하나 = 실험 하나, 그림·원본 데이터는 같은 이름의 폴더에 둔다. 기록이 없는 결과는 "실험 결과"로 인용하지 않는다. 그래프 PNG 는 글자를 읽을 수 있게 가로 1024 px 보다 크게 둔다(generation 의 렌더 이미지 규칙과 다름).

| 날짜 | 파일 | 한 줄 결론 |
|---|---|---|
| 2026-11-03 | [2026-11-03_fur-benchmark.md](2026-11-03_fur-benchmark.md) | **[실험 결과]** 쉘 1겹당 가까이 1.33 ms / 멀리 0.17 ms. 최적화(가까이): 쉘 셰이더 v2 −16%, 쉘 수 자동 조절 −45%, MSAA 4x→2x −32% (SM-S947N 1대) |

- 측정 도구: `Assets/Beside/FurLab/` ([README](../Assets/Beside/FurLab/README.md)). 분석: 루트 `scripts/analyze_fur_bench.py`.
- 절대 ms는 기기·발열에 따라 달라진다. 결론은 같은 라운드 안에서 나눈 비율로 낸다.
- 측정 기기·Graphics API·MSAA·거리(화면 차지 %)를 반드시 적는다.
- 2026-11-03 기록은 연구용 프로젝트에서 브랜치 `Unity`(4e6f190·0811aae) 코드로 잰 것이고, 2026-11-21 main 에 옮겼다. main 에 합친 코드로는 다시 재지 않았다.
