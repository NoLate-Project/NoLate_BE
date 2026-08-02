# Transit ETA package

대중교통 ETA 계산은 `com.noLate.eta`가 소유한다. `schedule`은 좌표, 저장 경로, 도착 마감시각을
전달하고 계산된 이동시간과 추천 출발시각을 저장하는 호환 경계만 유지한다.

## Package responsibilities

- `eta.domain`: 공급자와 무관한 여정, 구간, 노선, 정류장 모델
- `eta.application.port`: 전체 여정 공급자 계약
- `eta.application.transit`: 동일 경로 매칭, 안전 출발시각 탐색, 첫 승차 실시간 보정,
  환승 가능성 판정
- `eta.infrastructure.odsay`: ODsay `maasRP` HTTP 요청과 canonical 변환
- `eta.infrastructure.routejson`: 여러 앱 버전이 저장한 선택 경로 JSON 복원
- `schedule.infrastructure.TransitRealtimeTrafficClient`: 기존 schedule 호출자를 위한 얇은 delegate

## Invariants

1. 앱이 대체 itinerary의 승하차 상세를 전달할 계약을 갖기 전까지 선택 경로만 계산한다.
   같은 ODsay 응답의 다른 후보도 projection하거나 arrival provider를 조회하지 않는다.
2. ODsay 시간은 분 단위로 정규화하고 구간 합계, 전체 timeline, 대기시간 범위를 검증한다.
3. 시간표 불연속 탐색은 설정된 호출 상한을 넘지 않는다.
4. 첫 승차 대기만 현재 도착정보로 교체한다. 실시간 첫 차량은 정류장 접근 뒤
   `boarding-buffer-seconds`까지 확보되는 후보만 고른다. 첫 지연은 환승 지점까지 전파해
   `이전 차량 도착 + 전파 지연 + 환승 도보 <= 다음 시간표 차량 출발`을 물리적 탑승 경계로
   검사한다. 이를 넘으면 `MISSED`다. 물리적으로는 가능해도 기본 60초
   `transfer-confidence-margin-seconds`를 확보하지 못하면 분 단위 시간표 경계에서 거짓 성공을
   만들지 않도록 `UNKNOWN`으로 내리고, 기존 조회 예산 안에서 더 이른 여정을 재탐색한다.
5. 미래 환승은 ODsay 시간표로만 판정하며 실시간으로 표시하지 않는다. 필요한 시간표 시각이
   없거나 위 안전 여유를 입증할 수 없으면 `TRANSIT_TRANSFER_TIMING_UNKNOWN` 저신뢰도 진단으로
   내린다.
6. 정상 결과와 명시적 `TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE` 즉시 출발 결과만 provider
   `recommendedDepartureAt`을 사용한다. 그 밖의 timeout/degraded 진단은 직전 fresh
   `lastRecommendedDepartureAt`을, 첫 조회라면 사용자가 저장한 `departureAt`을 그대로 유지한다.
   degraded 이동시간으로 알람을 새로 역산하지 않는다. 환승 상태가 `MISSED`/`UNKNOWN`이면
   부정확한 정시 도착 문구가 울리지 않도록
   미래 native alarm을 `CANCEL`하고, 새 상태 또는 두 상태 사이의 변화에만 경로 재확인 push를
   한 번 보낸다. 알람 tombstone 반영이 확정되기 전 사용자 문구는 취소 완료를 주장하지 않는다.
   같은 실패가 연속되면 `lastEtaFailureReason`으로 중복 발송하지 않는다.
   payload의 `transitTransferFeasibility`도 `MISSED` 또는 `UNKNOWN`을 명시한다. 이후 직전
   persisted 상태가 환승 실패이고 현재 ETA가 정상으로 회복된 경우에만 CANCEL tombstone을
   새 generation의 `UPSERT`로 재개한다.
7. ODsay 실패 시 TMAP 대중교통 결과나 첫 승차 legacy overlay를 섞지 않고 저장 경로로
   fallback한다. 비 ODsay legacy도 단일 ride만 첫 대기를 보정하고 다중 ride는 fail closed한다.
8. safe-departure의 같은 first-stop 반복은 15초/최대 512개 bounded cache로 흡수한다.
   캐시는 원본 DTO만 보존하며 `sourceUpdatedAt` freshness는 cache hit마다 다시 검사한다.
9. ODsay CID와 TAGO cityCode는 같은 숫자 namespace가 아니다. 서울 CID 1000은 서울 API
   전용 타입이며 TAGO 12(세종)로 변환하지 않는다. 공식 명칭/포함 관계로 대조된 137개
   ODsay CID만 현재 TAGO 코드로 변환한다. 계룡처럼 공급자 코드가 둘인 지역, 폐지·통합 전
   행정구역명, ODsay 표가 중복된 CID는 실제 정류장 smoke test 전까지 `Unsupported`로
   fail closed한다.
10. 저장 경로에 방향 코드나 방향명이 있으면 재조회 후보에도 비교 가능한 방향 메타데이터가
    있어야 한다. 후보 방향이 누락되거나 반대면 같은 노선·정류장이어도 선택 경로로 인정하지
    않는다. 저장 데이터 자체에 방향이 없는 legacy 경로만 이전 호환성을 유지한다.
11. 지하철 일반/급행 종별은 선택 경로 JSON의 `serviceClass`로 보존한다. ODsay가 명시한
    `(급행)` 계열 표기는 `EXPRESS`, 비어 있지 않고 상충하지 않는 일반 ODsay 지하철 노선명은
    해당 공급자의 표기 계약에 따라 `LOCAL`로 저장한다. 재조회 후보와 실시간 열차 모두 같은
    종별임을 확인해야 하며, 이름 누락·상충·legacy `UNKNOWN`은 추론하지 않고 저장 ETA로 내린다.
    이는 같은 노선 ID를 쓰는 일반·급행의 정차역 차이를 숨기지 않기 위한 경계다.
12. 도착정보는 provider 원천 갱신시각을 증명하고 freshness 상한 안에 있을 때만 첫 승차
    실시간 보정에 사용한다. 서버가 받은 시각만 있는 응답은 실시간처럼 승격하지 않는다.
13. ETA push 한 회차의 immutable payload는 기본 120초 TTL 안에서만 발송한다. 기본 1분
    provider 재시도는 TTL보다 짧아야 한다. TTL 만료, 체크 번호·generation·fingerprint 불일치,
    일정/회원 불일치는 provider 직전 fence에서 거부하고, 이전 회차를 닫은 뒤 1초 catch-up
    marker로 새 ETA 회차를 만든다.
14. ODsay base URL은 기본적으로 HTTPS이고 user-info/query/fragment를 설정값에 포함할 수 없다.
    host는 공식 `api.odsay.com` 또는 loopback만 허용한다. 검토된 egress proxy는
    `ODSAY_ALLOW_CUSTOM_ENDPOINT=true`, 원격 HTTP까지 필요한 예외 환경은 별도로
    `ODSAY_ALLOW_INSECURE_HTTP=true`를 명시해야 한다. 서버 키는 URI template의 query
    parameter로만 인코딩한다.

서버 호출은 모바일 키와 분리된 `ODSAY_SERVER_API_KEY`가 있고 `ODSAY_ENABLED=true`인 환경에서만
활성화된다.

ODsay 필드/표기 계약은 [공식 API Reference](https://lab.odsay.com/guide/releaseReference?platform=web)와
[공식 급행 표기 답변](https://lab.odsay.com/community/boardView?seq=673)을 기준으로 고정한다.
