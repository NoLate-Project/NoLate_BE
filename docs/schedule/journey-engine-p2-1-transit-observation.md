# P2-1 교통 도착 관측 정규화 계약

작성일: 2026-10-04. 범위: 순수 정규화 어댑터와 합성 단위 테스트.

`JourneyTransitObservationAdapter.normalize(TransitArrivalDto, evaluatedAt)`는 기존 도착정보 한 건을
version 1 `JourneyTransitObservation`으로 변환한다. Spring bean 등록, controller, HTTP, 공급자 fetch,
DB, 스키마, 하네스, UI 연결, 음성·푸시 호출은 추가하지 않는다. `.env`나 실제 API를 사용하지 않는다.
[P0 제공처 능력 검증](journey-engine-p0-transit-capabilities.md)의 한계를 코드 계약으로 고정하는 단계다.

## 시각·품질 계약

| 필드 | 의미 |
| --- | --- |
| `sourceUpdatedAt` | 파싱된 공급자 원천 시각. 수신 시각으로 대체하지 않는다. |
| `receivedAt` | 기존 DTO `observedAt`을 파싱한 수신·변환 시각. 최신성 증거가 아니다. |
| `expectedAt` | 기존 절대 예측 시각을 그대로 파싱한다. 수신 시각이나 분 단위 값으로 재계산하지 않는다. |
| `evaluatedAt` | 호출자가 명시적으로 제공한 평가 시각. 시스템 시계는 읽지 않는다. |
| `inputFreshnessEvidence` | 입력 enum을 보존한다. 이것만으로 신뢰를 허용하지 않는다. |
| `validUntil` | 검증 성공 때만 `min(sourceUpdatedAt + maxSourceAge, expectedAt)`. 상한은 미포함이다. |
| `remainingWaitSeconds` | 검증 성공 때만 `ceil(expectedAt - evaluatedAt)`. 원래 waitSeconds를 재사용하지 않는다. |

기본 정책은 원천 정보 나이 120초, 미래 clock skew 5초, 원천 시각부터 예상 도착까지 최대 3,600초다.
정책 허용 범위는 각각 1~120초, 0~30초, 1~3,600초다. **이 수치는 보수적인 로컬 구현 정책이며,
실제 공급자 SLA나 실응답 지연을 검증한 값이 아니다.** P1-3 자동 출발 outbox의 600초 TTL과 다른 개념이다.

알려진 공급자·교통수단 조합, realtime, 파싱 가능한 원천·수신·예측 시각이 필요하다. 원천이 만료됐거나,
미래 skew 초과, 원천이 수신보다 skew 이상 미래, 예상 도착이 현재 이하·원천 이전·horizon 초과이면 거절한다.
`waitSeconds`가 있으면 0~horizon 범위이며 `sourceUpdatedAt + waitSeconds == expectedAt`이어야 한다.
`expectedAt` 누락을 `waitMinutes`나 `waitSeconds`로 복원하지 않는다. 과도한 시각의 연산 overflow도 거절한다.

예: 12:00:00 원천의 120초 대기 정보를 12:00:30에 평가하면 잔여 90초다. 120초로 다시 표시하거나
12:02:30으로 예측을 늦추지 않는다. 동일 원천을 재평가해도 `validUntil`은 연장되지 않는다.

품질은 `FRESH_WAITING_INFORMATION`, `UNVERIFIED`, `STALE`, `INVALID`, `UNAVAILABLE`이며 상세 사유 enum을
함께 반환한다. 여러 문제가 있으면 INVALID → UNAVAILABLE → UNVERIFIED → STALE 순서로 대표 품질을 선택한다.
`waitingObservationUsable`은 시간 검증된 대기 관측을 뜻할 뿐 **선택 경로의 승차 가능 차량 매칭을 뜻하지 않는다.**
파싱 가능한 원천 시각·예측은 거절 후에도 보존하지만 `validUntil`과 잔여 대기 시간은 null이다.

## 대기 정류소와 하차 정류소 분리

