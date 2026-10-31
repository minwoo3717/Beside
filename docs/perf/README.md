# docs/perf — 백엔드 성능 측정 결과

백엔드 버전(커밋)마다 jar 를 만들어 같은 PC 에서 같은 시나리오로 재고, 그 결과로 만든 A4 보고서를 둔다. 측정 도구와 자세한 방법은 [scripts/perf/README.md](../../scripts/perf/README.md).

- **[구현됨]** 측정·보고서 도구 `scripts/perf/`(Python 표준 라이브러리, 단위 테스트 `python -m unittest discover -s scripts/perf -p "test_*.py"`).
- **[실험 결과]** `data/` 의 원시 결과와 [backend-perf-report.pdf](backend-perf-report.pdf). 측정 조건(PC, JVM 옵션, 고정 CPU, 다른 프로그램의 부하, 명령)은 각 데이터 폴더의 `run.json` 과 보고서 '측정 방법'에 있다.
- **[계획]** 클라우드 VM, VM ↔ GPU PC(Tailscale), 실제 AnimalLift 추론, Unity 쪽 측정: [notes/20-next-version.md](notes/20-next-version.md).

## 파일

| 파일 | 내용 |
|---|---|
| `versions.json` | 비교할 버전(라벨 → 커밋 SHA, 제목, 설명, 기능), 판정 기준 `baseline`, 두 버전을 맞대는 `pairs` |
| `data/<YYYY-MM-DD_HHMM>/` | `run.json`(환경, 명령, 버전 SHA, 반복별 순서) + `<라벨>-r<반복>.json`(스위트 하나의 원시 표본, 0.01 ms 단위). 이 보고서는 `data/2026-10-31_1434/`(버전 5개 × 3회, 14:34~15:30) |
| `notes/*.md` | 보고서 3장 뒤에 들어가는 서술(파일 이름 순): 개선 사례, 다음 버전 절차 |
| `backend-perf-report.pdf` | 위 셋으로 만든 보고서 |

## 버전

| 라벨 | 커밋 | 무엇 |
|---|---|---|
| v0 | `e0e1c2b` | 이전: origin/main (10-14, #1 머지) |
| v1 | `af4a747` | 현재: main = deploy/vm-4b (4b 배포 기반). **판정 기준** |
| v1p | `033f3f3` | 현재 + 개선 P1 (perf/backend-report) |
| v2 | `6a3959f` | 다음 버전 후보 v1.1 (review/api-v1.1 의 rebase 전, 계약 회의 전) |
| v2p | `ecc5fb5` | 다음 버전 + 개선 P1·P4·P5 (review/api-v1.1) |

쌍 비교: v0 → v1(이전 → 현재), v1 → v1p(현재의 개선), v1 → v2(다음 버전 기능의 비용), v2 → v2p(다음 버전의 개선).

v1p·v2·v2p 커밋은 아직 push 하지 않은 로컬 브랜치에만 있다(`perf/backend-report`, 백업 브랜치 `backup/review-api-v1.1-6a3959f`·`-ecc5fb5`). 다른 PC 에서 다시 재려면 이 커밋들을 먼저 올려야 하고, 백업 브랜치를 지우면 v2·v2p 는 다시 잴 수 없다.

## 다시 만들기

```powershell
# 측정 (버전 5개 × 3회, 약 2시간. 결과는 docs/perf/data/<측정 시각>/)
python scripts/perf/run.py --versions v0,v1,v1p,v2,v2p --reps 3 --versions-file docs/perf/versions.json --stub-python <fastapi·uvicorn 이 깔린 python>
# 보고서
python scripts/perf/report.py --data docs/perf/data/2026-10-31_1434 --versions-file docs/perf/versions.json --notes docs/perf/notes --out docs/perf/backend-perf-report.pdf
```

새 버전을 더하는 절차는 [notes/20-next-version.md](notes/20-next-version.md).

## 결과 요약 [실험 결과] (2026-10-31, 이 PC)

- 이전 → 현재(v0 → v1): 측정한 경로에 회귀 없음.
- 현재의 개선(v1 → v1p, P1 키셋 목록): 목록 첫 페이지 N=2000 에서 13.1 → 3.6 ms(−72 %), 최대 RSS −10 %.
- 다음 버전의 비용(v1 → v2): 업로드 +50~+560 %(요청 안에서 만드는 썸네일 — P4 로 옮기자 큰 사진·12 MP 는 v1 수준), 폴링 처리량 −26 %, PENDING 조회 +88 %(이 둘의 원인은 아직 가설).
- 다음 버전의 개선(v2 → v2p, P1·P4·P5): 큰 사진·12 MP·real 업로드 요청 −62~−85 %(v1 수준), 목록 첫 페이지 −38~−54 %. P5 는 PENDING 조회에 효과 없음(차이 없음).
- 다음 버전에 남은 비용(v1 → v2p): 폴링 처리량 −25 %, PENDING 조회 +79 %, 단건 조회 +26~30 %, 목록 중간 페이지 +14~58 %, 작은 사진 업로드 +28 %, 기동 +12 % 등 — 전체 목록과 가설·다음 확인은 [notes/10-improvements.md](notes/10-improvements.md).
- 조건: 서버를 논리 CPU 2개(물리 코어 1개)에 고정, 스위트 직전 배경 CPU 17~33 %, 실시간 백신이 갓 쓴 4.5 MB 파일 첫 열기에 216 ms.

## 읽는 법

- 판정: 기준 대비 p50 변화가 10 % 이상이고, 반복별 p50 의 [최소, 최대] 범위가 기준의 범위와 겹치지 않을 때만 **개선**·**악화**, 나머지는 **차이 없음**(잡음).
- 이름: `bench.*` 값과 `postMs`·`flowMs`·`pollMs` 같은 클라이언트 표본 이름은 이 벤치마크 전용이고 [docs/METRICS.md](../METRICS.md) 에 등록된 이름이 아니다(계약 회의 안건 [REVIEW_CHECKLIST #15](../api/REVIEW_CHECKLIST.md)). 서버 값 `uploadMs`·`queuedMs`·`processingMs`·`totalMs` 만 METRICS §1 의 뜻 그대로다.
- 한계: 개발 PC 루프백(127.0.0.1)에서 잰 값이고, real 시나리오는 모델 없는 패스스루 스텁이라 Spring 쪽 오버헤드만 보인다. 클라우드 VM·Tailscale·GPU 의 수치가 아니다.
