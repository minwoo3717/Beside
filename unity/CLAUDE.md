# unity — Android AR 클라이언트 (Unity 트랙)

공통 규칙은 루트 [CLAUDE.md](../CLAUDE.md). 여기에는 이 트랙의 규칙·실행·테스트 명령만 적는다.

## 규칙

- 앱은 **Spring API v1(`/api/v1`)만** 호출한다. v0 `/api/jobs`와 Python 추론 서비스는 호출하지 않는다.
- 계약 DTO는 `Assets/Beside/Api/`에 있다(`JobResponse`, `ApiResponses`, `ApiEnums`, `ApiV1Routes`). 필드 이름은 [docs/api/openapi.yaml](../docs/api/openapi.yaml)과 같아야 하며, 계약이 바뀌면 DTO만 고친다.
- 알 수 없는 enum 값(`status`, `variant`, `error.code`)은 크래시 대신 `Unknown`으로 처리한다(`ApiEnums.ParseJobStatus` 등). 모르는 JSON 필드는 무시한다.
- 오류 표시 문구는 [docs/api/ERROR_CODES.md](../docs/api/ERROR_CODES.md)의 `error.code` 기준으로 매핑한다. 서버 `message`를 그대로 사용자에게 보여주지 않는다.
- JSON 역직렬화는 Newtonsoft Json(`com.unity.nuget.newtonsoft-json`)을 쓴다. `JsonUtility`는 nullable 숫자와 null 객체를 처리하지 못한다.
- GLB는 `Application.persistentDataPath/models/{jobId}-{variant}.glb`에 캐시하고 glTFast로 로드한다. 배치 규칙(기본 스케일 0.6, 핀치 0.2~1.5, 원점 발바닥 중심)은 [docs/asset/GLB_SPEC.md](../docs/asset/GLB_SPEC.md).
- 측정값은 `Application.persistentDataPath/metrics/metrics.csv`에 [docs/METRICS.md](../docs/METRICS.md)의 열 이름 그대로 기록한다.
- Unity 생성 폴더(`Library/`, `Temp/`, `Logs/`, `Obj/`, `Build*/`, `UserSettings/`)는 커밋하지 않는다.

## 실행

- Unity 2022.3 LTS + URP. 패키지, Android 설정, 폰↔PC Mock 서버 연결 절차는 [README.md](README.md).
- Mock 서버: `cd ..\backend; .\gradlew.bat bootRun`. 기기에서는 PC IP 사용(예: `http://192.168.0.10:8080`). 연결 확인은 `GET {baseUrl}/api/v1/healthz`.
- 실패 흐름 테스트: 파일명에 `fail`이 들어간 사진을 업로드하면 Mock 서버가 FAILED를 돌려준다. `POST /api/v1/jobs/{id}/retry`로 재시도 흐름까지 확인한다.

## 테스트

- 에디터: Play 모드에서 `editorTestImagePaths`로 업로드 → 폴링 → 다운로드 흐름 확인(PLAN 1단계 DoD).
- 기기: `adb logcat -s Unity`로 로그 확인. `metrics.csv`는 `adb shell run-as <패키지명>` 또는 Android 파일 탐색기로 회수.
- [계획] Unity Test Framework(EditMode)로 DTO 파싱(알 수 없는 enum → `Unknown`, 모르는 필드 무시)을 검증한다.
