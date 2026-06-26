# Schedule / Push Implementation Guide

Last verified: 2026-06-26 KST

이 문서는 일정 기반 출발 알림 기능을 개발, 검증, 운영 보강할 때 보는 구현 가이드다. 제품 규칙과 검증 상태는 [`PUSH_NOTIFICATION_STATUS.md`](PUSH_NOTIFICATION_STATUS.md), 수동 Runner 사용법은 [`push-scenario-runner.md`](push-scenario-runner.md), MVP 남은 체크리스트는 [`../quality/mvp-acceptance-checklist.md`](../quality/mvp-acceptance-checklist.md)를 기준으로 한다.

## Repository Layout

- BE: `D:\DevSpace\application\no-late\NoLate_BE`
- FE: `D:\DevSpace\application\no-late\NoLate_FE`
- BE docs: `NoLate_BE/docs`
- BE HTTP client files: `NoLate_BE/http`

## Current Status

완료된 핵심 범위:

- 일정 등록, 수정, 삭제 API와 PushJob 연동
- 미래 일정 + 알림 ON일 때 PushJob 생성/갱신
- 과거 일정, 알림 OFF, 삭제, 출발 완료 시 PushJob 미생성 또는 취소
- `Clock` 주입으로 시간 기준 테스트 가능
- `routeJson`에서 FE 선택 경로 ETA 추출
- `TrafficClient` 실시간 ETA 재조회
- 교통 API 실패 시 선택 경로 ETA와 기존 `travelMinutes` fallback
- `PROCESSING` timeout 복구
- ETA 변화, 출발 준비, 지금 출발 정책 테스트
- `push_send_history` 성공/실패/무효 토큰/토큰 없음 이력 저장
- `POST /api/schedules/{scheduleId}/depart-now`
- FE push payload type별 상세 이동 규칙
- FE `departNow=true` 알림 액션에서 `depart-now` API 호출
- Android emulator 실제 FCM 일정 push 3종 수신
- iOS TestFlight build 24 업로드와 production APS entitlement 확인

진행 중인 acceptance:

- iPhone TestFlight 실기기에서 실제 일정 push 3종 수신
- background/terminated 상태 알림 터치 상세 이동
- 출발 완료 액션 후 운영 BE에서 PushJob 취소 확인
- 서버 다중 인스턴스와 재시도 상황의 중복 발송 방지
- 실제 Tmap ETA 변화 시나리오 검증

## Main Implementation Files

Backend:

- `src/main/kotlin/com/noLate/schedule/application/useCase/ScheduleUseCase.kt`
- `src/main/kotlin/com/noLate/schedule/application/service/SchedulePushJobService.kt`
- `src/main/kotlin/com/noLate/schedule/application/service/SchedulePushJobWorker.kt`
- `src/main/kotlin/com/noLate/schedule/application/TrafficClient.kt`
- `src/main/kotlin/com/noLate/schedule/infrastructure/TmapTrafficClient.kt`
- `src/main/kotlin/com/noLate/schedule/infrastructure/FallbackTrafficClient.kt`
- `src/main/kotlin/com/noLate/schedule/infrastructure/SchedulePushJobRepository.kt`
- `src/main/kotlin/com/noLate/schedule/dev/SchedulePushScenarioRunner.kt`
- `src/main/kotlin/com/noLate/notification/application/useCase/NotificationUseCase.kt`
- `src/main/kotlin/com/noLate/notification/application/service/PushSendHistoryService.kt`

Frontend:

- `NoLate_FE/src/api/schedule.ts`
- `NoLate_FE/src/modules/notification/foregroundPush.ts`
- `NoLate_FE/src/modules/notification/pushNavigation.ts`
- `NoLate_FE/src/modules/notification/pushRegistration.ts`
- `NoLate_FE/src/modules/schedule/routePlannerSession.ts`
- `NoLate_FE/app/_layout.tsx`
- `NoLate_FE/app/schedule/index.tsx`
- `NoLate_FE/app/schedule/route-planner.tsx`

## Test Coverage

주요 자동 테스트:

