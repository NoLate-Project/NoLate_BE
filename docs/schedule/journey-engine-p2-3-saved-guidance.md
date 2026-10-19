# P2-3a 저장 경로와 native 안내 입력 검증

2026-10-04. 자동 안내 default-off. simulator/SANDBOX의 첫 연결이며 운영 배포 계약이 아니다.

## 새 준비 API

`POST /api/schedules/{scheduleId}/automatic-journey/prepare-saved`

입력은 기존 `requestId`, `clientOwnerId`, `routeInputDigest`, `execution`과 `routeInputJSON`이다.
controller의 인증 principal/login generation을 사용한다. UUID·digest·실행 플래그를 먼저 검사하고
actor를 잠근 뒤 본인의 저장 travel plan만 읽는다. 클라이언트가 schedule/member 소유자를 선택하지 않는다.

`SavedJourneyInputValidator`가 저장 plan의 독립 endpoints와 전체 route에서 native v1 allow-list를
재구성한다. context=SAVED_ROUTE, revision=1, provider/출처/품질/geometry/원본 stop 근거/IDs/방면,
배열 순서와 필드 구조를 검사한다. 정확한 UTF-8 JSON SHA-256을 요구하며 중복 키, 후행 JSON,
크기·문자열·leg/point/stop 한도 초과를 거절한다. 숫자 노드의 표기 차이만 값으로 비교한다.
여러 transitLegs container나 필수 endpoint 누락을 보완 추정하지 않는다.

검증 후 기존 `prepare`를 재사용하고 저장 route fingerprint가 준비 row와 일치하는지 다시 확인한다.
응답에 `savedRouteValidated=true`가 추가되며 기존 API는 기본 false다. ARMED/idempotency/TTL,
단일 기기 owner/login generation, 자동 출발 receipt·동기화 정책은 기존 계약을 유지한다.
준비만으로 출발 상태/알람/푸시 취소/Activity 시작을 수행하지 않는다.

이 응답은 **저장 JSON과 native 입력의 일치**를 뜻한다. 실제 provider payload 진위나 운행 지원,
버스 탑승, 전체 journey 진행, 데이터 최신성을 인증하지 않는다. native는 usable 첫 WALK와
DEBUG/simulator/SANDBOX opt-in을 별도 검사한다. proof flag는 개발용 로컬 binding의 표시 조건이며
서명된 운영 attestation이 아니다. 운영 세션/경로 변경 수명과 신뢰 모델은 후속 작업이다.

## 검증

- journey 패키지 11 classes / 101 tests PASS. 신규 validator/controller/fixture 관련 8개 포함.
- digest/geometry/endpoint/ID/context/revision/키/타입 위조, 중복/후행 JSON, 크기 제한,
  소유권·feature·login generation·기기 fence, 준비 멱등성과 출발 부작용 없음 검사.
- test-source `AutomaticJourneyHarness`의 SAVED_GUIDANCE mode는 단일 전체 합성 TMAP-shaped plan이다.
  실제 controller/service/domain + 메모리 repository + 합성 actor 7/generation 2로만 동작한다.
  production Spring context, 사용자 DB, 실제 provider/auth/APNs를 사용하지 않는다.
- 독립 시뮬레이터 최종 계수: prepare 1 / departure 1 / activation 1 / mock cancel 1,
  arrivalRequests 0. ARMED → 합성 CoreLocation 자동 출발 → WAITING → STOPPED를 확인했다.
  기존 상세 컴포넌트·로컬 잠금 Activity는 동일 native projection을 소비한다.
- 종료/위치 입력 clear 후 fixture reset: 모든 계수 0/state NONE, FRESH/MAPPED.

사진·UI·위치 만료·빌드·제약은 [FE 검증 기록](../../../NoLate_FE/docs/journey-engine-p2-3a-validation.md)에 있다.
실제 운영 일정 인증 진입/지도 오버레이/도착 관측 공통 통합, 버스 완주/지하철/음성/실제 APNs는 미검증이다.
원래 ODsay WALK 형상 차단을 완화하거나 누락된 provider ID를 만들지 않았다.

## P2-3b1 native 합성 도착정보 소비 (2026-10-05)

