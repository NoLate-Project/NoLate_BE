# Departure status API

`GET /api/schedules/{scheduleId}/departure-status`는 인증 회원 본인의 저장된 ETA 상태만 조회한다.
일정을 볼 수 없으면 `404`, 일정만 공유되어 이동 기능 권한이 없으면 `403`을 반환한다.
이 API는 TMAP 등 외부 provider를 호출하거나 PushJob 상태를 변경하지 않는다. UI polling은 마지막
worker snapshot을 읽으며, 아직 worker 평가가 없을 때만 선택 경로/저장 이동 시간으로 응답을
구성한다.

PushJob snapshot은 다음 조건을 모두 만족할 때만 현재 상태로 채택한다.

- 상태가 `ACTIVE` 또는 `PROCESSING`이고 알림이 활성화되어 있다.
- `job.scheduleAt`이 현재 일정 시작 시각과 같고, 저장된 ETA route fingerprint가 현재 회원의
  경로 fingerprint와 같다.
- 공유 참가자 계획의 schedule fingerprint가 현재 일정 시각·목적지와 일치한다.
- 이동 시간이 제품 상한 이내이며 `source`, `stale`, `lastCheckedAt`, 추천 출발 시각이
  완전하고 서로 일관된다.
- `LIVE_PROVIDER`이면 `stale=false`, `liveFetchedAt`과 `lastLiveTravelMinutes`가 존재하고,
  현재 ETA와 마지막 live ETA가 같다.
- `TIMETABLE_PROVIDER`이면 방금 조회한 시간표의 provider 취득 시각이 있고 `stale=false`다.
  실제 추천 출발시각은 `일정시각 - 이동시간`보다 이를 수 있다.
- 정상 provider snapshot의 `predictedArrivalAt`은 일정 시작 시각을 넘지 않는다. 동일 경로의
  조회 가능한 차량을 모두 확인해도 정시 도착이 불가능한 경우에만
  `TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE`, `stale=true`, `confidence=LOW` 조합으로 늦은
  절대 도착시각을 보존한다.

`CANCELED`, `FAILED`, `COMPLETED`, 이전 일정/경로 snapshot과 migration 전 provenance가 없는
legacy row는 거부한다. 이 경우 현재 선택 경로 또는 canonical 저장 이동 시간으로 명시적인 stale
fallback을 만들며, 현재 경로도 유효하지 않으면 ETA를 null로 반환한다.

## ETA provenance

- `LIVE_PROVIDER`: worker의 마지막 평가에서 provider가 실제 응답했다. `liveFetchedAt`이 있고
  `stale=false`다.
- `TIMETABLE_PROVIDER`: ODsay에서 선택한 동일 여정을 방금 다시 조회했지만 첫 승차 실시간
  도착정보는 적용하지 못한 결과다. `stale=false`, 신뢰도는 `MEDIUM`이며 공개
  `liveFetchedAt`과 live 비교 이력은 만들지 않는다.
- `SELECTED_ROUTE`: 사용자가 선택해 저장한 경로의 ETA snapshot이다. 새 live 취득 시각을
  만들지 않고 `stale=true`다.
- `SAVED_FALLBACK`: 선택 경로 ETA가 없어 일정/개인 계획에 저장된 시간을 사용했다.
  새 live 취득 시각을 만들지 않고 `stale=true`다.
- 이동 계획이나 이동 시간이 전혀 없으면 `travelMinutes`, `recommendedDepartureAt`, `source`,
  `confidence`가 null이며 `failureReason`이 원인을 설명한다.

PushJob snapshot이 있으면 `evaluatedAt`은 worker의 `lastCheckedAt`이다. 과거 데이터에서
`lastCheckedAt`이 `liveFetchedAt`보다 앞선 경우에만 `liveFetchedAt`으로 올려 시간 순서를
보장한다. PushJob 평가가 없으면 API가 저장 경로 fallback을 평가한 시각이다. `liveFetchedAt`은
provider가 실제로 응답한 시각이며 fallback 평가에서도 이전 성공 시각이 있으면 이를 유지하고,
한 번도 성공하지 않았다면 null이다.
`lastTrafficChangeMinutes`와 `lastChangedAt`은 비교 가능한 `LIVE_PROVIDER → LIVE_PROVIDER`
평가에서만 갱신되고, 조회나 fallback 전환은 교통 변화 이력을 만들지 않는다.

