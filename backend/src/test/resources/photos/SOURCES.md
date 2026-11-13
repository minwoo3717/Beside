# 시험 사진 출처

전처리(대표 사진 검사·크롭·배경 제거)를 실제 모델로 확인할 때 쓰는 사진이다. 모델 테스트(`@Tag("models")`)와 폴더 일괄 도구가 읽는다.

- 2026-11-04 사용자 지시("너가 찾아서 넣어줘", "일단 개 사진만", 이어서 "동물이 없는 사진이랑 흐린 사진")로 Wikimedia Commons 의 **CC0** 사진만 골랐다. 팀이 찍은 사진은 아니다. 고양이 사진은 아직 없다.
- 넣은 방법: Commons 의 긴 변 960~1280px 섬네일을 받아 새 비트맵에 다시 그려 JPEG(품질 85)로 저장했다. 그래서 EXIF·GPS·XMP 는 남지 않는다(파일 안에 `Exif`·`GPS`·XMP 문자열 없음 확인). 긴 변 최대 1200px, 장마다 300 KB 이하.
- CC0 라 표기 의무는 없지만, 다시 받을 수 있게 원본 페이지와 작성자를 적는다.

| 파일 | 크기 | 내용 | 기대 판정 | 원본 (Commons) | 작성자 | 라이선스 |
|---|---|---|---|---|---|---|
| `dog.jpg` | 800×1200 | 호수 앞에 앉은 블랙앤탄 하운드 한 마리, 몸 전체 | 통과 | [A dog sitting outside near a lake.jpg](https://commons.wikimedia.org/wiki/File:A_dog_sitting_outside_near_a_lake.jpg) | Kristin Hardwick | CC0 1.0 |
| `dog-single-black.jpg` | 800×1200 | 잔디에 앉은 검은 카렐리안 베어 독 한 마리(가슴만 흰색) | 통과 (검은 털이 어둡다고 거절되면 안 됨) | [Karjalankarhukoira, Karelian Bear Dog sitting.jpg](https://commons.wikimedia.org/wiki/File:Karjalankarhukoira,_Karelian_Bear_Dog_sitting.jpg) | Uusijani | CC0 1.0 |
| `dog-single-edge.jpg` | 1200×675 | 풀밭의 크림색 개 한 마리, 몸이 왼쪽 끝에서 잘림 | 통과 (크롭이 잘린 쪽으로 치우치는 경우) | [Blonde dog in grass (Unsplash).jpg](https://commons.wikimedia.org/wiki/File:Blonde_dog_in_grass_(Unsplash).jpg) | Razvan Antonescu | CC0 1.0 |
| `dogs-two-swimming.jpg` | 1200×675 | 구명조끼를 입고 헤엄치는 프렌치 불도그 두 마리 | `TOO_MANY_ANIMALS` | [Two French bulldogs swimming in life jackets.jpg](https://commons.wikimedia.org/wiki/File:Two_French_bulldogs_swimming_in_life_jackets.jpg) | W.carter | CC0 1.0 |
| `dog-blurry.jpg` | 1200×800 | 움직이다 흔들린 치와와 얼굴 클로즈업(실내, 어두운 조명) | `IMAGE_TOO_BLURRY` | [- moving dog in low light. Kind of like a paintin (2995566101).jpg](https://commons.wikimedia.org/wiki/File:-_moving_dog_in_low_light._Kind_of_like_a_paintin_(2995566101).jpg) | cogdogblog | CC0 1.0 |
| `no-animal-room.jpg` | 1200×800 | 가죽 소파·의자·화분이 있는 실내, 동물·사람 없음 | `NO_ANIMAL_DETECTED` | [Take a seat (Unsplash).jpg](https://commons.wikimedia.org/wiki/File:Take_a_seat_(Unsplash).jpg) | Sabri Tuzcu | CC0 1.0 |

"기대 판정"은 사람이 사진을 보고 적은 기대다. 모델 테스트 `PhotoCheckModelTest`(C4, 2026-11-05)가 이 기대를 고정했다(모델이 있는 PC 에서만 돈다).

## 첫 확인 (2026-11-04, 개발 PC, 일회성 실행) [실험 결과]

지금 코드의 `PhotoCheck`(제안값 `Rules.DEFAULT`, YOLOX-S `yolox_s#c5c2d13e`, 디코딩 긴 변 1600)로 한 번 돌린 결과다. 테스트로는 아직 남기지 않았다.

| 파일 | 실제 판정 | 감지 | 밝기(90백분위) | 선명도(라플라시안 분산) | 기대와 같은가 |
|---|---|---|---|---|---|
| `dog.jpg` | 통과 | dog 0.93 | 166 | 835.2 | 같음 |
| `dog-single-black.jpg` | 통과 | dog 0.92 | 186 | 1566.8 | 같음 |
| `dog-single-edge.jpg` | 통과 | dog 0.69 | 160 | 1952.8 | 같음 |
| `dog-blurry.jpg` | `IMAGE_TOO_BLURRY` | cat 0.75 | 170 | 11.1 | 같음 (단, 개를 고양이로 감지) |
| `no-animal-room.jpg` | `NO_ANIMAL_DETECTED` | 없음 | - | - | 같음 |
| `dogs-two-swimming.jpg` | **통과** | dog 0.71, dog 0.48 | 151 | 1055.0 | **다름**: 둘째 개 점수 0.48 이 "다른 동물" 기준 0.50 에 못 미쳐 한 마리로 셈 |

두 마리 사진이 통과한 것은 제안값의 잘못 통과 사례다. 조정 여부는 조각 G2(임계값 조정)에서 정한다. → 2026-11-05 사용자 결정으로 둘째 동물 기준을 0.40 으로 낮췄고, 이 사진은 이제 `TOO_MANY_ANIMALS` 다([docs/preprocess/2026-11-05_photo-check.md](../../../../../docs/preprocess/2026-11-05_photo-check.md)).
