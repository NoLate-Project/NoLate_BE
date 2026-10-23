# P2-2 버스 도착 관측과 저장 경로 매핑

## 2026-10-21 공식 노선 대조 진전과 남은 연결

실제 ODsay `maasRP` 버스 구간이 `way`/`stationCount`를 생략해도, 원본 local BIS 노선·정류장 ID,
ARS, 순서, 좌표가 온전하면 `JourneyBusRideMapping`은 서울시 공식 순서 있는 정류장 목록과 노선 형상을
대조할 수 있게 했다. 공식 구간이 유일하고 같은 방면이어야 하며, 원본에 방면/정류장 수가 **있는데**
공식 자료와 충돌하면 차단한다. 누락된 값을 원본 ODsay 값으로 꾸미거나 기존 도착정보
`SAVED_ROUTE_METADATA` 계약에 주입하지 않는다. 이 단계의 검증은 합성 ODsay-shaped fixture와
공식 자료 형태의 fixture를 사용한 서버 단위 테스트이며, 실제 7016 응답 또는 시뮬레이터 E2E가 아니다.

`DEVICE` 준비 전에 `JourneyBusDeviceAdmissionVerifier`가 공식 정류장·형상을 한 번 더 조회해
저장 BUS와 유일 대조하고 정적 방면/정류장 수를 별도 응답으로 전달한다. FE/native는 원본
누락 값에 한해서만 이를 적용하며 `serviceState=UNKNOWN`을 `operating`으로 바꾸지 않는다.
`not_operating`, 형상·순서 불일치, 차량/GPS 근거 부족은 차단한다. 운영의
`JOURNEY_OFFICIAL_BUS_ADMISSION_ENABLED`는 기본 OFF이고, 기기 길안내를 운영에서 켠다면
startup guard가 이 플래그도 필수로 확인한다. 이는 실제 운행·탑승 증명이 아니다.
실제 ODsay 성공 경로의 독립 시뮬레이터 전체 E2E, 실제 APNs, 마지막 실기기 GPS는 각각
별도 검증해야 한다.
검증: BE 전체 `./gradlew test` 통과, 추가된 읽기 전용 ride 서비스/매핑 19 tests 통과,
FE ODsay 누락 필드·기존 엄격 binding 계약 57 tests 통과. 실제 경로 준비가 아직 차단되므로
이번 단계에서 성공한 사용자 여정 E2E 또는 실기기 검증으로 기록하지 않는다.

2026-10-07 후속: 첫 실제 시험 대상은 **7016 상명대 방면 서울역→경복궁역**으로 선정했다.
서울시 공식 공개 화면에서 정류장 순서 44~48, 노선 `100100447`, 차량 source timestamp/방면과 형상을 읽기 전용 조회했다.
이는 운영 API adapter 또는 ODsay 실제 응답 crosswalk 검증 완료가 아니다. test source에 `Seoul7016PreviewFixture`를 추가하고
현재 미지원 provider/없는 도보 geometry를 합성으로 채우지 않은 채 차단했다. 관련 BE 20 tests PASS.
FE 독립 시뮬레이터에서도 동의/GPS 변경 후 준비·출발·push 0, 상세 render 수정·미확인 시간 표시와 잠금 카드 없음까지 확인했다.
형상의 `sectord`를 정류장 `seq`와 동일시하면 다른 위치가 선택됨을 관측했으므로 실제 link/section 매핑이 선행돼야 한다.
상세 증거는 FE `docs/journey-seoul-7016-validation-20261007.md`를 참조한다. 실제 자동 탑승·하차/완주·음성/APNs 노선 안내는 미검증이다.

2026-10-04: 소유 자동 안내 세션에 대한 읽기 전용 버스 도착 API와 순수 매처를 추가했다.
후속 P2-2b에서 저장 경로 메타데이터 기반 crosswalk와 선택 경로 대조를 추가했다.
실제 공급자 원문/운행 정확도와 운영 일정 통합은 아직 검증하지 않았다.

## 계약

`POST /api/schedules/{scheduleId}/automatic-journey/{sessionId}/bus-arrivals`
입력: generation, routeRevision, clientOwnerId, routeInputDigest, query.
query: namespace=SEOUL_BUS, 5자리 arsId, providerRouteId, 명시적 directionLabel, legIndex=1.
서버 로그인 actor와 일정 접근권, 세션 소유/TTL/최신 세대/저장 경로 fingerprint/첫 도보 다음 BUS를 조회 전후 검사한다.
읽기 경로는 자동 출발/탑승으로 상태를 변경하거나 push·Live Activity를 발송하지 않는다. 기존 운영 기능 플래그는 default off 유지.
서울은 기존 TransitArrivalService의 명시적인 ODSAY_CID=1000 매핑을 사용하며 이름 fallback/타 지역 fallback을 하지 않는다.
provider deadline·rate-limit·결과 상한 10개를 유지한다. 상한 밖 노선은 이름만으로 대신 선택하지 않는다.

순수 매처는 원문 arrival provider=seoul-bus, BUS, ARS·공급자 route ID·방면 label이 요청과 모두 같은 항목만
P2-1 신선도 어댑터로 검사한다. fresh 항목 중 가장 빠른 도착을 표시하고 mismatch/empty/stale/unverified를 구별한다.
순수 매처의 증거 `REQUEST_IDENTIFIERS_ONLY`는 저장 경로 매핑의 인증이 아니다.
서비스는 아래 crosswalk 검사까지 통과한 경우에만 `SAVED_ROUTE_METADATA`를 반환한다.
도착 예정 차량 ID는 사용자의 탑승 차량이 아니다. confirmedBoarding=false / remainingStopsToAlight=null.
별도 차량 위치 증거 없이 RIDING·하차·자동 발화로 승격하지 않는다.

