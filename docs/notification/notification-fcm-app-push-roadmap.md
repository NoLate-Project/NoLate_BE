# Notification / FCM / App Push Roadmap

Last verified: 2026-07-22 KST

이 문서는 FCM/APNs 토큰 등록, push 발송, FE 수신/표시/이동 처리의 상태를 관리한다. 일정 ETA 정책은 [`../schedule/PUSH_NOTIFICATION_STATUS.md`](../schedule/PUSH_NOTIFICATION_STATUS.md)를 기준으로 한다.

상위 로드맵:

- [`../roadmap.md`](../roadmap.md)

## Current Status

BE 완료:

- `/api/notifications/token` 토큰 등록
- member/device 기준 token upsert
- 다른 회원에 묶인 token/device 정리
- 회원별 토큰 조회 후 PushClient 발송
- Firebase PushClient와 Dummy PushClient
- Android high priority push, default sound, `schedule-push` channel
- Firebase APNs alert payload
- `departNow=true` 일정 알림의 iOS `schedule_depart_now` category
- invalid token과 `BadEnvironmentKeyInToken` 삭제 처리
- `/api/notifications/test/send`
- `PushScenarioRunner`
- `push_send_history` 발송 이력 저장
- `GET /api/notifications/send-histories`
- `app_notifications` 사용자 알림함 영속 저장과 회원별 읽음 상태 관리
- FCM token 부재·만료·발송 실패와 무관하게 push 호출 전에 앱 알림 저장
- `(member_id, deduplication_key)` 유니크 제약과 독립 트랜잭션으로 worker 재시도·동시 이벤트 중복 방지
- 최신순 cursor pagination, 읽지 않음 필터, 미확인 개수 조회
- `GET /api/notifications/inbox`, `GET /api/notifications/unread-count`
- `PATCH /api/notifications/{notificationId}/read`, `PATCH /api/notifications/read-all`
- 공유 참가자의 첫 `depart-now` 커밋 후 오너·직접 공유·카테고리 공유 대상에게 자동 push
- 자동 출발 알림의 중복 수신자/출발 당사자 제외와 동시 요청 1회 발행 보장
- `AFTER_COMMIT + REQUIRES_NEW` 경계로 자동 push 결과 이력의 독립 커밋 보장
- 오너 전용 `POST /api/schedules/{scheduleId}/departure-nudges/{targetMemberId}` 지정 알림
- 활성 공유 대상, 미출발 상태를 서버에서 재검증한 뒤 지정 알림 발송

FE 완료:

- 로그인 후 FCM token 등록
- deviceId 생성 및 SecureStore 저장
- token refresh 시 BE 재등록
- Android 13+ notification permission 요청
- foreground push를 local notification으로 표시
- payload에서 `scheduleId` 추출
- `SCHEDULE_TRAFFIC`, `SCHEDULE_DEPARTURE_REMINDER`, `SCHEDULE_DETAIL` 상세 이동 규칙
- 알림 클릭 시 `/schedule/[id]` route 생성
- `departNow=true` 알림의 출발 완료 액션 연결
- 출발 완료 액션에서 `POST /api/schedules/{scheduleId}/depart-now` 호출
- TestFlight build에서 production APS entitlement 확인
- `SCHEDULE_PARTICIPANT_DEPARTED`, `SCHEDULE_DEPARTURE_NUDGE` 일정 상세 이동 규칙
- 오너 상세 화면에서 대기 중 공유 참가자별 출발 확인 알림 버튼 제공
- 수신자/이미 출발한 참가자에게 지정 알림 버튼을 숨기고 발송 결과를 화면에 구분 표시
- 메인 캘린더 알림함 버튼과 읽지 않은 알림 배지
- 전체/읽지 않음 필터, cursor pagination, pull-to-refresh, 모두 읽음이 포함된 앱 알림함 화면
- 실시간 push와 앱 알림함이 같은 payload 해석 및 일정 상세/공유함 이동 규칙 사용
- 알림 선택 시 읽음 처리 후 대상 화면 이동, 포그라운드 수신·앱 복귀 시 배지 갱신

