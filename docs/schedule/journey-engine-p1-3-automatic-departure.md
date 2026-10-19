# P1-3 자동 출발 서버 계약 및 격리 검증

작성일: 2026-10-04. 상태: 서버 계약·로컬 테스트 구현, 운영 비활성.

## 범위와 안전 경계

`ARMED` 준비는 출발 완료가 아니다. 별도 `automatic_journey_preparations`에 준비와 이벤트 수신 기록만 저장하며,
기존 수동 `journey_guidance_sessions` row는 만들지 않는다. 준비·현재 상태 조회·시험 종료는 출발 기록,
개인 알림 해제, 출발 푸시/기기 알람 취소, Live Activity 투영을 호출하지 않는다.

첫 `AUTO_DEPARTURE` 승인에서만 기존 출발 처리 흐름을 한 트랜잭션으로 호출한다.

1. 알림 수신자 member를 기존 정렬 순서로 잠그고 JWT 로그인 세대를 다시 검증한다.
2. 본인의 현재 개인 이동 계획을 schedule lock 아래 읽어 준비 당시 fingerprint와 비교한다.
3. 소유 클라이언트·세션·세대·경로 revision·digest·이벤트 순서·시간·상태를 확인한다.
4. 참가자 최초 출발 기록, 개인 이동 알림 해제, 출발 푸시/기기 알람 취소, 기존 소유자 출발 상태를 처리한다.
5. 같은 트랜잭션에 ACTIVE/sequence 1과 멱등 receipt를 저장한다. 실패하면 모두 롤백한다.

알람 취소에는 `preserveLiveActivity=false`를 사용한다. 따라서 기존 출발 전 Live Activity의 일반 `DEPARTED`
종료 흐름은 발생할 수 있다. **새 inTransit Live Activity, 가짜 MANUAL 여정, 탑승/하차/환승 안내는 만들지 않는다.**
지속 안내와 ActivityKit 투영은 후속 단계다. 실제 APNs 전달·실주행 정확도는 이 검증의 대상이 아니다.

`routeInputDigest`는 클라이언트가 준비한 경로 입력과 후속 이벤트를 같은 값에 묶는 일관성 필드다.
서버의 형상 검증, 원본 공급자 출처 인증, 최신 경로 인증 또는 이동 증명의 서명으로 해석하면 안 된다.
서버는 본인 저장 계획의 별도 fingerprint를 비교한다. 기존 `JourneyRouteSnapshot`의 READY 판정은
P1-2 native 형상 품질 검사와 동일하지 않으며, GPS 원본이나 궤적은 요청/저장 계약에 없다.

## 기능 게이트

모든 신규 동작은 `journey.guidance.enabled`와 `journey.automatic.enabled`가 모두 true여야 한다. 기본값은 false다.

- SIMULATOR 준비/출발: `journey.automatic.simulator-enabled=true`, `journey.automatic.isolated-test-scope=true`,
  그리고 `test` 또는 `journey-isolated-fixture` profile을 함께 요구한다. `prod`/`production`이 섞이면 거절한다.
- DEVICE 준비/출발: 추가 `journey.automatic.device-enabled=true`를 요구한다. 기본 false이며 이번 작업에서 활성화하지 않았다.
- 로컬 하네스는 프로덕션 설정을 읽지 않고 테스트 객체 생성 시에만 위 격리 조건을 명시한다.

기존 수동 길안내가 활성화돼 있으면 새로운 자동 준비/활성화를 거절한다. 같은 회원·일정의 다른 ARMED/ACTIVE
준비가 있을 때도 새 요청 ID로 조용히 탈취하거나 교체하지 않는다. 명시적 stop 또는 만료 후 다시 준비한다.
로그인 세대가 바뀐 뒤 새로 인증된 요청은 이전 준비를 종료하고 새 세대를 만들 수 있지만, 이전 세대 이벤트는 거절된다.

## API

기본 경로: `/api/schedules/{scheduleId}/automatic-journey`. 실제 API는 기존 access JWT가 필요하다.
`clientOwnerId`는 인증 토큰을 대체하지 않는 소문자 UUID이며, 모든 후속 변경의 소유 클라이언트 fence다.
성공 응답은 기존 `ApiResponse.data` 안에 들어간다.

### POST /prepare

