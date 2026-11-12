# GCP 메인 서버 첫 배포 기록 (2026-11-13)

팀 메인 서버를 GCP 무료 체험 크레딧으로 처음 띄우고 확인한 기록이다. 절차와 운영은 [deploy/README.md](../../deploy/README.md) 'GCP 메인 서버'. 시각은 한국 시각(KST), 괄호 안은 VM 로그의 UTC.

- **[구현됨]** VM·도메인·HTTPS·Basic 인증·전처리 모델·업로드 모드 추론(스텁)까지 공개 주소에서 동작한다. 재부팅·`deploy_vm.sh` 업데이트 경로도 확인했다.
- **[실험 결과]** 아래 수치는 표본 1~3건이다. 동작 확인용이며 성능 측정이 아니다(반복·통제 없음).
- **[계획]** Tailscale 과 팀원 GPU PC(실제 AnimalLift), VM 위 부하 시험, 실제 하루 비용 확인.

## 구성

| 항목 | 값 |
|---|---|
| VM | GCP `beside-514511` / `beside-vm`, asia-northeast3-a, e2-standard-2(2 vCPU, 7.7 GiB), Ubuntu 24.04.5(커널 7.0.0-1017-gcp), 균형 디스크 50GB, 스왑 2GB |
| 주소 | 고정 IP 34.64.91.254, `https://beside-team.duckdns.org`(Let's Encrypt) |
| 소프트웨어 | Docker 29.9.3, Compose v5.7.1, caddy 2.11.7, backend 이미지(`feat/preprocess` `ad0c3f6`, profile `real,prod`, 전처리 켬) |
| Caddyfile | 위 커밋 + 감사 뒤 강화판(`deploy/gcp` 브랜치, VM 에는 push 전까지 로컬 수정으로 적용) |
| 추론 | VM 안 패스스루 스텁 `beside-stub`(리포의 FastAPI 앱, 업로드 모드, 토큰 필수). 샘플 GLB 는 개발 PC 의 `sample-dog-rigged.glb`(1,429,772 B — 리포의 `sample-dog.glb` 는 0 B) |
| 클라이언트 | 개발 PC(국내 가정용 회선), Git Bash curl, `scripts/e2e_mock.sh` |

## 수치 [실험 결과]

| 항목 | 값 | 조건 |
|---|---|---|
| backend 이미지 첫 빌드 | 145 s, 582 MB | VM 에서 `compose build backend`, Gradle 캐시 없음 |
| 스텁 이미지 빌드 | 19 s, 248 MB | `python:3.12-slim` + requirements |
| 전처리 모델 받기 | 7 s(210 MB) | `fetch_models.sh`, sha256 통과 |
| backend 기동 → healthy | 23~24 s | Spring `Started ... in 14.9 s`(재부팅 직후 19.7 s), YOLOX 준비 1.2~1.3 s, U2Net 4.0~4.1 s |
| 인증서 발급 | 12 s | Caddy 첫 기동 → `certificate obtained successfully` |
| healthz(밖에서) | 34~54 ms | 개발 PC, 요청마다 새 TLS 연결, 5회 |
| 전체 흐름(밖에서) | 업로드 971 / 1085 ms, 대기 2839 / 2797 ms, 다운로드 224 / 187 ms | `dog.jpg`, `dog-single-black.jpg`(재부팅 뒤). 업로드에 대표 사진 검사 포함 |
| 전체 흐름(VM 안) | 업로드 652 ms, 대기 3184 ms, 다운로드 48 ms | compose 망 안의 컨테이너에서 `dog.jpg` |
| 크롭+배경 제거 | 1934~2577 ms(검출 328~425, 분할 1455~1900) | 4건, `CutoutStage` 로그 |
| 메모리 | backend 1.48~1.56 GiB / 한도 3 GiB, caddy 55 MiB, 스텁 36~61 MiB, VM 사용 2.2 GB / 7.7 GiB, 스왑 0 | 작업 1~5건 뒤 `docker stats` |
| 디스크 | 8.4 GB / 48 GB | 이미지 0.9 GB, 빌드 캐시 2.1 GB |
| 재부팅 | 공개 healthz 끊김 50 s, 컨테이너 3개 자동 복구, 인증서 재발급 없음 | `systemctl reboot`(보안 업데이트 5개 적용 뒤) |
| `scripts/deploy_vm.sh beside-gcp` | 4 s, [4/4] healthz 통과 | 바뀐 코드 없음(캐시 빌드, 재생성 없음) |

## 확인한 것

- 밖에서: `healthz` 200(`real,prod`), `http://` → 308, 404(`/h2-console`, `/swagger-ui.html`, `/v3/api-docs`, `/api/jobs`, `;` 경로, 대소문자·퍼센트 인코딩 변형), 401(웹 화면, 목록 GET·HEAD, 삭제, 관리·보관함), 26MB 본문 413, 8080·8001 닫힘.
- 대표 사진 검사: `dogs-two-swimming.jpg` → 400 `TOO_MANY_ANIMALS`, `no-animal-room.jpg` → 400 `NO_ANIMAL_DETECTED`(202 전).
- 스텁: 토큰 없이 `/infer/upload` 401.
- 팀 비밀번호: 01:09(16:09Z)에 개발 PC 쪽 SSH 세션에서 `beside-set-password` 로 설정됐다(`.env` 의 bcrypt 해시 = 실행 중 Caddy 의 해시). 로그인 200 은 비밀번호를 아는 사람이 브라우저로 확인한다 **[계획]**.

## 보안 감사

에이전트 4개(경로·인증 우회, 공개 API 노출, TLS·네트워크, VM 설정)가 각자 시험하고, 발견마다 다른 에이전트가 다시 재현해 판정했다(읽기 전용, 요청 약 150건 이하, 업로드 3건 이하). 재현된 것과 조치:

| 발견 | 판정 | 조치 |
|---|---|---|
| `/index.html/`, `/index.html//`, `/app.js/` 가 인증 없이 200(Mock UI) | 재현, 중간 | **[구현됨]** Caddyfile 파일 이름 접두사 매칭. VM 에 적용, 밖에서 401 확인 |
| `/webjars/swagger-ui/...` 200 | 재현, 낮음 | **[구현됨]** `/webjars*` 404 |
| 소문자 `get`·`delete` 가 인증 규칙을 비켜 Spring 까지(Spring 은 405) | 재현, 낮음 | **[구현됨]** "POST 가 아니면 인증" 식으로 |
| 손님의 `X-Forwarded-Prefix`·`Forwarded` 를 Spring 이 받음 | 재현, 낮음 | **[구현됨]** Caddy 에서 지움 |
| HSTS 등 보안 헤더 없음 | 재현, 낮음 | **[구현됨]** HSTS 180일, nosniff, X-Frame-Options |
| 기본 서비스 계정 → 컨테이너가 메타데이터 서버에서 토큰을 받을 수 있음 | 재현, 낮음 | `create_vm.sh` 는 서비스 계정 없이 만들게 **[구현됨]**. 지금 VM 은 멈춘 뒤 바꿔야 해서 **[계획]**(사용자, Cloud Shell) |
| 기본 네트워크 규칙 `default-allow-rdp`(3389)·ICMP 열림 | 재현, 정보 | 리눅스라 듣는 것이 없음. 지우기는 **[계획]**(사용자) |
| SSH 22 전체 공개(키 인증만, 비밀번호 거부 확인) | 재현, 낮음 | 유지(팀 IP 가 고정이 아님) |
| 공개 업로드에 요청 수 제한 없음 | 재현, 낮음 | 범위 밖(ARCHITECTURE '보안'). 부하 시험 때 다시 본다 |
| `Idempotency-Key` 가 아는 사람에게 같은 작업 id 를 돌려줌(키 만료 없음) | 재현, 낮음 | 계약 안건으로 **[계획]** |
| 공개 400 메시지에 Java 예외 클래스 이름 | 재현, 낮음 | backend 수정 **[계획]** |
| Docker·containerd 는 무인 업데이트 대상 아님, 백업이 같은 디스크, Caddy 정지 10 s 넘김(SIGKILL), pids 제한 없음 | 재현, 낮음~정보 | [CHECKLIST](../CHECKLIST.md) 에 남김 **[계획]** |

## 사고: 약 1분 중단 (01:08~01:09, 16:08~16:09Z)

`deploy/docker-compose.yml` 은 `name: beside` 로 프로젝트 이름을 고정한다. 비밀번호 도구를 시험하려고 compose 파일을 `/tmp` 에 복사해 돌렸더니 그 복사본이 실제 서버의 caddy·backend 를 시험 설정(빈 모델 폴더, 예시 도메인)으로 다시 만들었다. `~/Beside` 에서 `up -d` 로 바로 복구했고 볼륨(데이터·인증서)은 그대로였다. 도구(`deploy/set_basic_auth.sh`)는 떠 있는 caddy 가 같은 체크아웃의 것일 때만 다시 만들게 고쳤고, 이름이 같은 별도 시험 프로젝트로 확인했다. 운영 규칙은 deploy/README 'GCP 메인 서버'의 주의.