## Tests

BE:

- `src/test/kotlin/com/noLate/notification/application/service/NotificationServiceUnitTest.kt`
- `src/test/kotlin/com/noLate/notification/application/service/NotificationTokenServiceIntegrationTest.kt`
- `src/test/kotlin/com/noLate/notification/application/useCase/NotificationUseCaseUnitTest.kt`
- `src/test/kotlin/com/noLate/notification/dev/PushScenarioRunnerTest.kt`
- `src/test/kotlin/com/noLate/notification/application/service/PushSendHistoryServiceTest.kt`
- `src/test/kotlin/com/noLate/notification/domain/AppNotificationTest.kt`
- `src/test/kotlin/com/noLate/notification/application/service/AppNotificationServiceIntegrationTest.kt`
- `src/test/kotlin/com/noLate/notification/controller/AppNotificationControllerUnitTest.kt`
- `src/test/kotlin/com/noLate/schedule/application/service/ScheduleDepartureStatusConcurrencyIntegrationTest.kt`
- `src/test/kotlin/com/noLate/schedule/application/service/ScheduleDeparturePushNotificationListenerUnitTest.kt`
- `src/test/kotlin/com/noLate/schedule/application/service/ScheduleDepartureNotificationServiceUnitTest.kt`
- `src/test/kotlin/com/noLate/schedule/controller/ScheduleDepartureNotificationControllerUnitTest.kt`

FE:

- `NoLate_FE/__tests__/App.test.tsx`
- `NoLate_FE/__tests__/apiWrappers.test.ts`
- `NoLate_FE/__tests__/appNotificationApi.test.ts`
- `NoLate_FE/__tests__/appNotificationPresentation.test.ts`
- `NoLate_FE/__tests__/scheduleDepartureNudgePresentation.test.ts`

## Remaining Acceptance

1. iPhone TestFlight 최신 빌드에서 실제 push token 재등록 확인
2. iPhone 실기기에서 실제 일정 push 3종 수신
3. background 상태 알림 클릭 시 일정 상세 이동
4. terminated 상태 알림 클릭 시 일정 상세 이동
5. `departNow=true` 출발 완료 액션 후 운영 BE `depart-now` API 성공
6. 출발 완료 액션 후 PushJob 취소와 일정 알림 OFF 확인
7. 같은 기기에서 계정 전환 시 이전 계정으로 push가 가지 않는지 확인
8. 실제 Firebase E2E 테스트 절차 문서화
9. invalid token 삭제 지표와 로그 모니터링
10. 참가자 첫 출발 자동 push를 오너와 다른 공유 참가자 실기기에서 수신하고 상세 이동 확인
11. 오너 지정 출발 확인 push를 선택한 참가자 한 명에게만 수신하고 상세 이동 확인
12. TestFlight에서 push를 받지 못한 경우에도 앱 재실행 후 알림함에 동일 이벤트가 남는지 확인
13. 다중 기기 로그인에서 한 기기가 읽은 알림 상태와 배지가 다른 기기에 반영되는지 확인
14. 알림 보관 기간·삭제 정책과 운영 데이터 증가량 모니터링 기준 확정

로컬 검증 메모:

- iOS Simulator 빌드는 APNs token 등록을 의도적으로 건너뛰므로 `simctl push` payload 저장과 FE 라우팅까지만 검증한다.
- 실제 원격 전달 성공 여부는 `FIREBASE_ENABLED=true`와 유효한 실기기 token이 있는 환경에서 확인한다.
- 로컬에서 만료된 FCM token의 발송 실패·token 삭제 이후에도 공유 알림 row가 남는 것을 확인했다.
- iOS 시뮬레이터에서는 알림함 목록, 읽음 처리, 배지, 일정 상세 이동을 실제 API 데이터로 검증한다.
- 2026-07-22 수신자 시뮬레이터에서 `미확인 배지 1 -> 새 일정 공유 -> 공유 일정 상세 -> 배지 0` 흐름을 실제 계정·API 데이터로 통과했다.
- BE 전체 Gradle test와 FE release config/typecheck/lint/Jest를 통과했다. FE Jest 결과는 125 suites, 829 tests이다.

