# Beside Unity 클라이언트 기본 틀

이 폴더는 Unity로 가져갈 C# 스크립트 틀입니다. 아직 실행 가능한 Unity 프로젝트가 아니며, 사진 선택·API 통신·polling·다운로드·UI·AR 기능은 구현하지 않았습니다.

```text
Beside/
├─ backend/
└─ frontend/
   ├─ Assets/
   │  └─ Scripts/
   │     ├─ Api/
   │     │  └─ BesideApiClient.cs
   │     ├─ Job/
   │     │  └─ JobManager.cs
   │     └─ UI/
   │        └─ MainUIController.cs
   └─ README.md
```

| 스크립트 | 역할 |
|---|---|
| `BesideApiClient.cs` | 일반 C# 클래스. UnityWebRequest 기반 업로드·작업 조회·결과 다운로드의 경계와 코루틴 메서드 시그니처 정의 |
| `JobManager.cs` | MonoBehaviour. API Client 호출, 작업 상태 관리, 2초 간격 polling 및 최대 60초 대기, 취소와 다운로드 완료 전달 담당 |
| `MainUIController.cs` | MonoBehaviour. 사진 선택·생성 요청 버튼과 상태·오류·다운로드 완료 표시 연결 담당 |

현재 API 메서드는 호출 시 `NotImplementedException`을 발생시킵니다. JobManager와 UI 메서드도 TODO만 있는 틀이며, Inspector 설정값은 아직 실행 로직에 사용되지 않습니다. 통신이나 작업 성공을 흉내 내지 않습니다.

API 연동은 기존 Backend 형식을 기준으로 구현할 예정입니다.

- `POST /api/jobs`: 같은 `photos` 이름으로 여러 파일 업로드. `202 Accepted`의 빈 본문 대신 `Location` 헤더에서 jobId 추출
- `GET /api/jobs/{jobId}`: JSON의 `id`, `status` 사용
- `GET /api/jobs/{jobId}/result`: 완료된 작업의 GLB 다운로드
- 서버 내부 경로인 `resultPath`는 다운로드 URL로 사용하지 않음

실제 Unity 프로젝트로 옮기는 방법:

1. 사용할 Unity 버전을 정하고 Unity Hub에서 실제 Unity 프로젝트를 별도로 생성합니다.
2. 이 폴더의 `Assets/Scripts` 내용을 새 프로젝트의 `Assets/Scripts`로 복사합니다. Unity가 필요한 `.meta` 파일을 생성하도록 합니다.
3. 씬에 빈 GameObject를 만들고 `JobManager`와 `MainUIController`를 추가합니다. `BesideApiClient`는 일반 C# 클래스이므로 컴포넌트로 붙이지 않습니다.
4. MainUIController의 `Job Manager` 참조에 해당 컴포넌트를 연결합니다.
5. JobManager의 `Base Url`을 Inspector에서 설정합니다. 같은 PC의 에디터에서는 `http://localhost:8080`, Android 실제 기기에서는 같은 Wi-Fi의 개발 PC 주소(예: `http://192.168.0.10:8080`)를 사용합니다.
6. MainUIController의 `Editor Test Image Paths`에 테스트 사진의 파일 경로를 입력합니다.
7. 이후 단계에서 API 구현 → polling → UI 연결 → 사진 선택 → 다운로드 완료 처리를 연결합니다. 현재 Play 모드에서는 이 흐름이 동작하지 않습니다.

Android 갤러리 선택, HTTP 허용·인터넷 권한 등 플랫폼 설정은 이후 단계에서 진행합니다. 외부 플러그인, UI 패키지, 씬, `Packages`, `ProjectSettings`는 포함하지 않았으며 특정 Unity 버전도 고정하지 않았습니다.

현재 Backend의 샘플 GLB는 0바이트이므로 실제 모델 로드 검증 전에 유효한 파일 준비가 필요합니다. 이번 작업에서는 Backend 파일을 변경하지 않습니다.
