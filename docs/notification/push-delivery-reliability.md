# Push delivery reliability boundary

FCM 호출과 애플리케이션 DB commit은 하나의 원자 트랜잭션이 아니며, FCM 단일 토큰 전송은
서버가 지정한 idempotency key를 제공하지 않는다. 따라서 이 구현은 exactly-once를
주장하지 않고, 같은 논리 이벤트를 성공했을 가능성이 있는 기기에 다시 보내지 않는
at-most-once를 우선한다.

## 상태와 재시도

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

## 운영 복구

오래 남은 `DISPATCHING`은 자동으로 `FAILED`로 바꾸지 않는다. 먼저 `push_send_history`의
성공 row와 provider 관측 자료를 확인한다. 성공 여부를 증명할 수 없으면 중복 방지를 위해
그대로 억제한다. 사용자에게 다시 알려야 한다는 제품 판단이 있으면 기존 event key를 재사용하지
말고 새 event key의 보상 알림을 생성한다.

이 정책의 남은 장애 창은 provider 호출 전에 `DISPATCHING`을 기록한 직후 프로세스가 종료되는
경우다. 실제 FCM 호출이 없었어도 재전송하지 않으므로 알림이 손실될 수 있다. 반대로 provider가
실패를 반환한 뒤 `FAILED` 기록에 실패하면 상태가 `DISPATCHING`으로 남아 역시 자동 재시도되지
않는다. 또한 inbox row를 만든 뒤 첫 기기 경계를 만들기 전에 종료되거나, 여러 기기 사이에서
종료되면 경계가 없는 나머지 기기도 기존 inbox 이벤트로 판단해 자동 전송하지 않는다. 이는
공급자 idempotency가 없는 환경에서 중복 0을 우선한 의도적인 선택이다.
