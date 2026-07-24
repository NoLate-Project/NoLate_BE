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

`CANCELED`, `FAILED`, `COMPLETED`, 이전 일정/경로 snapshot과 migration 전 provenance가 없는
legacy row는 거부한다. 이 경우 현재 선택 경로 또는 canonical 저장 이동 시간으로 명시적인 stale
fallback을 만들며, 현재 경로도 유효하지 않으면 ETA를 null로 반환한다.

## ETA provenance

- `LIVE_PROVIDER`: worker의 마지막 평가에서 provider가 실제 응답했다. `liveFetchedAt`이 있고
  `stale=false`다.
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

## 동일 경로 갱신 제약

- `BIKE`는 provider 미지원으로 명시적 fallback한다.
- `ETC`는 현재 FE의 `CAR | ETC = driving` 제품 계약에 따라 TMAP 자동차 endpoint로
  명시적으로 매핑한다. catch-all 자동차 fallback으로 처리하지 않는다.
- `CAR`, `WALK` 선택 경로는 저장된 `searchOption` 또는 `providerRouteOption`이 있을 때만
  같은 옵션으로 provider를 다시 조회한다. 옵션이 없으면 `SELECTED_ROUTE`/`SAVED_FALLBACK`이다.
- 대중교통은 route JSON 유무와 관계없이 선택한 itinerary를 동일 여정으로 재조회할 provider
  계약이 없다. 따라서 다른 추천 여정을 `LIVE_PROVIDER`로 표시하지 않고 저장 snapshot을
  사용한다. 실시간 대중교통 교통 변화 알림은 동일 itinerary 갱신 계약을 도입하기 전까지
  제공하지 않는다.

모든 canonical/selected/provider ETA에는 동일한
`schedule.traffic.max-travel-minutes` 상한(기본 1,440분)을 적용한다. canonical
`route.travelMinutes`가 있으면 우선하며, selected JSON 값은 canonical과 일치할 때만 fallback
provenance로 사용한다. 따라서 과대값, NaN 또는 canonical과 불일치하는 JSON ETA가 저장값을
덮어쓰지 않는다.

공개 `failureReason`은 `PROVIDER_TIMEOUT`, `SELECTED_ROUTE_OPTION_MISSING` 같은 안정된 코드와
사용자 안전 문구만 포함한다. provider 예외 원문, URL, 좌표는 응답과 DB에 저장하지 않는다.