TAGO BUS의 `remainingStops`만 `incomingVehicleStopsAway`로 보존한다(0~500, 초과·음수는 null과 오류 사유).
이는 **조회 정류소로 접근하는 차량의 거리**이며, 사용자 차량의 목적 하차 정류소까지 남은 개수가 아니다.
서울·알 수 없는 공급자의 같은 이름 필드는 의미가 검증되지 않았으므로 대입하지 않는다.
`remainingStopsToAlight`는 항상 null이다. 하차 안내 이벤트를 만들지 않는다.

TAGO `direction`은 현 파서가 `routetp`를 넣는 노선유형이므로 `routeTypeLabel`로만 보존한다.
`directionLabel=null`, `directionEvidence=ROUTE_TYPE_NOT_DIRECTION`이다. TAGO는 현재 원천 시각을 제공하는
DTO 경로가 없으므로 timestamp와 enum을 임의로 넣어도 `SOURCE_TIMESTAMP_UNSUPPORTED_FOR_PROVIDER`로 차단한다.
향후 원천 계약이 실제로 확장되면 새 테스트와 함께 이 정책을 명시적으로 바꿔야 한다.

서울 버스·지하철의 방면 문자열도 `PROVIDER_LABEL_UNMATCHED`일 뿐이다. 선택 경로와 방면을 대조하지 않았으므로
MATCHED 상태를 만들지 않는다. 조회 정류소의 cityCode/nodeId/arsId와 노선명은 bounded 표시·문맥 값일 뿐
노선 ID나 차량 ID로 바꾸지 않는다. 알 수 없는 공급자/수단, 초과 길이·제어 문자 포함 표시는 조용히 추정하지 않는다.

## 탑승 능력은 항상 닫힘

기존 `TransitArrivalDto`에 노선·차량 식별자가 없으므로 다음 값은 생성자나 copy로 승격할 수 없는 상수다.

- `providerRouteId=null`, `vehicleId=null`, `remainingStopsToAlight=null`
- `confirmedBoarding=false`, `vehicleTrackingEnabled=false`, `guidanceAnnouncementEnabled=false`

`boardingBlockedReasons`는 노선 ID 없음, 차량 ID 없음, 선택 구간/revision 미결합, 방면 미매칭,
기기 위치 증거 필요를 항상 포함한다. 시간 검증도 실패하면 `TIMING_NOT_VERIFIED`를 추가한다.
`ARRIVED` 상태, 0초 대기, 차량이 1정류소 전이라는 값은 탑승 확정 증거가 아니다.

P2-1 기기 위치 기반 접근·대기·탑승후보의 순수 상태 판단은 별도 경로/위치 증거로 진행할 수 있지만,
이 어댑터만으로 실제 차량 탑승 확정이나 ETA 음성 안내를 활성화할 수 없다. 실데이터·실주행·APNs는 미검증이다.

## 검증

신규 `JourneyTransitObservationAdapterTest`는 고정 공개 합성 시각/문자열만 사용한다.
원천과 수신 시각 분리, 나이·예측 만료 경계, 미래 skew, 수신 재기준화 오류, TAGO 의미,
차량 식별 부재의 fail-closed 동작, 재평가 시 유효시간 불연장, overflow·정책 범위를 검증한다.

```sh
./gradlew test --offline --no-daemon -PexcludeMysqlTests=true \
  --tests 'com.noLate.journey.application.JourneyTransitObservationAdapterTest' \
  --tests 'com.noLate.transit.infrastructure.JourneyTransitCapabilityContractTest' \
  --tests 'com.noLate.transit.infrastructure.SeoulTransitArrivalClientTest' \
  --tests 'com.noLate.transit.infrastructure.TagoTransitArrivalClientTest' \
  --tests 'com.noLate.eta.application.transit.FirstBoardingRealtimeOverlayTest'
```

2026-10-04 실행 결과: **59 tests PASS, 실패/오류/스킵 0**.

- 신규 순수 관측 어댑터: 21 PASS.
- P0 공급자 능력 계약: 7 PASS.
- 서울 공급자 어댑터: 15 PASS.
- TAGO 공급자 어댑터: 4 PASS.
- 기존 첫 승차 ETA 보정: 12 PASS.

Kotlin 컴파일과 `git diff --check`도 통과했다. 기존 공급자 테스트는 합성/모의 응답만 사용한다.
이 묶음은 서버 계약 테스트이며 시뮬레이터 UI E2E나 실제 공급자 검증이 아니다.
