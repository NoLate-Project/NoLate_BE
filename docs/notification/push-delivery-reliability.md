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

명시적인 공급자 오류 코드가 없는 transport 예외는 수락 여부가 모호하므로 delivery row를
`DISPATCHING`으로 유지하고 `push_send_history.status=UNKNOWN`으로 관측한다. 이 결과도 자동
재전송하지 않는다.

여러 기기 중 일부만 성공하면 `SUCCESS` 기기는 유지하고 `FAILED` 기기만 재시도한다.
inbox deduplication key는 같은 논리 알림 row로 수렴하며, `push_deliveries`의 기기별 상태가
외부 호출 중복까지 차단한다.

일정 알림 event key는 `job id + notification generation + check count`로 만든다. 일정 시각,
출발 시각, 모니터링 구간 같은 의미가 변경돼 기존 job row를 reset할 때 generation을 먼저
증가시키므로 새 일정의 check 0이 과거 check 0 delivery와 충돌하지 않는다.

SchedulePushJob은 결과 의미도 구분한다. 확인된 새/기존 성공만 `last_pushed_at`을 갱신한다.
`DISPATCHING` 재관측 같은 ambiguous 결과와 inbox/device dedupe는 이벤트 단계 진행에는
사용할 수 있지만 성공 지표로 기록하지 않는다. 확인된 `FAILED` 기기가 하나라도 남으면
check count를 진행하지 않고 같은 event key로 job을 재예약한다.

due job claim은 `schedule.push.batch-size`로 제한한 비관적 잠금 transaction에서
`PROCESSING`으로 전이한다. provider 호출 전에 이 lease를 commit해 다른 인스턴스가 같은
job을 선점하지 못하게 하며, backlog 전체를 한 인스턴스 lease로 묶지 않는다.

## 운영 복구

오래 남은 `DISPATCHING`은 자동으로 `FAILED`로 바꾸지 않는다. 먼저 `push_send_history`의
성공 row와 provider 관측 자료를 확인한다. 성공 여부를 증명할 수 없으면 중복 방지를 위해
그대로 억제한다. 사용자에게 다시 알려야 한다는 제품 판단이 있으면 기존 event key를 재사용하지
말고 새 event key의 보상 알림을 생성한다.

inbox 생성 직후 종료되더라도 다음 실행이 누락된 manifest를 보충하며, 한 기기 처리 중 종료돼도
나머지 `PENDING` 기기는 계속 전송할 수 있다.

이 정책의 남은 장애 창은 provider 호출 전에 `DISPATCHING`을 기록한 직후 프로세스가 종료되는
경우다. 실제 FCM 호출이 없었어도 재전송하지 않으므로 알림이 손실될 수 있다. 반대로 provider가
실패를 반환한 뒤 `FAILED` 기록에 실패하면 상태가 `DISPATCHING`으로 남아 역시 자동 재시도되지
않는다. 이는 공급자 idempotency가 없는 환경에서 중복 0을 우선한 의도적인 선택이다.

배포 시에는 `2026-07-24-push-deliveries.sql` 다음에
`2026-07-24-push-delivery-followup.sql`을 적용한다. 후속 migration은 notification
generation 추가, PENDING attempt timestamp 보정, legacy token 중복 정리, token 및
member/device 유일 인덱스 추가 순서로 실행된다.