현재 상태용 `lastTravelMinutes/source`와 live 비교 이력은 분리한다.
`lastLiveTravelMinutes/lastLiveFetchedAt`은 fallback이 여러 번 발생해도 덮어쓰지 않는다.
provider가 복구되면 설정값 `schedule.traffic.live-comparator-max-age-minutes` 이내의 마지막
live와 비교한다. 기본값은 60분이며, 이를 넘은 baseline은 correction 근거로 사용하지 않고 새
live로 교체한다. 일정 또는 route fingerprint가 바뀌거나 job이 취소되면 baseline을 제거한다.

준비 시간과 안전 버퍼는 현재 제품 저장 모델에 없으므로 `preparationMinutes`,
`preparationStartAt`, `safetyBufferMinutes`는 null이다. `timeZone`은 서버의 일정 표시 정책인
`Asia/Seoul`이다.

`predictedArrivalAt`은 provider 여정과 첫 승차 실시간 overlay가 계산한 절대 목적지 도착
시각이다. `onTimeArrivalPossible`은 이 값이 있을 때만 boolean이며, 정시 도착 불가 진단에서는
`false`다. 이 진단의 푸시는 “늦지 않으려면”이라는 문구를 사용하지 않고 현재 확인된 가장
빠른 예상 도착시각과 정시 도착이 어렵다는 사실을 함께 안내한다.

## 동일 경로 갱신 제약

- `BIKE`는 provider 미지원으로 명시적 fallback한다.
- `ETC`는 현재 FE의 `CAR | ETC = driving` 제품 계약에 따라 TMAP 자동차 endpoint로
  명시적으로 매핑한다. catch-all 자동차 fallback으로 처리하지 않는다.
- `CAR`, `WALK` 선택 경로는 저장된 `searchOption` 또는 `providerRouteOption`이 있을 때만
  같은 옵션으로 provider를 다시 조회한다. 옵션이 없으면 `SELECTED_ROUTE`/`SAVED_FALLBACK`이다.
- `provider=odsay`인 대중교통 경로는 ODsay `maasRP`에서 다시 조회한다. 노선, 방향,
  승·하차 정류장 signature가 모두 같은 후보를 우선 사용한다. 첫 승차 실시간 지연으로 환승을
  놓치거나 선택 경로가 정시 도착 불가일 때만 같은 ODsay 응답 안의 최대 3개 대체 여정을
  비교한다. 전환 시 `ODSAY_ALTERNATIVE_ROUTE`를 푸시 payload에 남기며, 환승 시각이
  불완전한 선택 경로는 임의 변경하지 않고 저신뢰도 진단으로 보존한다.
- 시간표 기반 도착 마감 탐색은 최대 3회로 제한하고, 단순 `도착시각 - ETA` 역산 후보가 다음
  차량으로 넘어가 늦어지는 경우 조회한 후보 중 가장 늦게 출발하는 도착 가능 여정을 사용한다.
- 전체 시간은 새 ODsay 여정을 기준으로 하고 첫 승차 구간만 현재 정류장 도착정보로 교체한다.
  첫 차량의 지연은 다음 환승까지 전파하며 `이전 차량 도착 + 환승 도보 + 60초 버퍼`가 다음
  시간표 출발보다 늦으면 해당 여정을 제외한다. 미래 환승 정류장의 현재 도착정보는 사용하지
  않는다. 실시간 도착정보까지 적용되면
  `LIVE_PROVIDER`, 시간표만 유효하면 `TIMETABLE_PROVIDER`다.
- ODsay가 비활성화되거나 동일 경로를 찾지 못하면 TMAP 대중교통으로 바꾸지 않고 선택 경로
  snapshot으로 fallback한다.

모든 canonical/selected/provider ETA에는 동일한
`schedule.traffic.max-travel-minutes` 상한(기본 1,440분)을 적용한다. canonical
`route.travelMinutes`가 있으면 우선하며, selected JSON 값은 canonical과 일치할 때만 fallback
provenance로 사용한다. 따라서 과대값, NaN 또는 canonical과 불일치하는 JSON ETA가 저장값을
덮어쓰지 않는다.

공개 `failureReason`은 `PROVIDER_TIMEOUT`, `SELECTED_ROUTE_OPTION_MISSING` 같은 안정된 코드와
사용자 안전 문구만 포함한다. provider 예외 원문, URL, 좌표는 응답과 DB에 저장하지 않는다.
