# Notification / FCM / App Push Roadmap

Last verified: 2026-06-25 KST

앱으로 실제 푸시를 보내고, FE 앱이 알림을 수신/표시/이동 처리하는 기반 기능의 상세 로드맵이다.

일정 ETA 기반 푸시 정책은 별도 문서에서 관리한다.

- `docs/schedule-push-codex-handoff.md`

상위 로드맵:

- `docs/no-late-codex-roadmaps.md`

## Current Status

### BE 완료

- `/api/notifications/token` 토큰 등록
- 같은 member/device 토큰 갱신
- 다른 회원에 묶인 token/device 정리
- 회원별 토큰 조회 후 PushClient 전송
- Firebase PushClient
- Dummy PushClient
- invalid token 응답 시 토큰 삭제
- `/api/notifications/test/send` 단일 테스트 전송 API
- `PushScenarioRunner` 개발 검증 도구

### FE 완료

- 로그인 후 FCM 토큰 등록
- deviceId 생성 및 SecureStore 저장
- token refresh 시 BE 재등록
- Android 13+ notification permission 요청
- foreground push를 local notification으로 표시
- 알림 데이터에서 `scheduleId` 추출
- payload type별 일정 상세 이동 규칙
  - `SCHEDULE_TRAFFIC`
  - `SCHEDULE_DEPARTURE_REMINDER`
  - `SCHEDULE_DETAIL`
- 알림 클릭 시 `/schedule/[id]` route 생성
- foreground push를 같은 payload 규칙으로 local notification 표시
- `departNow=true` 일정 알림에 `지금 출발` 액션 버튼 연결
- `지금 출발` 액션에서 `POST /api/schedules/{scheduleId}/depart-now` 호출
- iOS TestFlight build 24 업로드 완료

### 주요 구현 파일

- `src/main/kotlin/com/noLate/notification/controller/NotificationController.kt`
- `src/main/kotlin/com/noLate/notification/application/useCase/NotificationUseCase.kt`
- `src/main/kotlin/com/noLate/notification/application/service/NotificationTokenService.kt`
- `src/main/kotlin/com/noLate/notification/infrastructure/FirebasePushConfiguration.kt`
- `src/main/kotlin/com/noLate/notification/infrastructure/PushClientApplication.kt`
- `src/main/kotlin/com/noLate/notification/dev/PushScenarioRunner.kt`
- `src/main/kotlin/com/noLate/notification/dev/PushScenarioController.kt`
- `NoLate_FE/src/api/notification.ts`
- `NoLate_FE/src/modules/notification/pushRegistration.ts`
- `NoLate_FE/src/modules/notification/foregroundPush.ts`
- `NoLate_FE/src/modules/notification/pushNavigation.ts`

### 테스트

- `src/test/kotlin/com/noLate/notification/application/service/NotificationServiceUnitTest.kt`
- `src/test/kotlin/com/noLate/notification/application/service/NotificationTokenServiceIntegrationTest.kt`
- `src/test/kotlin/com/noLate/notification/application/useCase/NotificationUseCaseUnitTest.kt`
- `src/test/kotlin/com/noLate/notification/dev/PushScenarioRunnerTest.kt`

## Next Work

- background/terminated 상태 알림 클릭 동작 실기기 검증
- iPhone TestFlight build 24에서 실제 일정 푸시 3종 수신 검증
- `지금 출발` 액션 후 BE에서 PushJob이 취소되는지 운영 환경에서 검증
- 추가 알림 액션 후보 검증
  - "일정 보기"
  - "길찾기"
  - "10분 뒤 다시 알림"
- Android notification channel 이름/우선순위/소리 정책 정리
- iOS 권한/foreground 표시 정책 정리
- Firebase credential 운영 환경 분리
- invalid token 삭제 지표/로그 모니터링
- 실제 Firebase E2E 테스트 절차 문서화
- 후속 UX 후보로 일정 상세/푸시 맥락의 날씨 정보 카드 검토
  - 1차 후보: 도착지 기준 현재 날씨와 일정 시작 시간대 예보
  - 구현 시 API key 보호를 위해 BE weather proxy 또는 서버 캐시 계층 우선 검토

## Roadmap

<!-- mermaidId: notification-fcm-app-roadmap -->

```mermaid
sequenceDiagram
  participant App as "FE App"
  participant BE as "NoLate BE"
  participant Store as "push_device_token"
  participant FCM as "Firebase"
  participant User as "User"

  App->>User: "알림 권한 요청"
  User-->>App: "허용"
  App->>FCM: "FCM token 발급"
  App->>BE: "POST /api/notifications/token"
  BE->>Store: "memberId + deviceId 기준 upsert"
  BE->>FCM: "푸시 발송"
  FCM-->>App: "foreground/background push"
  App->>App: "payload type 해석"
  App->>User: "알림 표시 또는 화면 이동"
```

## Suggested First Slice

1. iPhone TestFlight build 24에서 실제 일정 push token 재등록 확인
2. `SCHEDULE_TRAFFIC`, `SCHEDULE_DEPARTURE_REMINDER`, `SCHEDULE_DETAIL` 수신 검증
3. background/terminated 클릭 시 일정 상세 이동 실기기 테스트
4. `departNow=true` 알림의 `지금 출발` 액션과 PushJob 취소 검증
5. PushScenarioRunner 결과와 실제 일정 기반 Runner 결과를 같은 체크리스트에 기록
