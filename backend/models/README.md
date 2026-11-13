# 전처리 모델 (`backend/models`)

대표 사진 검사(개·고양이 감지)와 배경 제거에 쓰는 ONNX 모델이다. 크기 때문에 **커밋하지 않는다**. 받는 방법은 아래 스크립트이고, 받은 파일은 이 폴더의 `SHA256SUMS` 와 맞아야 설치된다.

```powershell
.\scripts\fetch_models.ps1 -Target C:\beside\models     # Windows. 리포가 OneDrive 안이면 밖에, ASCII 경로로
```

```bash
scripts/fetch_models.sh /opt/beside/models               # Linux·VM. 인자가 없으면 $PREPROCESS_MODELSDIR, 그것도 없으면 backend/models
```

서버는 `preprocess.models-dir`(기본 `models`, `backend/` 기준)에서 읽는다. 다른 폴더에 받았으면 환경변수 `PREPROCESS_MODELSDIR` 로 그 폴더를 가리킨다. 다시 실행하면 해시가 맞는 파일은 그대로 두고, 해시가 다르면 설치하지 않고 실패한다.

| 파일 | 쓰는 곳 | 출처 | 라이선스 | 크기 (bytes) | 확인 |
|---|---|---|---|---|---|
| `yolox_s.onnx` | 개·고양이 감지(COCO 80종, 입력 640×640) — 대표 사진 검사와 크롭 | [Megvii-BaseDetection/YOLOX 릴리스 0.1.1rc0](https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_s.onnx) | Apache-2.0 | 35,858,002 | 공식 해시가 없어 2026-11-04 에 받은 파일의 SHA-256 을 고정 |
| `u2net.onnx` | 배경 제거(U2Net, 입력 320×320) | [danielgatis/rembg 릴리스 v0.0.0](https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2net.onnx) | U-2-Net 은 Apache-2.0, rembg 는 MIT | 175,997,641 | MD5 `60024c5c889badc19c04ad937298a77b` 가 rembg 소스에 고정된 값과 같음(2026-11-04) |

## 입출력 규약

- YOLOX(0.1.1rc0 공식 내보내기 가정): BGR, 0~255 실수, 비율을 유지해 줄인 뒤 **좌상단**에 붙이고 나머지는 114, 출력은 격자·stride 디코딩이 필요한 원시값 `[1, 8400, 85]`(obj·cls 는 sigmoid 적용됨). 실제 사진으로 확인하는 모델 테스트로 확정한다 — **[계획]**(조각 C3).
- U2Net(rembg 와 같게): 320×320 으로 늘림, 최댓값으로 나눔, ImageNet 평균·표준편차, 출력 0번(fused map)을 min-max 정규화.

## 다른 모델로 바꾸기

크기·평균·출력 번호는 설정(`preprocess.detector.*`, `preprocess.segmenter.*`)이다. 예: 업로드 검사가 느리면 `yolox_tiny.onnx`(입력 416), 메모리가 모자라면 `u2netp.onnx`(약 4.7 MB). 바꿀 때는 출처·라이선스를 이 표에 적고, 해시를 `SHA256SUMS` 에, 받는 줄을 두 스크립트에 넣는다. 모델이 바뀌면 `preprocessVersion` 의 모델 해시도 바뀌어 측정이 섞이지 않는다.
