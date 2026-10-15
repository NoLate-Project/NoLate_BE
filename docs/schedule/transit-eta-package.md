# Transit ETA package

대중교통 ETA 계산은 `com.noLate.eta`가 소유한다. `schedule`은 좌표, 저장 경로, 도착 마감시각을
전달하고 계산된 이동시간과 추천 출발시각을 저장하는 호환 경계만 유지한다.

## Package responsibilities

- `eta.domain`: 공급자와 무관한 여정, 구간, 노선, 정류장 모델
- `eta.application.port`: 전체 여정·동일 도보 초 단위 시간·공식 환승 참조 자료 공급자 계약
- `eta.application.transit`: 동일 경로 매칭, 안전 출발시각 탐색, 첫 승차 실시간 보정,
  환승 가능성 판정
- `eta.infrastructure.odsay`: ODsay `maasRP` 변환과 동일 전체 도보 V2 정밀화
- `eta.infrastructure.seoul.SeoulTransferReferenceCatalog`: 버전이 있는 공식 환승 CSV 참조
- `eta.infrastructure.routejson`: 여러 앱 버전이 저장한 선택 경로 JSON 복원
- `schedule.infrastructure.TransitRealtimeTrafficClient`: 기존 schedule 호출자를 위한 얇은 delegate

## Invariants

1. 재조회마다 선택 경로와 같은 signature의 차량을 모두 평가하고, 환승 가능성과 도착 마감을
   만족하는 다음 차량이 하나라도 있으면 다른 경로보다 우선한다. 동일 signature가 응답에
   존재하지만 안전한 차량이 없거나 선택 signature가 응답에서 일시적으로 사라졌을 때만 같은
   ODsay 응답의 다른 여정을 최대 3개 평가한다.
   채택한 대체 여정은 `ODSAY_ALTERNATIVE_ROUTE`와 전체 승하차 구간을 푸시 작업 snapshot에
   원자적으로 저장해 일반 푸시, Live Activity, 일정 상세가 같은 경로를 표시하게 한다.
   경로 identity는 승차 노선·승하차 정류장·방향의 안정 ID를 우선하고, ID가 없을 때만
   범위가 포함된 이름을 사용한다. 도보 구간과 ID가 있는 객체의 표시명 변경은 identity를
   바꾸지 않는다. 경로 전환의 retry latch는 `(notificationGeneration, checkCount)`에 묶어
   snooze가 check 번호를 0으로 초기화해도 이전 세대의 전환 푸시를 다시 재생하지 않는다.
2. ODsay 경로의 분 값을 보존하고 구간 합계, 전체 timeline, 대기시간 범위를 검증한다.
   전체 구간이 증명된 초 값만 이동시간 계산에 사용할 수 있으며 원래 분과 올림 정합을 확인한다.
3. 시간표 불연속 탐색은 설정된 호출 상한을 넘지 않는다.
4. 첫 승차 대기만 현재 도착정보로 교체한다. 실시간 첫 차량은 정류장 접근 뒤
   `boarding-buffer-seconds`까지 확보되는 후보만 고른다. 첫 지연은 환승 지점까지 전파해
   `이전 차량 도착 + 전파 지연 + 환승 도보 <= 다음 시간표 차량 출발`을 물리적 탑승 경계로
   검사한다. 이를 넘으면 `MISSED`다. 물리적으로는 가능해도 기본 60초
   `transfer-confidence-margin-seconds`를 확보하지 못하면 분 단위 시간표 경계에서 거짓 성공을
   만들지 않도록 `UNKNOWN`으로 내리고, 기존 조회 예산 안에서 더 이른 여정을 재탐색한다.
