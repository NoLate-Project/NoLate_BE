# Departure status API

`GET /api/schedules/{scheduleId}/departure-status`는 인증 회원 본인의 이동 계획만 평가한다.
일정을 볼 수 없으면 `404`, 일정만 공유되어 이동 기능 권한이 없으면 `403`을 반환한다.

## ETA provenance

- `LIVE_PROVIDER`: provider가 이번 요청에 실제 응답했다. `liveFetchedAt`이 있고 `stale=false`다.
- `SELECTED_ROUTE`: 사용자가 선택해 저장한 경로의 ETA snapshot이다. `liveFetchedAt=null`,
  `stale=true`다.
- `SAVED_FALLBACK`: 선택 경로 ETA가 없어 일정/개인 계획에 저장된 시간을 사용했다.
  `liveFetchedAt=null`, `stale=true`다.
- 이동 계획이나 이동 시간이 전혀 없으면 `travelMinutes`, `recommendedDepartureAt`, `source`,
  `confidence`가 null이며 `failureReason`이 원인을 설명한다.

`evaluatedAt`은 API가 상태를 평가한 시각이고, `liveFetchedAt`은 provider가 실제로 응답한
시각이다. 두 값은 같은 의미가 아니다. `lastTrafficChangeMinutes`와 `lastChangedAt`은
PushJob이 실제로 저장한 변화 이력만 반환하며 조회 시 임의로 만들지 않는다.

준비 시간과 안전 버퍼는 현재 제품 저장 모델에 없으므로 `preparationMinutes`,
`preparationStartAt`, `safetyBufferMinutes`는 null이다. `timeZone`은 서버의 일정 표시 정책인
`Asia/Seoul`이다.

## 동일 경로 갱신 제약

- `BIKE`, `ETC`는 TMAP 자동차 API로 대체하지 않는다.
- `CAR`, `WALK` 선택 경로는 저장된 `searchOption` 또는 `providerRouteOption`이 있을 때만
  같은 옵션으로 provider를 다시 조회한다. 옵션이 없으면 `SELECTED_ROUTE`/`SAVED_FALLBACK`이다.
- 선택한 대중교통 itinerary를 동일 여정으로 재조회할 provider 계약이 없으므로 다른 추천
  여정을 `LIVE_PROVIDER`로 표시하지 않고 저장 snapshot을 사용한다.

공개 `failureReason`은 `PROVIDER_TIMEOUT`, `SELECTED_ROUTE_OPTION_MISSING` 같은 안정된 코드와
사용자 안전 문구만 포함한다. provider 예외 원문, URL, 좌표는 응답과 DB에 저장하지 않는다.
