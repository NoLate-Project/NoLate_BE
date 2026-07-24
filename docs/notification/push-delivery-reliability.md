# Push delivery reliability boundary

FCM 호출과 애플리케이션 DB commit은 하나의 원자 트랜잭션이 아니며, FCM 단일 토큰 전송은
서버가 지정한 idempotency key를 제공하지 않는다. 따라서 이 구현은 exactly-once를
주장하지 않고, 같은 논리 이벤트를 성공했을 가능성이 있는 기기에 다시 보내지 않는
at-most-once를 우선한다.

## 상태와 재시도

- `PENDING`: 최초 event linearization transaction이 그 시점의 대상 기기 전체를 만든
  immutable manifest다. 아직 외부 호출 경계를 만들지 않았으므로 다음 실행이 안전하게
  claim할 수 있다.
- `DISPATCHING`: provider 호출 전에 독립 트랜잭션으로 기록한다. 성공 응답 직후 프로세스가
  종료된 경우와 호출 직전 종료를 구분할 수 없으므로 자동 재전송하지 않는다.
- `SUCCESS`: provider message id를 받은 확인된 성공이다. 같은 회원·이벤트·기기는 건너뛴다.
- `FAILED`: provider가 예외를 명시적으로 반환한 확인된 실패다. 이 상태의 기기만 다음 실행에서
  다시 `DISPATCHING`으로 선점해 재시도한다.
- `INVALID_TOKEN`: 토큰을 제거하고 같은 이벤트에는 다시 보내지 않는다.
- `EXHAUSTED`: 확인 실패가 기기별 최대 provider attempt 수에 도달한 terminal 상태다.
  다른 저시도 기기의 늦은 실패가 source를 깨워도 이 기기는 다시 claim하지 않는다.
- `SUPERSEDED`: manifest가 저장한 token id/fingerprint/ownership version과 현재 소유권이
  달라 provider를 호출하지 않고 종료한 상태다. 계정 전환 뒤 이전 회원의 알림이 새 소유자
  token으로 전송되는 것을 막는다.

명시적인 공급자 오류 코드가 없는 transport 예외는 수락 여부가 모호하므로 delivery row를
`DISPATCHING`으로 유지하고 `push_send_history.status=UNKNOWN`으로 관측한다. 이 결과도 자동
재전송하지 않는다.

여러 기기 중 일부만 성공하면 `SUCCESS` 기기는 유지하고 `FAILED` 기기만 재시도한다.
inbox deduplication key는 같은 논리 알림 row로 수렴하며, `push_deliveries`의 기기별 상태가
외부 호출 중복까지 차단한다.

`app_notifications`는 inbox이면서 immutable outbox snapshot이다. 최초 event transaction이
title/body/canonical data와 그 시점의 대상 기기 전체 `PENDING` manifest를 함께 commit하고
`manifest_state=FROZEN`으로 닫는다. 동일 event 재시도는 현재 token/ETA/decision을 다시
조회해 event를 확장하지 않고, 저장된 delivery id와 payload만 redrive한다. 이후 등록한 기기,
다른 계정으로 이전한 기기, 삭제한 token은 과거 event의 수신자로 추가되지 않는다. 이전/삭제된
snapshot row도 ownership 검증에서 `SUPERSEDED`로 terminal 처리하므로 event가 영구 대기하지
않는다.

대상 기기가 0개인 최초 snapshot도 `FROZEN + manifest_recipient_count=0`인 명시적 terminal
event다. 나중에 기기가 등록돼도 같은 logical event key로 보내지 않는다. `OPEN`은 같은
transaction 안에서만 보이는 생성 중 상태이고 정상 commit 상태가 아니다. outbox row와
manifest를 나누어 commit하지 않으므로 생성 중 process crash는 transaction 전체를 rollback한다.
preview/manual 쓰기로 비정상 `OPEN`이 commit된 경우에도 복구는 이미 저장된 delivery row만
그대로 동결하며, row가 0개면 zero-device로 닫는다. 현재 token을 과거 event에 뒤늦게 붙이지
않는다.

