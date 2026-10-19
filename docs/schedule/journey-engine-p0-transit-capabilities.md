# 길안내 엔진 P0 교통정보 능력 검증

검증일: 2026-10-04

## 판정

현재 도착정보 연동은 대기시간 표시의 기반으로 재사용할 수 있다. 그러나 도착정보 응답만으로 사용자가 탑승한 차량, 현재 통과 정류소, 하차까지 남은 정류소를 확정할 수는 없다. 자동 길안내에는 별도의 경로 매칭과 관측 신뢰도 판정이 필요하다.

소스와 합성 fixture 계약 검증은 **PASS**다. 실제 공급자 응답의 지원 노선·원천 지연·차량 식별 안정성 및 실제 이동 정확도는 **NOT VERIFIED**다. 이 문서는 P0 전체 완료나 내비게이션 E2E 통과를 의미하지 않는다.

이번 검증은 `.env`, 공급자 키, 운영 DB를 읽지 않았다. 실제 교통 API를 호출하지 않았으며, HTTP 테스트는 임의 포트의 `127.0.0.1` 합성 서버만 사용했다. 제품 서비스 코드는 변경하지 않았다.

## 공급자별 현재 계약

아래 PASS는 **기존 어댑터가 해당 필드를 올바르게 변환한다는 fixture 검증**이다. 공급자 전체 지역·운행시간의 제공 보장을 뜻하지 않는다.

| 능력 | 서울 지하철 | 서울 버스 | TAGO 버스 |
| --- | --- | --- | --- |
| 대기초와 도착 예상 절대시각 변환 | PASS `barvlDt` → `waitSeconds` | PASS `traTime1/2` → `waitSeconds` | PASS `arrtime` → `waitSeconds` |
| 원천 시각 보존 | PASS `recptnDt` → `sourceUpdatedAt` | PASS `mkTm` → `sourceUpdatedAt` | 현재 어댑터에 없음, receipt only |
| 원천 시각 누락 때 보수적 분류 | PASS receipt only | PASS receipt only | PASS 항상 receipt only |
| 노선·역명 표시 | PASS | PASS | PASS |
| 방면 정보 | `updnLine`, 종착역 표시, 불일치 fallback 주의 | `adirection` 표시, 실제 운행 매칭 미검증 | **현재 `direction`은 `routetp` 노선유형** |
| 조회 정류소 식별자 | 현재 DTO에 지하철역 ID 보존 없음 | `arsId` 보존 | `cityCode`, `nodeId`, `arsId` 보존 |
| 노선 ID·열차/차량 ID의 정규화 보존 | 현재 없음 | 현재 없음 | 현재 없음 |
| 추적 차량 좌표·현재 정류소 순번 | 현재 없음 | 현재 없음 | 현재 없음 |
| 사용자가 탑승한 차량 판정 | NOT VERIFIED | NOT VERIFIED | NOT VERIFIED |
| 탑승 중 하차까지 남은 정류소 | 현재 계약으로 제공하지 않음 | 현재 계약으로 제공하지 않음 | `remainingStops`를 이 의미로 사용하면 안 됨 |
| 실제 연동 권한·운행 노선별 품질 | NOT VERIFIED | NOT VERIFIED | NOT VERIFIED |