서울 XML busRouteId·각 vehId 슬롯, TAGO routeid를 DTO에 보존한다.
도착 피드에서 제공되지 않는 TAGO vehicle ID/원천 timestamp는 null/receipt-only 그대로다.
TMAP/ODSAY route ID와 지역 BIS route ID의 namespace를 숫자/노선명만으로 혼합하지 않는다.
명세 참고: [서울 고유번호별 정류소 정보](https://data.seoul.go.kr/dataList/OA-15331/L/1/datasetView.do),
[TAGO 도착정보](https://www.data.go.kr/data/15098530/openapi.do).
실제 공급자 payload 호출/정확도는 이번에 검증하지 않았다.

## 검증

P2-2a 당시 매처/서비스/인증·지연응답 경계 12개 + 관련 회귀 합계 93개 PASS.
fixture harness는 test 소스의 MockMvc/메모리 repo/합성 인증/합성 arrivalSource만 사용한다.
실제 DB·SpringApplication·공급자·push API를 호출하거나 application 환경/.env를 로드하지 않는다.
loopback 15543/일정9001 전용이며 Authorization 헤더/다른 리소스를 거부한다.
FRESH, WRONG_ROUTE, WRONG_DIRECTION, STALE, EMPTY fixture를 독립 시뮬레이터 GPS 관측과 조합해 확인했다.
최종 상태는 FE [P2-2 검증 기록](../../../NoLate_FE/docs/journey-engine-p2-2-validation.md)을 참조한다.

## P2-2b 저장 경로 crosswalk

읽기 전용 `POST .../bus-arrivals/binding`은 generation/revision/clientOwnerId/routeInputDigest만 받는다.
현재 actor/로그인 세대, 소유 일정, 최신 준비 세션, TTL, 운영 플래그, 저장 경로 fingerprint를
검사한 뒤 서버가 저장된 구간에서 query와 boarding occurrence를 결정한다. 공급자를 호출하지 않는다.

지원은 첫 WALK 다음 BUS, ODsay/api, 서울 도시 1000, BIS 공급자 4 또는 10의 명시적 메타데이터에 한정한다.
ODsay busID 대신 busLocalBlID를 보존한 localRouteId를 서울 도착 API의 busRouteId와 연결한다.
명세상의 namespace/필드는 [ODsay 공식 참조](https://lab.odsay.com/guide/releaseReference?platform=web)를 참고했다.
실제 공급자에 이 ID가 존재한다는 외부 인증이나 사용자의 탑승 증거는 아니다.

필수 근거:

- leg ID와 raw navigationEvidence.busIdentifiers가 일치. 표시용 이름/코드에서 ID를 복원하지 않는다.
- 승차 ARS는 NNNNN 또는 NN-NNN만 허용. 임의 숫자 추출·자리 채움·도시 추정은 하지 않는다.
- 원본 stopOccurrences 전 행의 정수 sourceIndex/증가 providerSequence, 유효 좌표/지역 stop ID/ARS/namespace.
- 원본 stationCount와 행 개수 일치, 첫 정류장 ID와 승차 ID 일치, startCoord와 30m 이내.
- 승차 ID가 원본 배열에 반복되면 별도 provider occurrence 조회 전까지 차단.
- 방면은 원문 navigationEvidence.directionName 필수. endName이나 화면용 방면 fallback은 불허.
- 서로 다른 경로 배열이 함께 저장됐거나 wrapper 출처가 충돌하면 차단.

원문 메타데이터가 부족한 과거 저장 경로, TMAP, 타 지역/TAGO, 방면 누락, 혼합 namespace,
반복 승차 정류장 등은 미지원. 지원율을 높이기 위한 실제 공급자 route/station 조회는 이번 범위에 없다.
도착 요청의 query를 서버 canonical query와 비교하고, fetch 후에도 전체 세션/저장 경로 fence와
crosswalk를 다시 검사한다. 이름 기반 재시도나 미지원 provider로 fallback하지 않는다.

P2-2b 매핑 경계 테스트 9개를 추가해 관련 회귀 102개 PASS. 여러 malformed 변형은 각 테스트 안에서 반복 검사한다.
fixture 서버는 별도 합성 ODsay-shaped 저장 메타데이터와 FRESH/STALE/방면 오류 피드만 사용한다.
MAPPED/UNSUPPORTED/CHANGED_ROUTE 제어는 test-source loopback harness에만 존재하며 운영 controller가 아니다.

## 다음

FE의 실제 선택 경로는 메타데이터 미리보기만 제공하는 LOCAL_ONLY이며 운영 조회하지 않는다.
합성 GPS 형상과 ODsay 매핑 fixture는 별개임을 화면에 표시한다. 이 시험으로 실제 ODsay WALK 형상 적격성이나
서버 저장 경로와 native route digest의 전체 일치까지 증명한 것으로 취급하지 않는다.
P2-3에서 공통 안내 상태를 실제 일정 상세/ActivityKit에 연결한다. 실제 APNs·무음/잠금·특정 차량 탑승 및 현장 신뢰도는 별도 실기기 게이트다.