5. 미래 환승은 경로 공급자가 증명한 운행 시간표나 별도로 연결한 서울 시간표로만 판정하며
   실시간으로 표시하지 않는다. 서울 OA-101 연결은 현재 운행일 범위만 지원한다. 필요한 시간표 시각이
   없거나 위 안전 여유를 입증할 수 없으면 `TRANSIT_TRANSFER_TIMING_UNKNOWN` 저신뢰도 진단으로
   내린다. 단일 후보의 `MISSED`/`UNKNOWN`은 즉시 최종 경고가 아니며, 같은 노선의 다음 차량과
   검증 가능한 대체 여정 및 최대 3회의 시간표 탐색을 모두 소진한 뒤에만 경고로 확정한다.
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
    이는 같은 노선 ID를 쓰는 일반·급행의 정차역 차이를 숨기지 않기 위한 경계다. ODsay의
    `way`는 종착역이 아니라 방면 정보이므로 실시간 열차의 운행 범위 증거로 사용하지 않는다.
    역순서 topology가 없는 현재 계약에서는 단축 운행의 거짓 성공을 막기 위해 실시간 열차
    종착역이 사용자 하차역과 정확히 일치할 때만 지하철 overlay를 허용한다.
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
15. 서울 시간표로 전 구간을 확인한 지하철 여정은 첫 차량 실시간 지연 때문에 환승이
    `MISSED`/`UNKNOWN`이 되면 `TransitTimetableProvider`를 통해 이후 환승을 다시 연결한다.
    첫 열차의 원본 시간표와 관측을 유지하고, 지연을 전파한 하차시각에 환승 도보와 기존
    안전 여유를 더해 다음 차량을 선택한다. 공급자·승하차 역코드·호선·방향·종별·운행일을
    보존하며, 마지막 환승까지 확인하지 못하면 부분 회복을 버리고 기존 진단을 유지한다.
    재연결 후에도 도착 마감과 최대 이동시간을 그대로 검사한다. 실제 도착 API의 추가 조회는
    없으며 다음 환승 차량은 실시간이 아니라 시간표라는 근거를 유지한다.
16. 환승 도보 정밀화는 시간표 연결 전에 수행한다. ODsay V2의 시작/끝·전체 거리·링크
    순서/좌표가 원래 경로와 일치할 때만 전체 초를 적용하고 원래 분과 취득 시각을 보존한다.
    한 평가의 새 도보 HTTP는 최대 1회이고 실패 시 기존 분을 사용한다. 서울교통공사 공식
    환승 기준값은 역·이전/다음 호선으로 연결하되 방향/승강장 범위가 없으므로 참조 전용이다.
    공식 거리/1.2m/s 산출 시간을 실측으로 표시하거나 다른 도보 경로에 자동 적용하지 않는다.
    `walkingTiming`은 이동 근거, `transferTiming`은 현재 하차·도보·안전 여유·다음 승차 및
    잔여 여유다. 여유는 기존 max(buffer, confidence) 규칙이며 도보에 포함하거나 재합산하지 않는다.
17. 선택적 도보 정밀화 거절은 고정된 `WalkingRefinementFailure` 코드와 횟수,
    마지막 관측시각·HTTP 상태·숫자 공급자 코드만 진단한다. 원문 메시지, 요청 URL,
    인증키와 좌표는 진단에 넣지 않는다. 경로/거리/링크 불일치와 인증/한도/전송 오류를
    구분하며, 경로 내부 `errorCode=401`만으로 HTTP 인증 오류라고 추정하지 않는다.
    거절 시 원래 분 또는 동일 경로의 검증된 캐시값을 유지하고 재시도하지 않는다.
    `unavailableCalls`는 HTTP 전 안전장치 거절도 포함할 수 있어 HTTP 실패율의 분모가 아니다.

### 환승 재연결 검증 범위

`TransitTimetableCalculationIntegrationTest`는 Tmap 매퍼, 시간표 연결, 첫 차량 보정,
환승 평가와 ETA 반환을 고정 응답으로 연결한다. 지연 후 다음 차량 회복, 60초 여유 부족,
다음 차량 부재, 회복 후 도착 마감 초과를 확인한다. 공급자 HTTP, 운영 DB, 시뮬레이터 UI,
실제 이동 오차 2분 이내를 입증하는 E2E는 아니며 실시간 API 복구 후 별도로 검증해야 한다.

서버 호출은 모바일 키와 분리된 `ODSAY_SERVER_API_KEY`가 있고 `ODSAY_ENABLED=true`인 환경에서만
활성화된다.

ODsay 필드/표기 계약은 [공식 API Reference](https://lab.odsay.com/guide/releaseReference?platform=web)와
[공식 급행 표기 답변](https://lab.odsay.com/community/boardView?seq=673)을 기준으로 고정한다.
