# GLB 3종 gltf-validator·GLB_SPEC 검사 (CHECKLIST S6)

| 항목 | 값 |
|---|---|
| 날짜 | 2026-11-27 (KST 02:4x, UTC 11-26 17:4x) |
| 담당 | BE(검사만. 변환기 코드는 바꾸지 않았다) |
| 코드 커밋 | 검사한 GLB 는 운영 추론 서비스가 만든 것. 검사 스크립트는 이 폴더의 `validate.cjs` |
| 도구 | Khronos glTF-Validator `gltf-validator` 2.0.0-dev.3.10 (npm, Apache-2.0, integrity `sha512-odJ4k0tR…`), Node.js |
| 입력 | 운영 VM `results/<jobId>/base.glb` 3개(아래 표). 입력 사진은 이 기록에 없다 [미확인] |
| 상태 | [실험 결과] |
| 형식 | 개선 실험(exp-NN)이 아니라 검사 기록이다. TEMPLATE 4절 끝의 'GLB_SPEC 검사' 줄을 세 종류 GLB 에 대해 따로 한 것이다 |

## 왜

- GLB_SPEC §1 의 '검증: glTF Validator 오류 0' 이 [계획](exp-02)으로 남아 있었다. 그래서 '앱으로 내보내는 GLB 가 표준에 맞는다' 를 근거 없이 말하고 있었다.
- CHECKLIST S6·3절 3단계 DoD 는 'GLB_SPEC 통과 GLB 3개' 다.
- 정적, 털 맵, 리깅 세 종류는 만드는 코드가 다르다. 정적·털 맵은 `convert_asset.py`, 리깅은 `rig_transfer.py` 가 템플릿 위에 만든다. 그래서 한 종류씩 본다.

## 검사한 파일

| 종류 | 작업 id(앞 8자) | 만든 날 (UTC) | 크기 | sha256(앞 16자) |
|---|---|---|---|---|
| 정적 | `d798b743` | 11-19 07:11 | 1,170,480 B | `eed30b90890d109f` |
| 털 맵(`extras.beside`) | `dd7af771` | 11-21 09:29 | 1,760,968 B | `d35437a201ba30e4` |
| 리깅 + 털 맵 + 클립 5개 | `35b357d7` | 11-26 08:07 | 2,100,924 B | `9048e8d5b3225c82` |

GLB 파일 자체는 리포에 넣지 않았다. AnimalLift 출력의 라이선스(SMAL 파생)를 아직 정하지 않았기 때문이다(CHECKLIST S9).

## 결과 [실험 결과]

| | 정적 | 털 맵 | 리깅 |
|---|---|---|---|
| validator 오류 / 경고 | **0 / 0** | **0 / 0** | **0 / 0** |
| INFO | 0 | 2 (`UNUSED_OBJECT /images/1·2`) | 2 (같음) |
| HINT | 4 (`BUFFER_VIEW_TARGET_MISSING`) | 4 (같음) | 0 |
| 삼각형 / 정점 | 7,774 / 4,310 | 7,774 / 4,310 | 7,774 / 4,310 |
| 이미지 | 몸 1024² | 몸 1024², `hair_length`·`hair_flow` 512² | `uv` 1024², 털 맵 2장 512² |
| 애니메이션 / 스킨 | 0 / 없음 | 0 / 없음 | 5 (Idle·Walk·TailWag·HeadTilt·Sit) / 있음 |
| `extras.beside` | 없음 | `hairLengthImage`·`hairFlowImage`·`furLength` | 같음 |

GLB_SPEC 기준(이 폴더의 `validate.cjs` 가 확인)은 세 파일 모두 통과다.
- validator 오류 0
- 파일 ≤ 10 MB [제안값]
- 버퍼 1개, 외부 uri 없음
- 확장 없음
- 삼각형 ≤ 20,000 [제안값]
- 텍스처 ≤ 2048² [제안값]
- 털 맵을 가리키는 텍스처 없음

- **INFO `UNUSED_OBJECT`** 는 설계 그대로다. GLB_SPEC §4.1 이 '어느 texture 도 털 맵 이미지를 가리키지 않는다' 고 정했고, 앱은 `extras.beside` 의 이미지 번호로 읽는다.
- **HINT `BUFFER_VIEW_TARGET_MISSING`** 은 `convert_asset.py` 가 정점·인덱스 bufferView 에 `target`(34962/34963)을 적지 않아서 나온다. 리깅본은 템플릿의 bufferView 를 써서 이 HINT 가 없다.
  - 표준 위반이 아니고 로드에는 영향이 없다.
  - 고칠지는 [계획] 이다(변환기 한 줄과 시험).
- 같은 날 로컬 공유 뷰어(model-viewer 3.5.0)가 리깅 GLB 를 그리고 'TailWag' 클립을 재생했다(W7 제품 로그 확인 때, 로컬 mock 서버).
- Unity glTFast 로드는 이 검사에 들어 있지 않다. 에디터 확인 때 함께 본다 [계획].

## 데이터

- `static-d798b743.glb.report.json`·`furmaps-dd7af771.glb.report.json`·`rigged-35b357d7.glb.report.json`: validator 원 보고서
- `summary.json`: 위 표의 값과 GLB_SPEC 검사 결과
- `validate.cjs`: 다시 돌리는 법
  - `npm install gltf-validator@2.0.0-dev.3.10` 을 빈 폴더에 한 뒤 실행한다.
  - 명령: `node validate.cjs <그 폴더>/node_modules/gltf-validator <출력 폴더> <glb>...`