일정 공유와 참여자 출발 알림도 business transaction 안에서는 immutable outbox/manifest만
저장한다. 외부 provider 호출은 commit 뒤 bounded claim/lease drainer가 수행한다. drainer가
시작하기 전 프로세스가 종료돼도 `PENDING` outbox가 restart 뒤 발견되고, 확인된 `FAILED`
delivery만 재시도한다. 여러 인스턴스는 outbox lease와 기기별 `DISPATCHING` 경계를 공유한다.
완료/재시도 전이는 `workerId + dispatchAttemptCount`를 함께 검증하므로 같은 worker ID가 stale
lease를 다시 잡는 ABA 상황에서도 이전 attempt가 새 lease를 덮어쓰지 못한다.

provider 호출이 event lease timeout보다 오래 걸리면 replacement worker는 남은
`DISPATCHING`을 ambiguous terminal로 관측할 수 있다. 이후 원 provider 호출이 확정 미수락으로
돌아오는 경우에는 authoritative schedule state를 다시 잠가 판단한다. 같은 generation/input의
`ACTIVE` job만 `delivery=FAILED`와 frozen safety outbox `PENDING`을 같은 transaction에서
재개방한다. `PROCESSING`은 현재 source worker가 끝날 때까지 failure budget 없이 defer하고,
`COMPLETED/FAILED/CANCELED`, missing job, generation/fingerprint mismatch는 delivery를
`SUPERSEDED`, outbox를 `COMPLETED`로 즉시 닫는다. 따라서 snooze/편집/terminal job의 오래된
알림은 late failure로 되살아나지 않는다. 이 규칙은 성공 가능성이 모호한 transport 예외에는
적용하지 않는다.
schedule generation/fingerprint safety fence는 event identity만 증명하고 outbox lease 소유권은
증명하지 않는다. provider 결과 전이는 locked source row의
`notificationId + workerId + dispatchAttemptCount`까지 별도로 비교한다. 현재 attempt가
일치할 때만 바깥 drainer에 retry/complete를 맡기며, lease를 잃은 늦은 결과는 source를 직접
깨운다.

같은 overlap에서 원 provider가 늦게 확인 성공한 경우에는 delivery `SUCCESS`와 schedule
confirmed-state reconciliation용 outbox wake-up을 함께 기록한다. wake-up redrive는 이미
`SUCCESS`인 기기를 provider에 다시 보내지 않고, 저장된 event identity/payload로
`last_pushed_at`과 confirmed reminder 필드만 멱등 보정한다. source job이 아직
`PROCESSING`이면 optimistic version을 선점하지 않고 reconciliation을 재예약한다. 이미 다음
generation 또는 두 회차 이상 진행된 경우에는 현재 reminder 의미를 과거 event로 덮지 않고
확인 성공 시각만 보존한다. `last_uncertain_at`은 당시 stale worker의 관측 이력으로 남는다.

모든 사용자 대상 canonical data에는 다음 계정 binding이 있다.

- `logicalEventKey`: `push_deliveries.event_key` 및 outbox의 durable event key와 동일하다.
- `recipientMemberId`: 실제 수신 회원 ID다.

route setup, 일정 공유, 출발 action/navigation 알림에도 같은 계약을 적용한다. 클라이언트는
현재 로그인 회원과 `recipientMemberId`가 다르면 오래 남은 OS 알림 action을 실행하지
않아야 한다.

route setup reminder는 현재 batch의 marker 집합을 event key로 쓰지 않는다. persisted marker
PK 하나가 `route-setup:<member>:marker:<id>` 하나에 영구 대응한다. A의 일부 기기 실패 뒤 B가
새로 due되어도 A는 기존 frozen payload/manifest에서 실패 기기만 redrive하고 B는 별도 event로
발송한다. 이 선택은 회원별 묶음 알림 대신 marker별 알림을 만들지만 성공 기기의 과거 reminder
중복을 막는다.

marker scheduler는 비잠금 후보 조회 뒤 `member → marker → immutable outbox`만 짧은
transaction에서 수행하고 marker를 `SENT`(durable enqueue 완료 의미)로 닫는다. provider I/O는
공용 outbox drainer가 transaction 밖에서 수행하므로 느린 FCM 호출이 route/ETA marker lock을
유지하지 않는다.