## Main Files

Backend:

- `src/main/kotlin/com/noLate/notification/controller/NotificationController.kt`
- `src/main/kotlin/com/noLate/notification/application/useCase/NotificationUseCase.kt`
- `src/main/kotlin/com/noLate/notification/application/service/NotificationTokenService.kt`
- `src/main/kotlin/com/noLate/notification/application/service/PushSendHistoryService.kt`
- `src/main/kotlin/com/noLate/notification/domain/AppNotification.kt`
- `src/main/kotlin/com/noLate/notification/application/service/AppNotificationService.kt`
- `src/main/kotlin/com/noLate/notification/controller/AppNotificationController.kt`
- `src/main/kotlin/com/noLate/notification/infrastructure/FirebasePushConfiguration.kt`
- `src/main/kotlin/com/noLate/notification/infrastructure/PushClientApplication.kt`
- `src/main/kotlin/com/noLate/notification/dev/PushScenarioRunner.kt`
- `src/main/kotlin/com/noLate/notification/dev/PushScenarioController.kt`
- `src/main/kotlin/com/noLate/schedule/application/service/ScheduleDeparturePushNotificationListener.kt`
- `src/main/kotlin/com/noLate/schedule/application/service/ScheduleDepartureNotificationService.kt`
- `src/main/kotlin/com/noLate/schedule/controller/ScheduleDepartureNotificationController.kt`

Frontend:

- `NoLate_FE/src/api/notification.ts`
- `NoLate_FE/src/modules/notification/pushRegistration.ts`
- `NoLate_FE/src/modules/notification/foregroundPush.ts`
- `NoLate_FE/src/modules/notification/pushNavigation.ts`
- `NoLate_FE/src/modules/notification/appNotificationPresentation.ts`
- `NoLate_FE/src/modules/notification/appNotificationEvents.ts`
- `NoLate_FE/app/notifications.tsx`
- `NoLate_FE/app/schedule/[id].tsx`
- `NoLate_FE/src/modules/schedule/detailPresentation.ts`

## Roadmap

```mermaid
sequenceDiagram
  participant App as "FE App"
  participant BE as "NoLate BE"
  participant Inbox as "app_notifications"
  participant Store as "push_device_token"
  participant FCM as "Firebase/APNs"
  participant User as "User"

  App->>User: "알림 권한 요청"
  User-->>App: "허용"
  App->>FCM: "token 발급"
  App->>BE: "POST /api/notifications/token"
  BE->>Store: "memberId + deviceId 기준 upsert"
  BE->>Inbox: "논리 알림 저장 + 중복 제거"
  BE->>FCM: "push 발송"
  FCM-->>App: "foreground/background/terminated push"
  App->>App: "payload type 해석"
  App->>User: "알림 표시 또는 일정 상세 이동"
  User->>App: "놓친 알림함 열기"
  App->>BE: "GET /api/notifications/inbox"
  BE-->>App: "읽음 상태 + 이동 payload"
  App->>BE: "PATCH /api/notifications/{id}/read"
```

## Next Slice

1. TestFlight 최신 빌드에서 token 재등록과 알림함 API 연동 확인
2. 실제 일정 기반 Runner로 iPhone push 3종 수신 확인
3. push 실패·앱 종료 상황에서 알림함 복구와 상세 이동 확인
4. background/terminated 알림 클릭 상세 이동 확인
5. `departNow=true` 액션과 PushJob 취소 확인
6. 결과를 [`../quality/mvp-acceptance-checklist.md`](../quality/mvp-acceptance-checklist.md)에 기록
