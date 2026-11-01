# 다음 버전 측정 절차와 남은 측정

## 다음 버전(v3)을 만들었을 때

1. `docs/perf/versions.json` 의 `versions` 에 `v3` 를 더한다. `ref` 는 잴 커밋의 **SHA** 로 적는다(브랜치는 움직여서 커밋한 보고서를 다시 만들 수 없다). `capabilities` 에는 그 버전에 있는 기능(`mock`, `real_path`, `real_upload`, `prod`)만 넣는다.
2. `order` 끝에 `v3` 를 넣는다. 보고서의 색은 이 순서의 자리로 정해지므로 앞 라벨의 자리는 바꾸지 않는다.
3. `pairs` 에 직전 버전과의 쌍을 더한다. 예: `{"from": "v2q", "to": "v3", "title": "v1.2 의 변화"}`. 배포 버전이 바뀌면 `baseline` 도 옮긴다.
4. 기준 버전·직전 버전과 **같은 실행에서** 잰다. 날짜가 다른 실행의 결과를 섞어 판정하지 않는다: 다른 프로그램의 부하, 온도, OS·JDK 업데이트가 실행마다 달라 판정 규칙(반복 사이 범위)이 그 차이를 걸러 내지 못한다.

```powershell
python scripts/perf/run.py --versions v1,v2q,v3 --reps 3 --versions-file docs/perf/versions.json --stub-python <fastapi·uvicorn 이 깔린 python>
python scripts/perf/report.py --data docs/perf/data/<새 폴더> --versions-file docs/perf/versions.json --notes docs/perf/notes --out docs/perf/backend-perf-report.pdf
```

5. 개선이 있으면 '개선 사례'와 같은 순서(실패 사례 → 원인 가설 → 개선 → 비교 실험)로 `docs/perf/notes/` 에 적는다. 새 데이터 폴더, PDF, 노트를 한 커밋에 올린다.
6. 원인이 여럿 겹쳐 있으면 의심 부분만 바꾼 커밋을 따로 만들어 `versions-ablation.json` 처럼 별도 버전 파일로 A/B 측정한다(P7 에서 트랜잭션 몫과 `@Query` 재파싱 몫을 이렇게 나눴다). 이 PC 에서는 JFR 실행 표본이 거의 잡히지 않아 프로파일러로는 나누지 못했다.

## 측정 전 점검

- 무거운 프로그램(게임 클라이언트, 화상 회의, 다른 빌드, 여러 에이전트를 띄우는 다른 세션)을 끈다. 그 부하는 보고서 '한계'에 스위트마다 잰 CPU 사용률로 적힌다.
- 측정 중에 PC 가 절전에 들어가지 않게 한다(2026-11-01 실행은 밤사이 시작이 8시간 밀렸다).
- 노트북이면 전원을 연결하고 절전 모드를 끈다.
- `--quick --reps 1` 로 한 번 돌려 모든 시나리오가 `ok` 인지 먼저 본다(약 1분 × 버전 수).

## 아직 재지 않은 것 [계획]

| 항목 | 왜 필요한가 | 방법 | 담당 |
|---|---|---|---|
| 클라우드 VM(2 vCPU)에서 같은 시나리오 | 이 보고서는 개발 PC 에서 서버를 논리 CPU 2개(물리 코어 1개)에 고정해 VM 을 흉내 낸 값이다 | VM 에 JDK 17·Python 을 두고 같은 도구를 실행한다. 개발 PC 에서 VM 으로 원격 부하를 거는 것은 도구 확장이 필요하다 | Backend |
| VM ↔ GPU PC(Tailscale) 업로드 모드의 전송 시간 | 사진 업로드, GLB 다운로드, `DELETE /artifacts` 가 루프백이 아니라 실제 망에서 얼마인지 | VM 의 Spring(`real,prod`, 업로드 모드)과 GPU PC 의 추론 서비스로 E2E. events.jsonl 의 `processingMs` 와 `inferMs`·`convertMs` 의 차이가 전송·저장 시간이다 | Backend + 3D |
| 실제 AnimalLift 추론 시간과 GPU 메모리 | 이 보고서의 real 시나리오는 모델 없는 패스스루 스텁이라 추론 시간이 0 이다 | exp-01 (`generation/experiments/`) | 3D |
| Unity 로딩 시간과 FPS | 서버 수치만으로는 사용자가 기다리는 시간을 다 설명하지 못한다 | 앱의 metrics.csv (METRICS §3·§4) | Unity |