## 알림 action idempotency

`depart-now`와 `snooze`는 선택적인 `Idempotency-Key`를 받는다. 알림 action에서 FE는
각각 `departNow:<logicalEventKey>`, `snooze:<logicalEventKey>`를 보내며, 서버는
`memberId + scheduleId + action type`에 bind한다. legacy 수동 앱 호출은 header 없이
기존 동작을 계속 사용할 수 있다.

서버는 key 원문 대신 case-sensitive SHA-256 지문만 저장한다. 지문은 전역 unique라 같은
key를 다른 회원·일정·action에 쓰면 409로 닫힌다. 자유 입력/PII가 key 저장소로 들어오지
않도록 action prefix와 `key:<sha256>` 또는 `event:<uuid>` suffix만 허용한다.

receipt는 mutation 전에 insert/flush해 동시 요청의 winner를 하나로 만들지만,
`schedule_notification_action_receipts` 완료와 실제 출발/snooze mutation은 같은 DB
transaction에서 commit된다. 예외나 프로세스 종료가 있으면 둘 다 rollback하므로 committed
`PENDING` 또는 mutation-only 창이 없다. 같은 snooze key 재시도는 저장된
`result_snoozed_until`을 반환하고 시간을 다시 뒤로 밀지 않는다. depart-now 재시도도
출발 mutation/이벤트를 반복하지 않고 현재 authoritative schedule 결과를 반환한다.
security filter가 검증한 signed `sessionGeneration`은 header 유무와 관계없이 controller에서
실제 action transaction까지 전달한다. depart-now는 잠금 없는 recipient preview 뒤 actor를
포함한 전체 member id를 오름차순으로 잠그고 actor active/generation을 재검증한 다음
receipt와 schedule/job을 처리한다. 서로 다른 참가자가 동시에 action을 보내도
`actor → 다른 recipient` 역순을 만들지 않는다. snooze도 같은 generation을 member lock
안에서 검증한다. 최초 preview의 recipient 집합은 action fence에 동결한다. 잠금 뒤 revoke된
수신자는 최신 권한과의 교집합에서 제외하지만, preview 뒤 새로 grant된 낮은 ID 회원은 이번
action에 편입하거나 두 번째 member lock으로 획득하지 않는다.

MySQL action transaction은 `READ_COMMITTED`로 실행한다. 권한 회수가 recipient member lock을
먼저 얻었다면 대기 중이던 departure가 schedule/share grant를 최신 statement에서 다시 읽어
회수된 recipient 상태나 outbox를 만들지 않는다. action이 전체 member lock을 먼저 얻었다면
논리적 departure가 먼저이고 revoke가 그 뒤 cleanup한다. receipt의 authoritative lock 순서는
`sorted recipient members → receipt → schedule/share/job`이다. withdrawal이 먼저면 action
writer는 401로 종료해 receipt/job을 재생성하지 않고, action이 먼저면 withdrawal이 기다렸다가
같은 transaction 결과를 모두 정리한다.

## 운영 SQL 로그 비밀 경계

P6Spy listener는 성공 SQL과 SQLException 모두 `sqlWithValues`, SQL 원문, driver message,
stack trace를 출력하지 않는다. 테이블별 redact 목록은 신규 테이블을 놓치므로 사용하지 않고,
parameterless SQL에서 문자열 literal과 주석을 제외해 분류한 bounded `operation/table`만
허용한다. 분류가 실패해도 값이 있는 SQL로 fallback하지 않고 `UNKNOWN/unknown`으로 닫는다.
오류 관측에는 SQLState, vendor code, exception class만 추가한다.

따라서 `app_notifications`의 title/body/data JSON/logical key, refresh JWT, push token/device,
회원 password/social identifier뿐 아니라 이후 추가되는 테이블의 값도 기본적으로 로그에
들어가지 않는다. event 단위 운영 상관관계가 필요하면 raw logical event key가 아니라 별도의
bounded fingerprint만 사용한다. 운영에서는 Hibernate `SqlExceptionHelper`도 끄므로 JDBC
duplicate/deadlock 원문이 우회 출력되지 않는다.

