# 2026-11-26 계획 전환 훈련 D-1·D-3 — 원자료

결과와 해석은 [../../2026-11-26_failover-drills.md](../../2026-11-26_failover-drills.md) 에 있다. 시각은 모두 UTC 다. tailnet 계정 이름은 `<team>` 으로 가렸다. 줄 끝 공백은 지웠다.

| 파일 | 무엇 |
|---|---|
| `d1-failover.log` | D-1 `bash scripts/failover_vm.sh --planned beside-vm beside-standby` 의 화면 출력(15:48:26~15:50:52) |
| `failover-20261126T154828Z-beside-vm-to-beside-standby.tsv` | D-1 단계별 타임라인(`BESIDE_TIMELINE_DIR`). 열: ISO 시각, epoch ms, 단계, 내용 |
| `d3-dry-run.log` | D-3 전에 돌린 `DRY_RUN=1 … --planned beside-standby beside-vm`(15:53:19~15:53:59) |
| `d3-failback.log` | D-3 `bash scripts/failover_vm.sh --planned beside-standby beside-vm`(15:58:19~16:00:41) |
| `failover-20261126T155821Z-beside-standby-to-beside-vm.tsv` | D-3 단계별 타임라인 |
| `observations.txt` | 위 파일에 없는 명령 출력: Runpod 조회, 재기 요약, 표지 작업 조회, 백업·감시·backend 웹훅 로그, backend 종료 코드, 전환 뒤 두 VM 상태, H2 사본 행 수·차이, Grafana 알림 상태 |
| `probe-outages.csv` | 바깥 재기(`drill_client.py run --no-jobs`, Runpod CPU Pod US-CA-2)에서 200 이 아닌 모든 줄과 그 앞뒤 3줄씩, 126줄. 열: 응답이 끝난 시각, HTTP 코드(0 = 무응답), 걸린 ms, 오류 |

- 재기 원본 `probe.jsonl` 은 1,256줄(15:43:30.739~16:04:41.709)이다. Pod 를 지우기 전에 Pod 안에서 요약했고, 원본은 리포에 넣지 않았다.
  - 원본 sha256: `4c10779638c47e002c8b4d65b28da2f9b9f07e79c860684a654af0ad0d62cf1a`
  - 원본 전체를 `t,code,ms,err` CSV 로 줄인 판의 sha256: `dd3a10223030c0cd5fadda7e82e3aa6d2a8d502912118190432389be592a138a`
  - `probe-outages.csv` 는 Pod 에서 만든 같은 파일과 sha256 이 같다: `f419b0eba675ba9024cebc5f21526d8456906cb94dc67f8a2dafae766ab71724`
- 표지 작업 id: A `5caadcc7-aca3-40d3-af00-3ac5f7110108`(15:43:35.984), B `d8c13f6b-0404-4822-9027-6440cc7741c4`(15:48:21.915), A2 `e431ee27-49b3-4be5-9d81-9bbe03e6180a`(15:53:20.291), B2 `6f0ca0ce-1a58-47c2-9544-2c9283b05748`(15:58:14.866).
- H2 사본 두 개(D-1 때 멈춘 원본 DB, D-3 때 멈춘 대기 VM DB)는 운영 데이터라 리포에 넣지 않았다.
  - sha256: `7d4bb4f8e8a149925d663d5c8f8af70cb123b703d388c1db656b0df450429852`(원본, 167,936 B), `6c447d5be24f004f49c1ffddb5bd4bb14dd48c5457a13195eec12ae852c3ddec`(대기, 131,072 B)
  - 행 수 비교는 결과 문서에 있다.
