# 시험 사진 출처

전처리(대표 사진 검사·크롭·배경 제거)를 실제 모델로 확인할 때 쓰는 사진이다. 모델 테스트(`@Tag("models")`)와 폴더 일괄 도구가 읽는다.

- 2026-11-04 사용자 지시("너가 찾아서 넣어줘", "일단 개 사진만")로 Wikimedia Commons 의 **CC0** 사진만 골랐다. 팀이 찍은 사진은 아니다. 고양이·동물 없음·흐림 사진은 아직 없다.
- 넣은 방법: Commons 의 긴 변 960~1280px 섬네일을 받아 새 비트맵에 다시 그려 JPEG(품질 85)로 저장했다. 그래서 EXIF·GPS·XMP 는 남지 않는다(파일 안에 `Exif`·`GPS`·XMP 문자열 없음 확인). 긴 변 최대 1200px, 장마다 300 KB 이하.
- CC0 라 표기 의무는 없지만, 다시 받을 수 있게 원본 페이지와 작성자를 적는다.

| 파일 | 크기 | 내용 | 기대 판정 | 원본 (Commons) | 작성자 | 라이선스 |
|---|---|---|---|---|---|---|
| `dog.jpg` | 800×1200 | 호수 앞에 앉은 블랙앤탄 하운드 한 마리, 몸 전체 | 통과 | [A dog sitting outside near a lake.jpg](https://commons.wikimedia.org/wiki/File:A_dog_sitting_outside_near_a_lake.jpg) | Kristin Hardwick | CC0 1.0 |
| `dog-single-black.jpg` | 800×1200 | 잔디에 앉은 검은 카렐리안 베어 독 한 마리(가슴만 흰색) | 통과 (검은 털이 어둡다고 거절되면 안 됨) | [Karjalankarhukoira, Karelian Bear Dog sitting.jpg](https://commons.wikimedia.org/wiki/File:Karjalankarhukoira,_Karelian_Bear_Dog_sitting.jpg) | Uusijani | CC0 1.0 |
| `dog-single-edge.jpg` | 1200×675 | 풀밭의 크림색 개 한 마리, 몸이 왼쪽 끝에서 잘림 | 통과 (크롭이 잘린 쪽으로 치우치는 경우) | [Blonde dog in grass (Unsplash).jpg](https://commons.wikimedia.org/wiki/File:Blonde_dog_in_grass_(Unsplash).jpg) | Razvan Antonescu | CC0 1.0 |
| `dogs-two-swimming.jpg` | 1200×675 | 구명조끼를 입고 헤엄치는 프렌치 불도그 두 마리 | `TOO_MANY_ANIMALS` | [Two French bulldogs swimming in life jackets.jpg](https://commons.wikimedia.org/wiki/File:Two_French_bulldogs_swimming_in_life_jackets.jpg) | W.carter | CC0 1.0 |

"기대 판정"은 사람이 사진을 보고 적은 기대다. 모델로 실제 판정을 확인하는 것은 조각 C4(`PhotoCheckModelTest`)에서 한다.