## Ephemeral 진단 경로

인증된 공개 `POST /api/notifications/test/send`도 제품의 다른 사용자 대상 발송과 동일하게
`persistInInbox=true`인 durable outbox/frozen manifest 경로를 사용한다. security filter를
통과한 뒤 withdrawal이 먼저 commit되면 recipient fence가 provider 호출과 notification row
재생성을 막고, 발송 claim이 먼저 linearize된 경우에만 이미 시작된 provider 호출 residual이
남는다.

`persistInInbox=false`인 ephemeral 전송은 조건부 `/api/dev/push-scenarios` 수동 E2E Runner만
사용한다. 이 Runner는 `notification.push-scenario.enabled=true`를 명시한 비운영 환경에서만
bean과 endpoint가 생긴다. Runner와 controller 모두 `!prod` profile 경계를 가지므로 운영
환경변수/CLI가 property를 `true`로 덮어써도 bean이 생성되지 않으며, prod 설정도 해당 값을
`false`로 고정한다. 따라서 운영 공개 API가 current-token 직접 전송 경계를 우회할 수 없다.

## 세션과 push token 등록 fence

JJWT의 `iat`는 초 단위라 같은 초에 발급한 logout 전/후 token을 확실하게 순서화할 수 없다.
따라서 access/refresh JWT에는 서명된 단조 `sg`(`sessionGeneration`) claim을 넣고 security
filter가 이를 `MemberPrincipal`로 전달한다. 실제 token write transaction은 다음 전역 lock
order를 지킨다.

1. `member` row pessimistic lock
2. logout이면 회원별 refresh row, registration이면 token/device fingerprint 후보 row를 lock
3. refresh/push token mutation

lock 안에서 회원 active 여부와 JWT generation이 `member.session_generation`과 정확히 같은지
다시 검증한 뒤에만 token을 변경한다. generation 할당은 다음 상태기를 따른다. 최초 explicit
login은 migration/default g0에서 g1을 열고, 활성 refresh session A가 있는 상태의 explicit
재로그인은 member와 refresh row를 잠근 뒤 g2로 진행하며 A refresh를 B token으로 교체한다.
logout이 이미 g1을 revoke하면서 빈 g2를 열었다면 다음 login은 g3로 건너뛰지 않고 그 g2를
즉시 사용한다. `token-login`/`refresh` rotation은 현재 refresh row를 잠가 교체하지만 generation은
진행하지 않는다.

endpoint별 계약은 다음과 같다.

- `POST /api/member/auth/logout`: body의 signed refresh JWT가 제시한 member/`sg`와 현재 활성
  refresh row의 raw token이 모두 일치할 때만 generation을 한 번 증가시키고 해당 회원의
  refresh/device token을 삭제한다. raw token 비교는 회원별 refresh row를 잠근 뒤 메모리에서
  수행해 token을 SQL 조건이나 로그에 싣지 않는다. 응답 유실로 같은 요청이 반복되거나 A의
  g1 logout이 B의 g2 발급 뒤 도착하면 HTTP 성공 no-op이며 g2 generation/token은 변하지 않는다.
  같은 generation이어도 이미 rotation으로 교체된 raw refresh token은 현재 session을 revoke할
  수 없다.
- access JWT는 별도 blacklist row를 만들지 않는다. 위 성공한 logout의 generation 증가로
  해당 generation의 모든 access JWT가 즉시 인증 실패하며, refresh row도 같은 transaction에서
  삭제된다. 서명/issuer/만료/type을 검증할 수 없는 문자열은 account-wide revoke 권한이 없고
  성공 no-op으로 처리한다.
