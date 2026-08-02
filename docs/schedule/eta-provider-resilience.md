# ETA 외부 공급자 운영 안정성

대중교통 ETA의 외부 호출은 `com.noLate.eta.resilience`에서 다음 두 경계로 제한한다.

1. `EtaProviderGuard`는 공급자 ID마다 독립된 동시 실행 슬롯, 유한 대기열, 최대 대기시간,
   circuit breaker를 유지한다. 한 공급자의 포화나 장애가 다른 공급자의 quota를 점유하지 않는다.
2. `EtaCalculationDeadline`은 한 번의 전체 대중교통 계산에 monotonic soft deadline을 부여한다.
   중첩 계산은 남은 시간을 연장할 수 없고, deadline 뒤 반환된 live 결과는 저장 경로 ETA로
   fallback한다.

soft deadline은 실행 중인 소켓 thread를 `Future.cancel`로 남기지 않는다.
`EtaDeadlineAwareClientHttpRequestFactory`가 HTTP 요청을 만들 때마다 현재 남은 시간을 다시 읽고,
connect+read phase timeout 합이 남은 시간 이내가 되도록 새 request factory에 설정한다. 공급자
단계 사이에서는 soft deadline이 추가 호출과 늦은 결과 채택도 막는다. 현재 ODsay의 2초 connect +
4초 read timeout보다 전체 기본 budget(8초)이 길어 단일 정상 요청을 인위적으로 자르지 않는다.

## 설정

기본 설정은 `application.yml`의 `eta.resilience`에 있다. 모든 값은 시작/첫 사용 시 아래 절대
상한으로 검증한다.

| 설정 | 기본값 | 허용 범위 |
|---|---:|---:|
| 전체 soft deadline | 8초 | 0초 초과, 5분 이하 |
| 동시 호출 | 8 (ODsay 4) | 1~64 |
| 대기 호출 | 16 (ODsay 8) | 0~256 |
| 대기시간 | 100ms | 0~5초 |
| circuit 연속 실패 | 5 | 1~100 |
| circuit open | 30초 | 0초 초과, 10분 이하 |

공급자별 override는 `eta.resilience.providers.<provider-id>` 아래에 둔다. 현재 독립 회로는
`odsay`, `seoul_subway`, `seoul_bus`, `tago_bus` 네 개다. provider ID는 내부에서 정의한 32자 이하
영문 소문자/숫자/`_`/`-`만 허용한다. 외부 요청값을 provider ID로 사용하면 안 된다.

서울/TAGO의 한 logical 조회는 정류장 탐색과 도착 조회 여러 건으로 늘어날 수 있다.
`transit.wire-rate-limit`은 replica별 실제 HTTP 호출을 1초 fixed window로 제한하고, 초과 요청은
대기하지 않고 deadline 안에서 fail-fast한다. 기본값은 서울 지하철 10/s, 서울 버스 20/s,
TAGO 버스 20/s다. 다중 replica에서 공급자 계정 전체 quota를 보장하려면 Redis 등 외부 shared
budget이 추가로 필요하다.

키가 설정된 원격 endpoint는 HTTPS가 기본이다. 서울 공식 HTTP endpoint를 직접 써야 하는 배포는
키 노출·응답 변조 위험을 승인한 뒤에만 `SEOUL_ALLOW_INSECURE_HTTP=true`로 열고, 가능하면 TLS
종단 프록시를 사용한다. TAGO 기본 endpoint는 HTTPS다. base URL에 credential/query/fragment를
넣는 설정은 시작 시 거부한다.

도착정보의 freshness는 `PROVIDER_SOURCE_TIMESTAMP`와
`LOCAL_RECEIPT_TIMESTAMP_ONLY`를 구분한다. 후자는 서버가 응답을 받은 시각일 뿐 차량 정보의
원천 갱신시각을 증명하지 못하므로 actionable 첫 승차 overlay로 승격하지 않는다. 서울 지하철은
공식 `recptnDt`(도착정보 생성시각), 서울 버스 `getStationByUid`는 공식 `mkTm`(제공시각)을
원천 시각 증거로 사용한다. 두 필드가 없거나 파싱되지 않으면 로컬 수신시각으로 강등한다.
TAGO 버스의 공식 정류소별 도착 응답은 `arrtime`과 `arrprevstationcnt`는 제공하지만 원천 시각과
차량 ID를 제공하지 않으므로 보수적으로 timetable/fallback ETA를 유지한다. 짧은 HTTP 캐시 TTL이나
응답 수신시각은 upstream 차량 관측 freshness를 입증하지 못한다. 이 선택은 잘못된 출발 알람을
막지만 TAGO 실시간 버스 ETA coverage를 낮추므로 운영 지표에서 정확도와 별도로
degraded/coverage를 반드시 본다.

필드 의미는 [서울 지하철 실시간 도착정보](https://data.seoul.go.kr/dataList/OA-12764/A/1/datasetView.do),
[서울 버스도착 정보 조회](https://data.seoul.go.kr/dataList/OA-1091/F/1/datasetView.do),
[TAGO 버스도착정보](https://www.data.go.kr/data/15098530/openapi.do) 공식 명세로 검증한다.

## 실패 의미

- provider HTTP/파싱 예외뿐 아니라 HTTP 200 응답 안의 서울 지하철 `RESULT.CODE`, 서울 버스
  `headerCd`/`returnCode`, TAGO `resultCode` application error도 circuit 실패에 포함한다.
- 정상적인 no-data는 장애가 아니다. 서울 지하철 `INFO-200`과 TAGO `resultCode=03`은 빈 성공으로
  처리하고 다음 역명/도시 후보를 계속 조회한다. 인증·quota·형식 오류만 circuit 실패로 기록한다.
- bulkhead 거절, 이미 열린 circuit, soft deadline 만료, thread interruption은 provider 장애로
  중복 집계하지 않는다.
- open 시간이 끝나면 한 호출만 half-open probe가 된다. probe 성공 시 닫고, 실패 시 현재
  wall clock부터 open 기간을 다시 시작한다.
- wall clock은 circuit recovery 시점에만, monotonic ticker는 계산/대기 budget에만 사용한다.
- 빈 정류장 탐색 결과는 process-lifetime negative cache에 넣지 않는다. 장애·quota 회복 뒤 같은
  프로세스에서 다시 해석할 수 있어야 한다.

## 통합 규칙

공급자 client의 실제 HTTP/응답 해석 블록을 아래처럼 감싼다. 기존 provider latency/outcome
관측 wrapper가 있다면 그 wrapper 안쪽에 guard를 둬서 circuit/bulkhead 거절도 bounded outcome으로
관측한다.

```kotlin
providerMetrics.observe(providerId) {
    providerGuard.execute(providerId) {
        callAndMapProvider()
    }
}
```

대중교통 전체 계산 진입점인 `TransitRealtimeTrafficClient`가 `calculationDeadline.within { ... }`을
적용한다. 비동기 thread로 provider 호출을 옮길 경우 `ThreadLocal` scope는 자동 전파되지 않으므로,
deadline을 명시적으로 전달하거나 동일 thread에서 계산해야 한다.
