# MVP Acceptance Checklist

Last verified: 2026-06-26 KST

이 문서는 MVP 1차 목표인 **일정 기반 출발 알림 엔진의 신뢰성 검증**을 위한 남은 테스트만 관리한다. 이미 자동 테스트나 문서화된 런타임 검증으로 통과한 항목은 제외했다.

상위 로드맵:

- [`../roadmap.md`](../roadmap.md)
- [`../schedule/PUSH_NOTIFICATION_STATUS.md`](../schedule/PUSH_NOTIFICATION_STATUS.md)
- [`../notification/notification-fcm-app-push-roadmap.md`](../notification/notification-fcm-app-push-roadmap.md)

## Already Verified Elsewhere

BE 자동 테스트와 Android emulator 검증으로 이미 덮은 범위:

- PushJob 생성, 갱신, 취소 기본 흐름
- 과거 일정 또는 알림 OFF 일정의 PushJob 미생성
- ETA 재조회와 routeJson ETA fallback
- ETA 증가/감소/동일 정책
- 출발 준비, 교통 변화, 지금 출발 payload 생성
- 최종 push 실패 재시도와 FAILED 처리
- `PROCESSING` timeout 복구
- 여러 회원 payload와 token 격리
- token 없음, invalid token, push send history 기록
- Android emulator 실제 FCM 일정 push 3종 수신
- FE payload route 생성과 `depart-now` API wrapper

## BE Remaining Tests

### 운영 배포

- [ ] 운영 BE에 `POST /api/schedules/{scheduleId}/depart-now` 포함 최신 commit 배포 확인
- [ ] 운영 BE에서 push 문구와 payload key가 최신 코드와 일치하는지 확인

### 출발 완료 액션

- [ ] `depart-now` 호출 후 일정의 `notificationEnabled=false` 확인
- [ ] `depart-now` 호출 후 해당 `SchedulePushJob`이 취소되는지 확인
- [ ] 같은 schedule에 `depart-now`를 두 번 호출해도 상태가 깨지지 않는지 확인

### 중복 발송 방지

- [ ] 서버 다중 인스턴스에서 같은 PushJob을 동시에 처리하지 않는지 확인
- [ ] FCM 발송 성공 후 DB 반영 실패 상황에서 재실행 시 중복 push 위험 확인
- [ ] scheduler 재시도와 worker timeout 복구가 같은 job을 중복 발송하지 않는지 확인

### 동시성

- [ ] 일정 수정과 scheduler 실행이 동시에 발생할 때 오래된 일정 기준 알림이 나가지 않는지 확인
- [ ] 알림 OFF 수정과 scheduler 실행이 동시에 발생할 때 push가 나가지 않는지 확인
- [ ] 일정 삭제와 scheduler 실행이 동시에 발생할 때 push가 나가지 않는지 확인

### 외부 연동과 시간

- [ ] 실제 Tmap ETA 증가 시 correction push 확인
- [ ] 실제 Tmap 장애, timeout, 비정상 ETA 응답 시 fallback 확인
- [ ] 교통 악화로 추천 출발 시각이 이미 지난 경우 즉시 지금 출발 처리 확인
- [ ] Asia/Seoul 기준 날짜 경계와 UTC 저장 값 확인

## FE Remaining Tests

### iPhone TestFlight

- [ ] 최신 TestFlight 빌드 설치
- [ ] 로그인 후 push token 재등록 확인
- [ ] notification permission 허용 확인
- [ ] 실제 일정 기반 `SCHEDULE_TRAFFIC` 수신
- [ ] 실제 일정 기반 `SCHEDULE_DEPARTURE_REMINDER`, `departNow=false` 수신
- [ ] 실제 일정 기반 `SCHEDULE_DEPARTURE_REMINDER`, `departNow=true` 수신

### 알림 터치

- [ ] background 상태에서 알림 터치 시 정확한 일정 상세 이동
- [ ] terminated 상태에서 알림 터치 시 정확한 일정 상세 이동
- [ ] foreground local notification 터치 시 같은 상세 이동 규칙 적용
- [ ] 같은 notification response를 두 번 받아도 중복 이동하지 않는지 확인

### 출발 완료 액션

- [ ] iOS 알림을 길게 누르거나 확장했을 때 출발 완료 액션 노출 확인
- [ ] 출발 완료 액션 클릭 시 `depart-now` API 호출 확인
- [ ] 액션 성공 후 같은 알림 response를 다시 받아도 중복 API 호출하지 않는지 확인
- [ ] 액션 성공 후 BE PushJob 취소까지 확인

### 기기와 계정

- [ ] 동일 계정의 여러 기기에서 push 수신 정책 확인
- [ ] 같은 기기에서 A계정 로그아웃 후 B계정 로그인 시 token 소유권 이동 확인
- [ ] 계정 전환 후 A계정 push가 해당 기기에 도착하지 않는지 확인
- [ ] 앱 재실행 후 token bootstrap 재등록 확인

## Result Format

실기기 검증 결과는 아래 형식으로 이 파일 또는 별도 acceptance log에 남긴다.

```text
Date:
Build:
Device:
Account:
BE environment:
Scenario:
Expected:
Actual:
DB evidence:
Push history evidence:
Result: PASS / FAIL
Notes:
```