- `DELETE /api/member/withdraw`: 현재 access `MemberPrincipal`의 `sg`를 member row lock 안에서
  다시 비교한 뒤에만 비밀번호 검사와 계정 정리를 진행한다. filter 통과 후 지연된 stale
  withdrawal은 logout처럼 성공 처리하지 않고 401로 fail closed하며, 새 generation/session이나
  계정 데이터를 절대 삭제하지 않는다. 성공한 withdrawal만 generation을 증가시키고 모든
  refresh/device token과 계정 데이터를 같은 transaction에서 정리한다. withdrawal fence는
  member generation과 refresh만 먼저 닫고, provider 관련 정리는 worker와 같은
  `member → schedule job → immutable source → delivery/history → device token` lock 방향으로
  수행한다. provider가 이미 진행 중이면 외부 호출 자체는 취소할 수 없지만, 늦은
  SUCCESS/FAILED/INVALID/UNKNOWN 결과 writer가 member active fence에서 멈추므로 cleanup 뒤
  source/delivery/history/token row를 재생성하지 않는다.
  일반 logout의 `member → refresh → device token` 단축 경로와 섞지 않는다.

register가 먼저 선형화되면 뒤의 같은-generation logout이 방금 등록된 token을 삭제하고,
logout이 먼저면 기다리던 old register가 generation mismatch로 거절된다. 바로 이어진 새
로그인은 증가한 generation으로 즉시 등록할 수 있어 같은 초 `iat` 정밀도에 영향을 받지 않는다.
명시적 첫 로그인은 migration/default 0에서 g1을 열고, 활성 refresh session을 교체하는
재로그인은 같은 member row lock 안에서 다음 generation을 연다. logout이 이미 다음 빈
generation을 열어 refresh row를 삭제했다면 그 직후 로그인은 다시 증가시키지 않고 그 세대를
사용한다. 따라서 A g1 logout 요청이 네트워크에서 지연된 사이 B 재로그인이 g2를 열면, 늦은
A 요청은 generation mismatch로 성공 no-op이고 B access/refresh/register는 그대로 유효하다.
refresh rotation만 같은 generation을 유지한다.
기존 generation claim 없는 token은 배포 후 인증/재발급에서 fail closed하고 재로그인이 필요하다.
정상 legacy refresh token의 logout 요청은 서명·issuer·만료·refresh type과 DB 소유권을 확인한
뒤 migration generation 0에 bind한 cleanup 전용 경로로만 처리한다. 이 경로는 member row lock
안에서 generation을 올리고 refresh/device token을 함께 삭제한다. 또한 최초 v4 운영 배포는
quiesce 뒤 모든 legacy `push_device_token` row를 운영자가 명시적으로 전량 drain하고 0건을
검증해야 한다. migration은 자동 삭제하지 않고 한 row라도 남으면 marker 생성 전에 fail
closed한다. 따라서 만료됐거나 저장 row가 사라져 신뢰할 수 없는 legacy logout 요청도 provider
endpoint를 배포 경계 너머에 남기지 않는다.

device fingerprint는 platform과 무관한 전역 installation identity다. deviceId는
대소문자와 앞뒤 공백을 포함한 UTF-8 byte 그대로 fingerprint하며 trim/case-fold하지 않는다.
A 계정의 같은 device가 B 계정에서 등록되면 한 transaction이 기존 row를 B/token으로
이전하거나 stale 중복을 제거한다.
token/device fingerprint의 global unique index는 raw opaque 값을 SQL index/duplicate-key
메시지에 싣지 않으면서 최종 소유자가 하나임을 보장한다.

일정 알림 dedupe input은 `job id + notification generation + check count`이며, 여기서 도출한
`logicalEventKey`를 실제 event key로 쓴다. 시작 시각, 목적지, 현재 회원의 출발지/경로,
이동수단, 알림 정책 같은 의미 input의 deterministic fingerprint가 달라질 때만 generation과
check/stage를 reset한다. 동일 PUT과 notes/category 같은 비알림 메타 편집은 상태를 보존한다.

편집과 provider 전송의 linearization fence는 `schedule_push_job` row의 pessimistic lock,
worker lease, optimistic job version, generation, notification input fingerprint를 함께 검증한다. 편집 lock이
먼저면 old provider call은 0회다. 기기 `DISPATCHING` fence가 먼저 commit되면 그 old immutable
event가 논리적으로 먼저이고, 이후 편집이 새 generation을 연다. token manifest에는 raw
token 대신 token row id, case-sensitive SHA-256 fingerprint, ownership version을 저장하고
provider 직전에 현재 ownership을 다시 검증한다.