```json
{
  "requestId": "aa67598a-6c84-4d0d-8d25-134e0c01c12e",
  "clientOwnerId": "bb67598a-6c84-4d0d-8d25-134e0c01c12e",
  "routeInputDigest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "execution": "SIMULATOR"
}
```

반환 필드: `sessionId=requestId`, `scheduleId:string`, `generation`, `routeRevision:1`, `sequence:0`,
`state:ARMED`, `clientOwnerId`, `routeInputDigest`, `preparedAt`, `expiresAt`, `execution`.
유효시간은 최초 준비부터 600초다. 같은 준비 요청의 재전송은 만료시각을 연장하지 않는다.
이미 ACTIVE/STOPPED/EXPIRED인 요청 ID 또는 변경된 소유자/digest/경로로 재사용하면 거절된다.

### POST /{sessionId}/departure

```json
{
  "generation": 1,
  "routeRevision": 1,
  "clientOwnerId": "bb67598a-6c84-4d0d-8d25-134e0c01c12e",
  "routeInputDigest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "eventKey": "aa67598a-6c84-4d0d-8d25-134e0c01c12e:1:1:AUTO_DEPARTURE",
  "sequence": 1,
  "type": "AUTO_DEPARTURE",
  "occurredAt": "2026-10-04T03:00:08.000Z",
  "execution": "SIMULATOR"
}
```

`occurredAt`은 ISO-8601 밀리초 정밀도다. `preparedAt <= occurredAt < expiresAt`이어야 하고,
서버 현재 시각보다 30초를 초과해 미래인 값은 거절한다. 저장된 offline outbox 이벤트의 지연 수신은
준비 세션의 600초 유효시간을 공유한다. 별도의 120초 수신 나이 제한은 없으므로 121~599초 늦게
도착해도 나머지 fence가 일치하고 서버 현재 시각이 `preparedAt <= now < expiresAt`이면 승인할 수 있다.
**서버 만료 시각에 도달하면 준비 기간 중 발생한 이벤트라도 신규 승인은 거절한다.**
서버 시계가 준비 이전으로 역행해도 새 이벤트는 승인하지 않는다.
generation/revision/sequence는 건너뛰거나 변경할 수 없다.

ACK: `sessionId`, `generation`, `routeRevision`, `eventKey`, `sequence:1`, `state:ACTIVE`,
`accepted:true`, `duplicate:boolean`, `departedAt`.

수신된 동일 tuple·소유자·digest·execution·발생시각이 정확히 같으면 재전송은 `duplicate:true`다.
한 번 승인된 receipt는 이후 준비 만료 또는 명시적 stop 뒤에도 반환할 수 있지만, member 로그인 세대와
현재 이동 접근 권한은 다시 검증한다. 이 ACK의 ACTIVE는 **과거 이벤트가 승인됐다는 뜻**이며,
STOPPED 상태를 되살리거나 출발 효과를 반복하지 않는다. 현재 세션 상태는 current/stop 응답을 기준으로 한다.
같은 eventKey에 다른 payload를 보내면 409를 반환한다.

### POST /{sessionId}/stop

`{generation, routeRevision, clientOwnerId, routeInputDigest}`를 받아 `state:STOPPED`인 준비 DTO를 반환한다.
이미 승인된 일정 출발을 취소하거나 알람을 재등록하지 않는다. 준비만 종료할 때도 출발 부작용은 없다.

### GET ?clientOwnerId={UUID}

본인·현재 로그인 세대·일치하는 소유 클라이언트의 최신 준비 DTO 또는 null을 반환한다.
다른 클라이언트의 owner ID를 공개하는 조회로 사용하지 않는다. ARMED가 만료됐으면 EXPIRED로 정리한다.

## 저장과 배포

새 테이블은 route fingerprint/digest, 소유자 fence, 준비/만료 시각, 한 번의 승인 receipt만 저장한다.
경로 JSON, 위치 좌표, 위치 이력, 푸시 토큰은 저장하지 않는다. 보존 만료는 준비 유효시간 종료 후 7일이며
시간당 정리한다. 계정 탈퇴 때 해당 회원 및 소유 일정의 준비/receipt를 함께 제거한다.