- `src/test/kotlin/com/noLate/schedule/application/useCase/ScheduleUseCaseUnitTest.kt`
- `src/test/kotlin/com/noLate/schedule/application/service/SchedulePushJobServiceTest.kt`
- `src/test/kotlin/com/noLate/schedule/application/service/SchedulePushJobUpdateTest.kt`
- `src/test/kotlin/com/noLate/schedule/application/service/SchedulePushJobWorkerTest.kt`
- `src/test/kotlin/com/noLate/schedule/application/service/policy/DepartureReminderPolicyTest.kt`
- `src/test/kotlin/com/noLate/schedule/application/service/policy/PeriodicPushPolicyTest.kt`
- `src/test/kotlin/com/noLate/schedule/application/service/policy/TrafficChangePolicyTest.kt`
- `src/test/kotlin/com/noLate/schedule/infrastructure/FallbackTrafficClientTest.kt`
- `src/test/kotlin/com/noLate/schedule/infrastructure/SchedulePushJobRepositoryIntegrationTest.kt`
- `src/test/kotlin/com/noLate/notification/application/useCase/NotificationUseCaseUnitTest.kt`
- `src/test/kotlin/com/noLate/notification/application/service/NotificationTokenServiceIntegrationTest.kt`
- `src/test/kotlin/com/noLate/notification/application/service/PushSendHistoryServiceTest.kt`
- `NoLate_FE/__tests__/App.test.tsx`
- `NoLate_FE/__tests__/apiWrappers.test.ts`

마지막 문서화된 전체 검증:

```powershell
cd D:\DevSpace\application\no-late\NoLate_BE
.\gradlew.bat --no-daemon test

cd D:\DevSpace\application\no-late\NoLate_FE
npm test -- --runInBand
npx tsc --noEmit
```

## Manual E2E Runner

개발/검증용 Runner는 두 종류다.

- `PushScenarioRunner`: 대표 payload를 현재 로그인한 회원에게 직접 전송한다.
- `SchedulePushScenarioRunner`: 실제 `scheduleId`, `SchedulePushJob`, `SchedulePushJobWorker`, `NotificationUseCase` 경로를 사용한다.

실제 일정 기반 Runner endpoint:

```http
POST /api/dev/schedule-push-scenarios/run
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "scheduleId": 13,
  "trafficChangeMinutes": 15
}
```

필수 조건과 payload-only Runner는 [`push-scenario-runner.md`](push-scenario-runner.md)에 둔다.

## How To Continue

1. `git -C NoLate_BE status --short`와 `git -C NoLate_FE status --short`를 먼저 확인한다.
2. 운영 BE가 최신 `depart-now` API 포함 commit인지 확인한다.
3. TestFlight 최신 빌드에서 로그인 후 token 재등록을 확인한다.
4. 실제 일정 기반 Runner로 iPhone 실기기 push 3종을 검증한다.
5. 알림 터치 상세 이동과 출발 완료 액션을 background/terminated 상태에서 확인한다.
6. PushJob과 `push_send_history`를 함께 확인해 API 성공, job 취소, 중복 발송 여부를 기록한다.
7. 남은 항목은 [`../quality/mvp-acceptance-checklist.md`](../quality/mvp-acceptance-checklist.md)에 결과를 남긴다.

## Remaining Risks

- 운영 BE 미배포 상태에서는 TestFlight의 출발 완료 액션이 최신 API와 맞지 않을 수 있다.
- iOS 알림 액션은 기본 배너에 항상 버튼이 노출되지 않고, 길게 누르기 또는 확장 상태에서 확인해야 한다.
- 서버 다중 인스턴스 환경에서는 같은 PushJob의 중복 lock/처리 검증이 필요하다.
- FCM 발송 성공 후 DB 반영 실패가 발생하면 재실행 시 중복 push 위험이 있다.
- 실제 Tmap ETA 장애, timeout, 비정상 응답에 대한 운영 backoff 정책은 추가 검증이 필요하다.
- MySQL `DATETIME`에 수동으로 값을 넣을 때 KST session의 `NOW()`를 사용하면 9시간 밀릴 수 있다. `UTC_TIMESTAMP()`를 사용한다.

## Working Notes

1. 관련 없는 dirty worktree 변경은 되돌리지 않는다.
2. BE 변경은 `NoLate_BE` 기준, FE 변경은 `NoLate_FE` 기준으로 작업한다.
3. 테스트 메서드명은 한글 목적 중심으로 유지한다.
4. 실제 앱 푸시 검증은 자동 테스트가 아니라 수동 E2E acceptance로 본다.
5. Gradle 테스트가 멈추면 기존 `bootRun` 프로세스가 빌드 lock을 잡고 있는지 확인한다.