운영 controller/service/domain은 이번 단계에서 변경하지 않았다. test-source harness의
`SAVED_GUIDANCE_MAPPED` mode에 단일 전체 ODsay-shaped 합성 저장 route를 추가했다.
시험용 가짜 route/local station/ARS/방면·원본 승차 순번과 완전한 합성 형상을 명시한다.
이는 실제 ODsay 형상 품질 승격이나 운영 공급자 ID 생성이 아니다. 기존 SAVED_GUIDANCE와
production ODsay WALK `geometryInputComplete=false` 차단은 유지한다.

native Runtime이 기존 canonical binding/arrival API를 사용하고, 자동 출발 ACK 이후 신선한
GPS 접근/대기에서만 loopback 일정 9001을 조회한다. 동일 관측을 상세 카드·로컬 ActivityKit에 표시한다.
journey 회귀는 11 classes / 102 tests PASS. 독립 시뮬레이터에서 도착정보 표시, 잠금 중 방면 오류
숨김, 원본 정보 STALE/복구, CHANGED_ROUTE 뒤 provider 조회 40→40 차단을 확인했다.
만료 뒤 종료 시 서버 STOPPED, reset 뒤 계수 0/state NONE다. 실제 provider/auth/DB/APNs는 사용하지 않았다.

운영 인증 일정 진입·경로 invalidation과 지도 연결은 P2-3b2로 남는다.
검사·증거·정확한 제한은 [FE P2-3b1 기록](../../../NoLate_FE/docs/journey-engine-p2-3b1-validation.md)에 있다.

## P2-3b2b1 수명 검증 추가 (2026-10-05)

`POST /api/schedules/{scheduleId}/automatic-journey/{sessionId}/validate`는
AutomaticJourneyStopCommand와 같은 identity fence를 받지만 종료/출발 부작용은 없다.
principal/login generation·본인 일정 접근·저장 route fingerprint·최신 세션·execution gate·원래 TTL을
재검증하고 schemaVersion/session/schedule/generation/revision/owner/digest/execution/state 및
validatedAt/validUntil을 반환한다. validUntil은 min(검증 시각+30초, 원래 세션 만료)이다.
기존 bus observation도 같은 공통 fence를 쓰며 첫 WALK→BUS 제약은 그대로 유지한다.

Debug simulator 고정 loopback worker와 명시 cold 복구에서 이 계약을 소비했다.
잠금 중 경로 교체/합성 account generation 무효화·GPS 없는 lease 만료 종료를 독립 창에서 확인했다.
실제 auth refresh/native token transport·APNs·운영 일정 진입은 아직 구현/검증 완료하지 않았다.
계정이 무효화되면 stop 요청도 거절되므로 local revoke 완료와 server row STOPPED를 구분한다.

BE 전체 journey 회귀 12 suites/108 tests PASS, Swift 239/FE 2,500 PASS.
FE의 `docs/journey-engine-p2-3b2b1-validation.md`에 정확한 E2E 범위/증거/남은 게이트를 기록했다.

## P2-3d3a cold/foreground terminal 기록 자동 정리 (2026-10-06)

`POST /api/schedules/{scheduleId}/automatic-journey/{sessionId}/reconcile`는 같은
AutomaticJourneyStopCommand의 generation/revision/owner/digest를 받고 상태만 반환한다.
기능/실행 gate·principal/login generation·활성 계정·본인 일정 접근·정확한 세션 소유권과 fence를
검사한다. 최신 세션이 아닌 소유 historical terminal binding도 조회할 수 있으나 다른 세션을 건드리지 않는다.

응답은 schemaVersion=1, sessionId/scheduleId/generation/routeRevision/clientOwnerId/
routeInputDigest/execution/state/checkedAt/endedAt이다. validate와 달리 validUntil/lease를
발급하지 않으며 저장/expire/stop/출발/푸시 부작용이 없다. TTL이 지난 ARMED도 서버 저장 상태가
ARMED인 동안 그대로 반환한다. ACTIVE TTL 정리는 별도 작업이다.

FE는 동일 인증 identity와 최근 checkedAt·정확한 binding을 검사하고 STOPPED/EXPIRED의 endedAt까지
확인한 경우에만 native scoped compare-and-revoke 뒤 JS 기록을 지운다. ACTIVE/ARMED는 보존하고
위치를 자동 복구하지 않는다. 조회 실패/401/404/다른 계정/로그인 세대는 종료 증거가 아니다.