마이그레이션: [2026-10-04-automatic-journey-preparations.sql](migrations/2026-10-04-automatic-journey-preparations.sql).
신규 schema marker는 `2026-10-04-automatic-journey-preparations-v1`이다. runtime flag가 꺼져 있어도 entity를
배포하려면 스키마가 먼저 필요하다. 기존 migration이나 기존 데이터는 변경/백필하지 않는다.
이번 작업에서는 운영 DB나 기존 사용자 DB에 migration을 실행하지 않았다.

## 검증 결과

2026-10-04 offline outbox TTL 수정 후 재실행: **74 tests PASS, 실패/오류/스킵 0**.

- 신규 서비스 17: 준비 무부작용, flag/profile, owner/generation/revision/digest/time, 중복 ACK,
  응답 소실 후 재시도, logout/접근 회수/경로 변경, stop/만료, 오류 전 receipt 미생성.
  121/180/599초 지연 수신 승인, 600/601/3600초 미승인 이벤트 차단,
  599초에 승인된 이벤트의 TTL 경과 후 중복 ACK도 포함한다.
- 신규 MockMvc 2: 실제 controller JSON/ISO 계약, 인증 없음·잘못된 enum 거절.
- 신규 H2 3: 동시 이벤트 6개에서 승인 1회, 출발 상태·receipt 원자적 rollback 및 재시도, 개인정보 정리.
- 신규 migration 계약 1.
- 기존 수동 journey 28, production schema guard 23 회귀.

```sh
./gradlew test --tests 'com.noLate.journey.*' \
  --tests 'com.noLate.global.config.ProductionSchemaVersionGuardTest' \
  --offline --no-daemon -PexcludeMysqlTests=true
```

Gradle은 test profile을 강제한다. H2 create-drop만 사용하고 Firebase/APNs·교통 공급자·외부 API 태그를
비활성화한다. H2 동시성 검증은 운영 MySQL 동작 전체를 증명하지 않으며 실제 MySQL migration은 미실행이다.

## 시뮬레이터용 로컬 HTTP 하네스

```sh
./gradlew automaticJourneyHarness --offline --no-daemon
```

주소는 `127.0.0.1:15543`, 일정은 `9001`, synthetic actor는 `7`, 로그인 세대는 `2`로 고정된다.
하네스 코드는 test source에만 있으며 `SpringApplication.run`을 호출하지 않는다. `.env`, `env.properties`,
production application context, 실제 계정·DB·교통 공급자·알람·APNs를 사용하지 않는다.
실제 신규 controller/service/domain에 in-memory repository와 출발 효과 counter를 연결한다.
인증은 고정 테스트 principal이며 Authorization 헤더가 오면 거절한다. 일반 API 인증의 대체 수단이 아니다.

| 경로 | 용도 |
|---|---|
| GET `/__fixture/health` | 하네스 종류와 고정 fixture 일정 확인 |
| GET `/__fixture/state` | `prepareCount`, `departureCount`, `activationCount`, `cancelCount`, `lastState` 확인 |
| POST `/__fixture/reset` | 이 프로세스의 합성 메모리 상태·counter만 초기화 |
| POST `/__fixture/control` | `{mode:"NORMAL"}` 또는 아래 실패 조건 지정 |

- `FAIL_BEFORE_COMMIT_ONCE`: 다음 departure 요청을 처리하지 않고 503 한 번 반환.
- `LOSE_RESPONSE_AFTER_COMMIT_ONCE`: 다음 departure 서비스가 승인된 뒤 성공 응답 대신 503 한 번 반환.
  같은 이벤트 재시도는 `duplicate:true`, 출발/활성화 counter는 1이어야 한다.

프로세스를 종료하면 fixture 데이터는 사라진다. 하네스는 단일 스레드 fixture이므로 DB 동시성 증명에 사용하지 않는다.
실제 트랜잭션 동시성/rollback은 위의 별도 H2 테스트로 검증한다.

HTTP 재현 스크립트는 고정 loopback fixture만 받으며, 실행 전후 합성 메모리 상태를 초기화한다.
**시뮬레이터 UI E2E와 동시에 실행하지 않는다.** 실패해도 연결이 가능한 한 마지막 초기화를 시도한다.

```sh
node scripts/qa/automatic-journey-harness-smoke.mjs --reset-isolated-fixture
```

검증 대상은 준비 무부작용, 처리 전 503, 승인 뒤 응답 소실 503, 동일 이벤트 중복 ACK,
변경 payload 409, stop 이후 receipt 재전송의 상태 부활 방지, 종료 후 모든 counter 0이다.

