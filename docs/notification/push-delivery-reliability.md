# Push delivery reliability boundary

FCM 호출과 애플리케이션 DB commit은 하나의 원자 트랜잭션이 아니며, FCM 단일 토큰 전송은
서버가 지정한 idempotency key를 제공하지 않는다. 따라서 이 구현은 exactly-once를
주장하지 않고, 같은 논리 이벤트를 성공했을 가능성이 있는 기기에 다시 보내지 않는
at-most-once를 우선한다.

## 상태와 재시도

- `PENDING`: provider loop 전에 현재 대상 기기 전체를 한 transaction으로 만든 manifest다.
  아직 외부 호출 경계를 만들지 않았으므로 다음 실행이 안전하게 claim할 수 있다.
- `DISPATCHING`: provider 호출 전에 독립 트랜잭션으로 기록한다. 성공 응답 직후 프로세스가
  종료된 경우와 호출 직전 종료를 구분할 수 없으므로 자동 재전송하지 않는다.
- `SUCCESS`: provider message id를 받은 확인된 성공이다. 같은 회원·이벤트·기기는 건너뛴다.
- `FAILED`: provider가 예외를 명시적으로 반환한 확인된 실패다. 이 상태의 기기만 다음 실행에서
  다시 `DISPATCHING`으로 선점해 재시도한다.
- `INVALID_TOKEN`: 토큰을 제거하고 같은 이벤트에는 다시 보내지 않는다.
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
title/body/canonical data와 현재 대상 기기 전체 `PENDING` manifest를 함께 commit한다.
동일 event 재시도는 현재 ETA와 decision을 다시 payload로 만들지 않고 이 snapshot만
redrive한다. inbox까지만 존재하는 과거 crash도 같은 transaction이 누락 manifest를
복구한다.

모든 사용자 대상 canonical data에는 다음 계정 binding이 있다.

- `logicalEventKey`: `push_deliveries.event_key` 및 outbox의 durable event key와 동일하다.
- `recipientMemberId`: 실제 수신 회원 ID다.

route setup, 일정 공유, 출발 action/navigation 알림에도 같은 계약을 적용한다. 클라이언트는
현재 로그인 회원과 `recipientMemberId`가 다르면 오래 남은 OS 알림 action을 실행하지
않아야 한다.

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

## 세션과 push token 등록 fence

JWT security filter는 raw access token 대신 검증된 `issuedAt`만 `MemberPrincipal`에 싣는다.
실제 token write transaction은 다음 전역 lock order를 지킨다.

1. `member` row pessimistic lock
2. `push_device_token` token/device fingerprint rows

lock 안에서 회원 active 여부와 `issuedAt > tokensValidAfter`를 다시 검증한 뒤에만 token을
쓴다. logout도 같은 member row를 먼저 잠근 transaction에서 `tokensValidAfter` 갱신,
refresh token 삭제, device token 삭제를 수행한다. register가 먼저 선형화되면 뒤의 logout이
등록 row를 삭제하고, logout이 먼저면 기다리던 old register가 stale JWT로 거절된다.
따라서 filter 통과 뒤 요청이 지연되거나 FE AbortSignal이 늦어도 logout 이후 old 계정 token이
다시 생기지 않는다. 새 계정/session 등록도 같은 lock order를 사용해 deadlock 순환을 만들지
않는다.

일정 알림 dedupe input은 `job id + notification generation + check count`이며, 여기서 도출한
`logicalEventKey`를 실제 event key로 쓴다. 시작 시각, 목적지, 현재 회원의 출발지/경로,
이동수단, 알림 정책 같은 의미 input의 deterministic fingerprint가 달라질 때만 generation과
check/stage를 reset한다. 동일 PUT과 notes/category 같은 비알림 메타 편집은 상태를 보존한다.

편집과 provider 전송의 linearization fence는 `schedule_push_job` row의 pessimistic lock,
worker lease, generation, notification input fingerprint를 함께 검증한다. 편집 lock이
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

## 운영 복구

오래 남은 `DISPATCHING`은 자동으로 `FAILED`로 바꾸지 않는다. 먼저 `push_send_history`의
성공 row와 provider 관측 자료를 확인한다. 성공 여부를 증명할 수 없으면 중복 방지를 위해
그대로 억제한다. 사용자에게 다시 알려야 한다는 제품 판단이 있으면 기존 event key를 재사용하지
말고 새 event key의 보상 알림을 생성한다.

inbox 생성 직후 종료되더라도 다음 실행이 누락된 manifest를 보충하며, 한 기기 처리 중 종료돼도
나머지 `PENDING` 기기는 계속 전송할 수 있다.

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

세 코드 커밋은 운영 미배포 상태이므로 반드시 한 배포에서 함께 올린다. SQL 순서는 다음과 같다.

1. `2026-07-24-push-deliveries.sql`
2. `2026-07-24-push-delivery-followup.sql`
3. `2026-07-24-push-delivery-linearization.sql`
4. 새 애플리케이션 배포

마지막 migration은 legacy `push_deliveries` 또는 이전 schedule event key가 있으면 `SIGNAL`로
중단한다. 해당 데이터는 운영자가 provider/history와 대조해 drain하거나 별도 보관한 뒤
다시 실행해야 한다. raw token/deviceId 유일 인덱스는 만들지 않으며 binary SHA-256
fingerprint 유일 인덱스로 교체한다. 따라서 MySQL 기본 case-insensitive collation에서도
`AbC`와 `aBc`는 서로 다른 opaque 값이다.

이 변경은 event identity와 ownership evidence를 추가하는 forward-only migration이다.
DDL 적용 뒤 구버전 애플리케이션으로 rollback하면 필수 snapshot/fence를 쓰지 않으므로
지원하지 않는다. 애플리케이션 장애 시 새 worker를 중지하고 DB는 유지한 채 수정 버전을
roll-forward한다. DDL 전 rollback은 세 migration을 적용하지 않고 구버전을 그대로 유지한다.