test-source authenticated harness에 조회 503 토글/안전 계수를 추가했다. production DB/실제 일정은
사용하지 않았다. automatic 및 Firebase client 회귀 11 suites/70 tests PASS(새 상태 조회 검사 6개).
독립 시뮬레이터에서 실제 단일 설치 APNs END 후 재시작·503 보존·foreground 회복 자동 정리·새 준비/종료를 확인했다.
최종 STOPPED, prepare=2/departure=1/activation=1, provider 수락=6/실패=0/END=1, trap=0.

범위·화면·실기기 후속 절차는 [FE 자동 정리 검증](../../../NoLate_FE/docs/journey-checkpoint-reconciliation-validation-20261006.md)에 기록했다.
운영 플래그는 default-off다. durable sender/새 로그인 세대/서버 재시작·실제 인증 일정 진입은 이 slice 완료에 포함하지 않는다.

## P2-3d3b 서버 ARMED/ACTIVE TTL 첫 slice (2026-10-06)

`AutomaticJourneyExpiry`는 guidance/automatic/expiry 세 opt-in이 true일 때만 기본 10초 간격으로
due ARMED/ACTIVE를 EXPIRED로 변경한다. 기존 expiresAt/receipt는 보존하고 endedAt을 기록한다.
조건부 JPQL UPDATE에 version 증가를 포함해 이전 객체의 save가 terminal을 ACTIVE로 되돌리지 못하게 한다.
STOPPED/EXPIRED는 변경하지 않는다. `expire()`도 ACTIVE를 지원하며 `/reconcile`는 계속 read-only다.
worker에는 출발/푸시/알람 부작용이 없고 durable remote END는 아직 연결하지 않았다.

`journey.automatic.expiry-enabled` 기본 false다. 시험 harness만 모든 gate를 명시 ON으로 사용한다.
`journey.automatic.isolated-ttl-seconds` 기본 600초/격리 SIMULATOR만 60~600초 허용, DEVICE는 600초를 유지한다.
test-source `NOLATE_JOURNEY_TEST_SHORT_TTL=1`은 60초 실시간 E2E용이다. 운영 설정/DB/키를 변경하지 않았다.

automatic/Firebase 회귀 12 suites/77 tests PASS. 실제 H2 쿼리·stale writer 거절·terminal/receipt 보존·worker 재구성 멱등성 포함.
독립 시뮬레이터에서 앱 미실행 ACTIVE와 ARMED 각각 만료→cold 자동 정리→새 준비/종료를 확인했다.
최종 준비 3·출발/활성화/알림 취소 1·만료 2·STOPPED/큐 0, 외부 provider 호출 0·trap 0이다.
이번 GUI의 repository는 메모리 mock이며 SQL 검사는 별도 H2다. MySQL/process restart·운영 부하·장시간 실제 안내 수명·TTL→remote END 성공으로 승격하지 않는다.

[FE 서버 TTL 검증](../../../NoLate_FE/docs/journey-server-expiry-validation-20261006.md)에 시험 ON/운영 OFF와 증거·후속 게이트를 기록했다.

## P2-3d3c test-source 만료 자동 APNs END (2026-10-06)

이번 변경은 `JourneyGuidanceApnsPilot` 및 authenticated harness/test에 한정한다. 운영 TTL worker는 여전히 원격 END를 발송하지 않는다. `NOLATE_JOURNEY_TEST_EXPIRY_END=1` 명시 opt-in에서 timer가 만료 worker 뒤 정확한 pinned binding의 저장 EXPIRED receipt/endedAt을 확인하고 자동 END를 보낸다. 메모리 소유 lane은 최대 16개다.

자동 END는 stop을 호출하지 않고 EXPIRED/원래 endedAt/receipt를 보존한다. historical binding 정리가 새 generation을 중지하지 않는다. 같은 초는 지연하고 미래 timestamp를 만들지 않는다. provider I/O 전 예약으로 성공 중복·모호한 수락/확정 거절 뒤 반복을 차단한다. token/예외 원문 없이 safe accepted/blocked/failed/unknown 계수를 제공한다. durable retry/outbox 및 token 회전은 구현하지 않았다.