2026-10-04 root 환경에서 새 하네스를 실행하고 HTTP smoke **PASS**를 확인했다.
출력의 `afterReset`은 prepare/departure/activation/cancel 모두 0, `lastState:NONE`이다.

독립 시뮬레이터 UI에서도 준비 ARMED(부작용 0), 위치 이동으로 자동 출발 1회, 전송 보류,
프로세스 재실행·명시 복구, 2분 넘은 사건 수신, commit 후 503/자동 재시도 ACK를 확인했다.
departure/activation/cancel counter는 각각 1을 유지했고 stop 뒤 새 위치 입력으로 부활하지 않았다.
최초 E2E에서 발견한 120초 cutoff 불일치는 600초 세션 범위로 수정 후 재시험했다.
UI 증거와 테스트 대역의 한계는 [FE P1-3 검증 기록](../../../NoLate_FE/docs/journey-engine-p1-3-validation.md)을 따른다.

## P2-3b2b2a 인증 transport 회귀 (2026-10-05)

별도 `authenticatedJourneyHarness` task는 test source의 JwtTokenProvider/test key/issuer만 사용하는
loopback 15544를 연다. 15543의 credential-free 정책은 바꾸지 않았다. 둘 다 SpringApplication,
실제 credentials/DB/교통 공급자/APNs/production configuration을 사용하지 않는다.
15544의 actual controller principal은 access JWT의 서명/issuer/type/member를 확인한 결과로 생성하며,
현재 Member 로그인 세대와 본인 일정/경로/세션 fence는 기존 actual service가 다시 검사한다.
15545 trap은 redirect가 따르거나 Authorization이 넘어오지 않는지 counter만 관측한다.

```sh
./gradlew authenticatedJourneyHarness --console plain
./gradlew test --tests 'com.noLate.journey.*' --console plain
```

2026-10-05 전체 journey **13 suites / 111 tests PASS**, 실패/오류/스킵 0.
신규 authentication 3개는 missing/malformed/foreign signature/issuer/refresh/foreign member 거절,
signed access principal 정상 요청, 현재 login-generation 불일치와 신규 세대 0의 정상 동작을 검증한다.
production SecurityFilterChain 로그인 E2E나 실제 계정 검증을 대체하지 않는다.
FE 독립 시뮬레이터에서는 합성 서명 JWT로 GPS 출발/상세/로컬 잠금 Activity·cold 복구·sg 회수·
45초 token 만료·native cross-origin redirect 차단을 확인했다. 만료/회수 token의 server stop은
거절되므로 local STOPPED와 server row state가 다를 수 있다.
위 기록은 P2-3b2b2a 시점이며 다음 foreground refresh 구현은 아래 항목과 구별한다.

## P2-3b2b2b1 foreground refresh fixture (2026-10-05)

15544 context는 별도 test issuer의 refresh JWT도 메모리 시험 source에 제공한다.
auth/refresh 시험 double은 실제 JwtTokenProvider로 서명/type/member/현재 세대/일회 소비를
검사하고 새 세트를 반환한다. production MemberUseCase/DB의 consumeAndRotate 통합 시험은 아니다.
refresh-control의 503은 token을 소비하지 않으며 reset은 token/counter/장애 상태를 초기화한다.
15543의 모든 Authorization 거절과 실제 공급자/production/DB/APNs 비사용 정책은 유지했다.

FE는 일반 API 401과 foreground 선제 갱신을 single-flight/credential revision으로 연결하고
Debug simulator의 refresh 요청을 redirect-blocking native client로 처리한다. 실제 authStorage
연결 코드는 회귀 테스트, E2E는 실제 Keychain에 쓰지 않는 합성 source로 검증했다.
독립 창 GPS에서 초기 45초 token 만료 뒤 동일 session/TTL과 출발 1회·ACK·상세/로컬 잠금 표시를
유지했고, sg 회수와 refresh 503 뒤 원래 만료 종료를 확인했다.
BE 전체 journey 회귀 13 suites / 111 tests PASS. 실제 refresh DB/실계정 E2E, 잠금 중 native 갱신,
cold/기기 소유권, 실데이터/지도 타일·승하차/음성/APNs는 남아 있으며 실행 gate는 default-off다.
