# Beside Again

반려동물 사진을 업로드하고 작업 진행과 Mock 결과를 확인하는 캡스톤 프로젝트입니다.

```text
Beside/
├─ backend/    Spring Boot 3.2 / Java 17 웹 Mock MVP 및 API
└─ frontend/   Unity 클라이언트용 C# 스크립트 틀
```

현재 브라우저 Mock MVP는 `backend`에서 실행합니다. `frontend`는 Unity 프로젝트가 아니라 이후 Unity로 가져갈 스크립트 구조입니다. 실제 AI 3D 생성과 AR 배치는 아직 구현되지 않았습니다.

## 브라우저 Mock MVP 실행

```powershell
cd backend
.\gradlew.bat clean build
.\gradlew.bat bootRun
```

서버가 시작되면 **http://localhost:8080/** 에 접속해 사진 선택 → 업로드 → `WAITING → PROCESSING → COMPLETED` → 대표 사진을 활용한 Mock 결과를 확인할 수 있습니다. 서버 실행 방법과 API·테스트 상세는 [backend/README.md](backend/README.md)를 참고하세요.

## Unity 클라이언트

`frontend/Assets/Scripts`에 API, 작업 상태, UI 연결을 위한 최소 C# 클래스 틀이 있습니다. 아직 실제 Unity 프로젝트, Android 갤러리 연동, API 구현, AR 화면은 없습니다. 파일 역할과 Unity 프로젝트로 옮기는 방법은 [frontend/README.md](frontend/README.md)에 정리했습니다.