새 검사 7개 포함 automatic/Firebase 12 suites/84 tests PASS. FE 2,697·Swift 269·Node QA 15/TypeScript PASS. 독립 simulator에서 실제 60초 자연 만료→자동 provider END 수락→사용자 재진입 전 잠금 카드 제거 2회, 재진입 자동 정리·새 준비/종료를 확인했다. 앱 terminate 직후 service 부재였으나 END 이후 새 PID도 관측됐다. 원격 종료 중 앱 HTTP 인증 요청은 불변이며 OS 프로세스 미생성을 주장하지 않는다.

최종 prepare=3/departure=2/expiry=2, provider 시도/수락 11·실패 0·자동 END 2, STOPPED/큐 0·trap 0이다. 실제 일정/운영 인증/DB는 사용하지 않았고 운영 설정/콘솔/키를 변경하지 않았다. 메모리 sender 재구성 시 destination이 없어 전송하지 않는 검사는 process restart 복원 성공이 아니다.

[FE 자동 END 기록](../../../NoLate_FE/docs/journey-expiry-auto-end-validation-20261006.md)에 화면 증거·관측 제한·시험 절차를 기록했다. 다음은 운영 durable token ownership/terminal outbox·재시작/회전/경쟁·오프라인 fallback이다.

## P2-3d3d main-source 영속 Activity 소유권·END (2026-10-07)

`JourneyActivityDeliveryService`/controller는 `POST .../live-activity` 및 `POST .../live-activity/end`를 제공한다. `journey.activity.enabled=false` 기본과 외부 `journey.activity.encryption-key`(base64 32-byte AES key) 필수 조건을 유지한다. 운영 DB/플래그/환경·키 설정은 변경하지 않았다. 수동 migration `2026-10-06-journey-activity-lanes.sql`과 schema marker가 필요하며 기존 DB에 자동 DDL을 적용하지 않는다.

main-source `journey_activity_lanes`는 정확한 actor/login/binding/Activity/token fingerprint와 등록된 iOS FCM token ID/ownership version/device fingerprint를 고정한다. token 원문은 AES-256-GCM/session AAD로 암호화하고 원시 GPS는 저장하지 않는다. 기존 FCM dispatch lease를 UPDATE/END에서 공유하고 계정·기기 소유권 이전을 재검증한다. Activity token invalid로 일반 FCM을 임의 retire하지 않는다. 7일 보존은 UPDATE로 늘리지 않으며 계정/일정 cleanup에 포함한다.

terminal dispatcher는 저장 EXPIRED/STOPPED·원래 endedAt을 reconcile한 뒤 READY/IN_FLIGHT를 DB commit하고 provider I/O를 수행한다. 같은 초 지연, ACCEPTED 반복 차단, 중단된 IN_FLIGHT→UNKNOWN, 수락 불명/확정 거절 반복 금지를 적용한다. provider 수락은 deviceVerified가 아니다. 기존 departure 위젯 sender/wire와 분리되며 immutable lane의 token 회전은 아직 거절한다.

현재 전체 journey 회귀 25 suites/214 tests PASS(failure/error/skip 0). 그중 실제 disposable MySQL 8.4에서 두 reviewed migration+Hibernate validate·manager 재생성·동시 worker 단일 claim·ACCEPTED 재시작 후 전송 0을 검증했다. 이 provider는 합성이며 사용자 DB에 접근하지 않는다.

별도 독립 simulator E2E는 test-source H2 file DB/합성 actor·등록 proof와 main-source dispatcher를 연결했다. GPS ACTIVE→앱 terminate/service 부재→서버 PID 57997 종료→같은 DB/key로 PID 60175 재시작→EXPIRED 복원/실제 APNs END 1 수락·잠금 카드 제거를 확인했다. 새 process의 auth/reconcile 요청 0, 사용자 앱 재진입 없음. 다시 서버 restart 후 DB ACCEPTED 1/provider 시도 0·카드 미재생성, cold terminal 자동 정리/새 ARMED·정상 종료 큐 0도 통과했다. APNs 뒤 OS가 새 앱 PID를 생성할 수 있음을 관측했다.

[FE 영속 END 기록](../../../NoLate_FE/docs/journey-durable-end-validation-20261007.md)에 정확한 범위·실패/중간 캡처·화면 증거와 재현 절차를 기록했다. 운영 MySQL→실제 등록 DB→실제 APNs 전체 통합/배포, token 회전·새 로그인 회수·오프라인 stale·장시간 안내 수명·실기기·실제 버스 완주/P3/P4는 미완료다.