SchedulePushJob은 결과 의미도 구분한다. 확인된 새/기존 성공만 `last_pushed_at`을 갱신한다.
`DISPATCHING` 재관측 같은 ambiguous 결과와 inbox/device dedupe는 이벤트 단계 진행에는
사용할 수 있지만 성공 지표로 기록하지 않는다. 확인된 `FAILED` 기기가 하나라도 남으면
check count를 진행하지 않고 같은 event key로 job을 재예약한다.

worker는 한 run에서 최대 `schedule.push.batch-size`건을 처리하지만, 매 job 처리 직전에
한 row만 `PROCESSING`으로 claim한다. 느린 provider 앞에서 tail 49건을 미리 lease하지
않으므로 다른 인스턴스가 남은 backlog를 처리할 수 있고 tail lease timeout도 발생하지
않는다. stale recovery도 같은 batch bound를 사용한다. provider 직전 fence heartbeat는
detached worker snapshot의 optimistic version을 바꾸지 않고 lease 시각만 갱신한다.
공용 outbox도 `dispatch_attempt_count`(lease epoch)와
`dispatch_failure_count`(실제 실패 예산)를 분리한다. authoritative schedule job이
`PROCESSING`인 정상 대기는 attempt epoch만 진행하고 failure budget을 소비하지 않아 600초
source recovery보다 먼저 outbox가 영구 `FAILED`가 되지 않는다.

## Schedule/account transition fence

`POST /api/schedules`와 `/api/schedules/import`는 security filter가 검증한 signed
`sessionGeneration`을 controller에서 버리지 않고 use case로 전달한다. outer schedule write
transaction의 첫 DB 연산은 `member FOR UPDATE + active/deleted + generation` 재검증이며,
그 lock을 schedule, owner travel plan, push job commit까지 유지한다. 생성이 먼저면
withdrawal이 기다린 뒤 방금 만든 schedule/job/outbox까지 삭제하고, withdrawal/logout이 먼저면
filter를 이미 통과한 stale create/import도 `INVALID_TOKEN`으로 끝나 어떤 row도 재생성하지
않는다. 전역 순서는 `member → schedule/travel-plan → push-job/outbox`다.

일정 update/delete fence는 잠금 없는 상세 조회로 actor/owner identity, 기존 job member와
알림 활성 travel-plan member를 미리 찾고 그 전체를 정렬 잠근 뒤 schedule별 push-job
row/gap을 잠근다. gap 획득 직후 job member를 다시 읽어 선잠금 집합 밖 회원이 보이면 gap
뒤에서 새 member lock을 잡지 않고 transaction을 재시도 가능 오류로 rollback한다. 이어
schedule row를 잠근 뒤 알림 활성 plan member도 같은 부분집합 조건으로 재검증한 다음에만
mutation한다. 따라서 아직 job이 없는 participant plan도 edit과 register/backfill 사이에서
`member → job → schedule` 순서를 뒤집지 않는다. 공유/calendar 권한 회수도 대상 member들을
resource/share보다 먼저 잠그고 그 뒤 revoked travel push-job을 취소한다.

startup legacy drain은 전체 후보를 하나의 transaction으로 처리하지 않는다. 짧은 read-only
scan은 불변 `(memberId, scheduleId, travelPlanId)`만 정렬해 반환하고, 후보별
`REQUIRES_NEW` writer가 `member → current schedule/plan 재검증 → push-job` 순서로 하나씩
commit한다. 다중 인스턴스가 같은 후보를 읽어도 member/job lock에서 수렴하며, scan 뒤
withdrawal/edit가 먼저 끝난 stale 후보는 새 job을 만들지 않는다.

Snooze는 단순히 `next_check_at`만 이동하지 않고 `notification_generation`을 증가시키고
check/stage를 reset한다. 이미 `SUCCESS`인 old delivery는 감사 결과로 보존하지만 old
`PENDING/FAILED/DISPATCHING`은 새 generation 전에 provider로 재전송되지 않고 terminal
`SUPERSEDED`로 수렴한다. snooze 만료 뒤에만 새 generation/event key가 만들어질 수 있다.