서울 지하철 공식 명세에는 `btrainNo`와 원천 생성 시각 `recptnDt`가 있다. 현재 `TransitArrivalDto`에는 열차 번호가 보존되지 않는다. 향후 필드를 추가하더라도 열차 번호의 유효 범위와 재사용 조건을 확인해야 한다. [서울 지하철 실시간 도착정보 명세](https://data.seoul.go.kr/dataList/openApiView.do?infId=OA-12764&srvType=A)

서울 버스의 현 어댑터는 정류소별 도착정보를 조회한다. 차량 위치 피드 연동을 이미 구현한 것으로 간주하지 않는다. [서울 고유번호별 정류소 조회 안내](https://data.seoul.go.kr/dataList/OA-15331/L/1/datasetView.do)

## 새 엔진에서 반드시 분리할 의미

### 1 도착 대기 정류소 수와 하차 정류소 수

TAGO `arrprevstationcnt`는 **조회 정류소로 오고 있는 버스가 몇 정류소 전인지** 나타낸다. 현재 코드에서는 이 값을 `TransitArrivalDto.remainingStops`로 변환한다. 사용자가 타고 있는 버스에서 목적 하차 정류소까지 남은 개수와는 다르다. 공식 명세의 `routetp`도 방면이 아니라 노선유형이다. [TAGO 버스도착정보](https://www.data.go.kr/data/15098530/openapi.do)

새 길안내 계약은 다음처럼 분리한다.

- `incomingVehicleStopsAway`: 정류소에 접근 중인 차량의 대기 정보.
- `remainingStopsToAlight`: 사용자의 탑승 경로, 순서가 있는 정류소 목록, 현재 진행 위치로 계산한 하차 정보.
- `routeType`: 일반·마을버스 등 유형.
- `directionEvidence`: 선택 경로와 실제 운행 방향의 명시적 일치 여부. 유형이나 비어 있는 방면을 일치로 취급하지 않는다.

두 stop count는 서로 대입하지 않는다. 하차 count를 계산할 증거가 없으면 `null`과 `위치 확인 중` 상태를 사용하며, 사용자의 단계 확인 버튼으로 보완하지 않는다.

### 2 수신 시각과 정보 생성 시각

`observedAt`은 NoLate의 응답 수신·변환 시각이다. 원천의 최신성 증명이 아니다. `realtime=true`, 미래의 `expectedAt`, `PROVIDER_SOURCE_TIMESTAMP` enum 단독으로도 최신성이 확정되지 않는다. enum은 시각의 **출처**만 말한다.

예를 들어 공급자가 12:00:00에 생성한 대기 120초를 12:00:30에 받았다면 예상 도착은 12:02:00이며, 수신 시점의 잔여 시간은 90초다. 12:02:30으로 새로 계산하거나 여전히 정확히 2분 남았다고 발화하면 안 된다. 서울시도 원천 생성부터 제공까지의 시차를 고려하도록 안내한다. [서울 지하철 데이터 지연 안내](https://data.seoul.go.kr/dataList/catalogView.do?currentPageNo=1&infId=OA-12764&srvType=A)

행동을 유도하는 음성·알림은 최소한 다음을 통과해야 한다.

1. 공급자 원천 시각 존재와 파싱 성공.
2. 현재 평가 시점 기준 유효한 최대 정보 나이와 미래 clock skew 범위.
3. 이미 지나간 도착 예측인지 확인.
4. 선택 노선·조회 정류소·방면·운행 종별과의 명시적 일치.
5. 현재 경로 revision 및 활성 구간과의 일치.
6. 같은 후보·같은 이벤트의 중복/역전/지연 발화 방지.

실시간 feed가 위 조건을 만족하지 못하면 대기정보 품질을 낮춰 표시한다. 수신 시간을 새 원천 시각으로 만들거나 타이머만으로 차량 진행을 확정하지 않는다. 기기 위치로 판단 가능한 구간까지 함께 정지시킬 필요는 없으며, 위치 품질과 교통정보 품질은 분리한다.

기존 `FirstBoardingRealtimeOverlay`에는 원천 시각/방면/종착역/열차 종별 검증이 있다. 하지만 이는 출발 전 ETA 보정 경로다. 새 길안내 어댑터가 `TransitArrivalDto`를 직접 사용할 때 자동으로 적용되는 공통 보안막은 아니다. 해당 검증을 재사용 가능한 경계로 옮기거나, 새 계약에서 동등한 조건을 명시적으로 검사해야 한다.

### 3 표시용 방면 fallback과 내비게이션 방면 증거

`SeoulTransitArrivalClient.filterSubwayDirection`는 요청한 방향에 맞는 결과가 없으면 전체 도착 목록을 반환한다. 따라서 `UP` 요청에 `외선`만 존재하는 fixture도 결과가 반환된다. 표시 호환 동작 자체를 이번 P0에서 바꾸지 않았으며, 새 엔진이 API 조회 성공을 방면 일치로 오해하지 않도록 회귀 테스트로 고정했다.

내비게이션에는 `MATCHED`, `MISMATCHED`, `UNKNOWN` 등 명시적인 결과가 필요하다. 종착역이 하차역보다 앞서는 단축 운행, 일반/급행 정차역 차이, 같은 이름의 반대편 정류소, 환승 다음 구간도 별도로 검증한다.

## 테스트 결과

추가한 테스트 파일:

`src/test/kotlin/com/noLate/transit/infrastructure/JourneyTransitCapabilityContractTest.kt`

신규 7개 테스트는 아래 계약을 검증한다.

1. 지하철 120초 예측은 수신 시각이 아니라 공급자 시각에 고정된다.
2. 오래된 원천 정보는 새로 수신해도 최신 예측으로 바뀌지 않는다.
3. 누락되거나 잘못된 지하철 원천 시각은 receipt only로 유지된다.
4. 지하철 표시 fallback에 반대 방향이 포함될 수 있음을 명시한다.
5. 서울 버스 원천 시각 변환과 onboard stop count 부재를 확인한다.
6. 서울 버스 원천 시각 누락이 수신 시각으로 승격되지 않는다.
7. TAGO 한 정류소 전 값과 노선유형 값이 기존 DTO에서 가진 의미를 확인한다.

이 테스트는 새 내비게이션의 하차 판단을 구현하지 않는다. 현재 입력 계약의 능력과 한계를 고정하는 P0 특성화 테스트다.

실행 명령:

```sh
./gradlew test --offline --no-daemon \
  --tests 'com.noLate.transit.infrastructure.JourneyTransitCapabilityContractTest' \
  --tests 'com.noLate.transit.infrastructure.SeoulTransitArrivalClientTest' \
  --tests 'com.noLate.transit.infrastructure.TagoTransitArrivalClientTest' \
  --tests 'com.noLate.eta.application.transit.FirstBoardingRealtimeOverlayTest'
```

| 테스트 묶음 | 결과 |
| --- | --- |
| 신규 길안내 데이터 계약 | 7 PASS |
| 서울 버스·지하철 어댑터 | 15 PASS |
| TAGO 어댑터 | 4 PASS |
| 기존 첫 승차 최신성·방면 보정 | 12 PASS |
| 합계 | 38 PASS, 0 failures, 0 errors, 0 skipped |

Gradle `test` profile 및 합성/모의 객체를 사용했으며, Spring 운영 컨텍스트나 실제 공급자 API는 구동하지 않았다. 결과 XML은 `build/test-results/test/TEST-*.xml`, HTML은 `build/reports/tests/test/index.html`에서 확인할 수 있다. 이 묶음은 백엔드 계약 테스트이며 시뮬레이터 화면 E2E가 아니다.

## 실데이터 검증 전 남은 조건

다음은 로컬 fixture로 대체할 수 없는 P0 데이터 게이트다.

1. 승인된 개발용 공급자 자격증명 주입 경로를 별도로 확보한다. `.env`를 읽거나 값이 로그에 노출되는 방법을 사용하지 않는다.
2. 첫 버스 검증 노선의 순서 있는 정류소 목록, 정류소 좌표, 노선 geometry 출처, 왕복·순환 방향을 검증한다. 경로의 단순 직선 연결을 도로 경로로 간주하지 않는다.
3. 차량/열차 식별자를 보존할 수 있는 응답과 유효 범위를 확인한다. 별도 위치 API가 있다 해도 현재 신청 권한, 목표 노선 제공 여부, 원천 시각은 실응답으로 검증해야 한다. [TAGO 위치·노선 API 구분](https://tago.go.kr/v5/use/openapi.jsp)
4. 지하철의 역별·노선별 제공 범위를 확인한다. 서울 지하철 API가 수도권 모든 역의 정보를 동일하게 제공한다고 가정하지 않는다.
5. 연속 관측으로 공급자 지연 분포, 오래된 응답 반복, 차량 후보 교체, 끊김, 운영 시간대를 측정한다. 재생 fixture에는 개인정보·키·원문 차량식별자가 아닌 합성 식별자를 사용한다.
6. 합성 위치 재생 E2E와 실제 이동 검증을 별도 기록한다. 합성 좌표가 정확히 맞는 경우의 통과를 현장 탑승 정확도로 보고하지 않는다.

위 조건 전에도 순수 엔진과 시뮬레이터 재생 테스트는 개발할 수 있다. 다만 실제 대중교통에서 검증된 자동 안내라는 출시 판정은 보류한다.
