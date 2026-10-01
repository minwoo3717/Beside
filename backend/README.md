# Beside Again — Browser Mock MVP

사진 선택 → 업로드 → 비동기 작업 생성 → 상태 조회 → Mock 결과를 브라우저에서 확인하는 Spring Boot 앱입니다. 실제 AI 모델, 3D 뷰어, Unity AR는 연결하지 않습니다.

## 실행

Java 17 환경에서 `backend` 폴더의 PowerShell로 실행합니다.

```powershell
.\gradlew.bat clean build
.\gradlew.bat bootRun
```

`Started MockBackendApplication` 이후 **http://localhost:8080/** 에 접속합니다. 기존 서버가 실행 중이면 해당 터미널에서 `Ctrl+C`로 종료한 뒤 다시 실행하세요. `bootRun`이 EXECUTING 상태로 유지되는 것은 서버가 실행 중이라는 뜻입니다.

## 브라우저 테스트 순서

1. 홈페이지에서 **사진 선택**을 누릅니다.
2. JPG, PNG, WEBP, GIF 사진을 선택하고 미리보기를 확인합니다. 장당 5MB, 최대 10장, 합계 18MB까지 선택할 수 있습니다.
3. **3D 모델 생성**을 누릅니다. 업로드 중에는 사진 선택과 중복 생성이 잠깐 비활성화됩니다.
4. 화면의 `WAITING → PROCESSING → COMPLETED` 상태를 확인합니다. Worker 시작 후 약 2초에 PROCESSING, 추가 3초 후 COMPLETED가 됩니다. 업로드 시간은 별도입니다.
5. 완료 메시지와 첫 번째 사진을 사용하는 **Mock 결과**를 확인합니다. 실제 3D 파일 생성이나 다운로드는 이 화면에서 수행하지 않습니다.
6. **다른 사진으로 다시 만들기**를 누르면 새로 시작할 수 있습니다.

API 조회는 1초 간격이며, 작업 ID를 받은 뒤 최대 60초까지 기다립니다. 개별 HTTP 요청 제한은 15초입니다. 실패 시 FAILED와 안내 메시지를 표시하고 다시 시도할 수 있습니다. 네트워크 지연으로 짧은 중간 상태는 보이지 않을 수 있습니다.

## 구조와 기존 API

```text
src/main/
├─ java/com/example/mockbackend/
│  ├─ controller/JobController.java
│  ├─ service/JobServiceImpl.java
│  ├─ service/MockJobWorker.java
│  ├─ repository/MemoryJobRepository.java
│  └─ exception/GlobalExceptionHandler.java
└─ resources/
   ├─ application.properties
   └─ static/
      ├─ index.html       # 사진 선택, 상태, 결과 영역
      ├─ styles.css       # 반응형 화면
      └─ app.js           # 미리보기, 업로드, polling, 오류 처리
```

- Controller가 요청을 받고 Service가 파일과 Job을 저장합니다. 별도 Spring Bean인 MockJobWorker의 `@Async` 메서드가 백그라운드 작업을 수행합니다.
- Repository는 메모리 기반입니다. 서버를 재시작하면 Job 정보는 사라지고 업로드 파일은 디스크에 남습니다.
- `POST /api/jobs`: multipart의 **photos** 필드에 여러 이미지 전송. **202 + 빈 본문 + Location: /api/jobs/{jobId}** 형식을 유지합니다.
- `GET /api/jobs/{jobId}`: 기존 JSON의 **id**, **status** 등을 반환합니다. API 상태 **PENDING**을 브라우저에서 **WAITING**으로 표시합니다.
- `GET /api/jobs/{jobId}/result`: 기존 샘플 GLB 다운로드 API를 유지합니다. 현재 샘플은 0바이트이므로 유효한 3D 모델로 사용할 수 없습니다. Mock 화면의 완료 표시는 이 파일에 의존하지 않습니다.
- 사진 전송에는 FormData를 사용하며 multipart Content-Type의 경계값은 브라우저가 설정합니다. 같은 서버에서 화면과 API를 제공하므로 별도 CORS 설정이 필요 없습니다.
- 서버 업로드 제한은 파일당 5MB, 요청 전체 20MB입니다. 브라우저의 합계 제한을 18MB로 두어 multipart 부가 데이터 여유를 둡니다.
- 없는 페이지/정적 파일은 404, 너무 큰 업로드는 413, 잘못된 요청 방식은 405로 구분합니다. 예상하지 못한 서버 오류만 500으로 처리합니다.

API를 직접 확인하려면 별도 PowerShell 터미널에서 실제 사진 경로로 실행하세요.

```powershell
curl.exe -i -F "photos=@C:\photos\dog1.jpg" -F "photos=@C:\photos\dog2.jpg" http://localhost:8080/api/jobs
curl.exe -i http://localhost:8080/api/jobs/응답받은-jobId
curl.exe -i http://localhost:8080/missing-page
```

## 검증

```powershell
.\gradlew.bat clean build
# Node.js 20 이상이 있을 때 JavaScript 흐름 테스트 (별도 npm 설치 불필요)
node --test src/test/js/mock-mvp.test.cjs
# 실행 중인 로컬 서버와 실제 app.js 흐름을 함께 검증
$env:BESIDE_MVP_URL = 'http://localhost:8080'
node --test src/test/js/mock-mvp.test.cjs
Remove-Item Env:BESIDE_MVP_URL
```

Java 테스트는 홈페이지·정적 파일·404·잘못된 요청·비동기 상태 전이·기존 다운로드 API를 확인합니다. JavaScript 테스트는 작은 DOM 대역에서 실제 app.js를 실행하여 성공·오류·시간 초과·중복 클릭을 확인합니다. 실제 브라우저의 레이아웃 렌더링 검증을 대체하지는 않습니다. 로컬 서버 테스트는 `mvp-probe.png` 테스트 업로드 2개와 메모리 Job을 생성합니다.

별도 `../frontend` 폴더의 Unity 스크립트 틀은 이번 웹 MVP와 독립적이며 변경하지 않았습니다.