## 운영 복구

오래 남은 `DISPATCHING`은 자동으로 `FAILED`로 바꾸지 않는다. 먼저 `push_send_history`의
성공 row와 provider 관측 자료를 확인한다. 성공 여부를 증명할 수 없으면 중복 방지를 위해
그대로 억제한다. 사용자에게 다시 알려야 한다는 제품 판단이 있으면 기존 event key를 재사용하지
말고 새 event key의 보상 알림을 생성한다.

event/manifest는 한 transaction으로 commit되므로 생성 중 종료되면 둘 다 rollback되고,
commit 뒤 종료되면 frozen manifest 전체가 그대로 남는다. 한 기기 처리 중 종료돼도 나머지
`PENDING` 기기는 계속 전송할 수 있다.

ambiguous event는 중복 방지를 위해 논리 reminder 단계만 `last_handled_*`에 전진시킨다.
`last_pushed_at`, `last_notified_*`, `departure_notice_sent_at`,
`last_departure_reminder_stage` 같은 확인 성공 필드는 바꾸지 않는다. ambiguous
`DEPART_NOW`도 후속 +3/+7분 정책에서 이미 처리된 초기 경계로 보되, 운영 지표는
`last_uncertain_at`으로 분리한다. 같은 immutable event를 redrive하는 사이 live decision이
다음 경계로 바뀌면 기존 event를 먼저 terminal 처리하고 1초 뒤 새 check/event를 예약한다.

이 정책의 남은 장애 창은 provider 호출 전에 `DISPATCHING`을 기록한 직후 프로세스가 종료되는
경우다. 실제 FCM 호출이 없었어도 재전송하지 않으므로 알림이 손실될 수 있다. 반대로 provider가
실패를 반환한 뒤 `FAILED` 기록에 실패하면 상태가 `DISPATCHING`으로 남아 역시 자동 재시도되지
않는다. 이는 공급자 idempotency가 없는 환경에서 중복 0을 우선한 의도적인 선택이다.

## 배포와 rollback

이 변경의 상세 운영 절차는
[`push-reliability-production-rollout.md`](./push-reliability-production-rollout.md)를
따른다. 핵심은 rolling schema 변경이 아니라 모든 old API/worker writer를 먼저 quiesce한
maintenance 배포라는 점이다. 3개 SQL의 pre/post verification을 통과해야
`2026-07-24-push-reliability-v4` marker가 생기며, 운영 애플리케이션은
`ddl-auto=validate`, `sql.init.mode=never`와 이 marker를 모두 확인하지 못하면 시작하지 않는다.

마지막 migration은 legacy delivery/event key, legacy schedule push job 또는 legacy push
token이 단 한 row라도 남아 있으면 raw 값을 출력하지 않고 `SIGNAL`로 중단한다. legacy job의
시간 tuple만으로 full runtime semantic fingerprint를 추측하지 않는다. 운영자가
provider/history/session 및 활성 reminder evidence를 보관하고 승인된 change로 job/token을
전량 drain한 뒤 처음부터 preflight를 다시 통과해야 한다. 새 앱은 future owner/participant
job을 authoritative schedule/travel-plan row에서 full fingerprint로 재구성한다. raw
token/deviceId 유일 인덱스는 만들지 않으며 `ascii_bin` SHA-256 fingerprint 전역 유일
인덱스로 교체한다.
따라서 MySQL 기본 case-insensitive collation에서도 `AbC`와 `aBc`는 서로 다른 opaque 값이다.

DDL 적용 뒤 구버전 애플리케이션 rollback은 지원하지 않는다. 새 버전에 장애가 있으면 즉시
새 API/worker를 모두 중지하고 DB/marker는 유지한 채 수정 버전을 roll-forward한다. DDL 시작
전이라면 maintenance를 해제하고 구버전을 그대로 재개할 수 있다. DDL이 한 statement라도
시작된 뒤에는 MySQL implicit commit 때문에 스키마를 역변경하지 않는다.
