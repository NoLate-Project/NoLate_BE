# Schedule Push Notification Status

Last verified: 2026-06-26 KST

이 문서는 일정 기반 출발 알림의 제품 규칙과 검증 상태를 관리하는 source of truth다. 구현/검증 상세 흐름은 [`schedule-push-implementation-guide.md`](schedule-push-implementation-guide.md), 수동 E2E Runner 사용법은 [`push-scenario-runner.md`](push-scenario-runner.md)를 본다.

## Product Rules

1. 미래 일정이고 경로와 알림 설정이 있으면 일정별 `SchedulePushJob`을 생성한다.
2. 일정 시간, 경로, 알림 설정이 바뀌면 기존 PushJob을 새 기준으로 재계산한다.
3. 일정 삭제, 알림 OFF, 출발 완료는 PushJob을 취소한다.
4. scheduler는 실행 대상 PushJob을 조회하고 실시간 ETA를 갱신한다.
5. 교통 API 실패 시 선택 경로 ETA, 기존 `travelMinutes` 순서로 fallback한다.
6. 추천 출발 15분 전에는 출발 준비 알림을 보낸다.
7. ETA가 악화되어 추천 출발 시각이 바뀌면 correction push를 보낸다.
8. 추천 출발 시각에는 `departNow=true` 지금 출발 알림을 보내고, 성공 시 job을 완료한다.
9. 지금 출발 알림 실패는 재시도하고, 최대 재시도 이후 실패 상태로 종료한다.
10. iOS `departNow=true` 알림에는 `schedule_depart_now` category를 붙여 출발 완료 액션과 연결한다.

## Verified

자동 테스트 기준:

- 미래 일정 + 알림 ON일 때 PushJob 생성/갱신
- 과거 일정 또는 알림 OFF일 때 PushJob 미생성
- 일정 수정/삭제/출발 완료 시 PushJob 재등록 또는 취소
- ETA 재조회와 routeJson 선택 경로 ETA fallback
- ETA 증가, 감소, 동일 시 정책 분기
- 출발 준비, 교통 변화, 지금 출발 payload 생성
- 최종 push 실패 재시도와 FAILED 처리
- `PROCESSING` timeout 복구
- 여러 회원의 일정과 payload 격리
- 토큰 없음, 무효 토큰, 성공/실패 발송 이력 기록
- APNs `schedule_depart_now` category 설정
- FE `depart-now` API wrapper와 payload route 생성 규칙

런타임 검증 기준:

- 실제 one-minute scheduler가 due job을 처리했다.
- ETA-only job은 푸시 없이 ETA와 다음 체크 시각을 갱신했다.
- Android emulator에서 실제 FCM 일정 push 3종을 수신했다.
- TestFlight build 24 업로드와 production APS entitlement를 확인했다.
- Delivery UUID: `0d9b768b-cf18-4869-afad-b7e8f2729603`
- build status: `VALID`, `APP_STORE_ELIGIBLE`

## Remaining Acceptance

1. 운영 BE에 최신 `depart-now` API 포함 commit이 배포됐는지 확인한다.
2. iPhone TestFlight 최신 빌드에서 로그인 후 FCM/APNs token 재등록을 확인한다.
3. iPhone 실기기에서 실제 일정 push 3종을 수신한다.
4. background 상태에서 알림 터치가 정확한 일정 상세로 이동하는지 확인한다.
5. terminated 상태에서 알림 터치가 정확한 일정 상세로 이동하는지 확인한다.
6. `departNow=true` 출발 완료 액션이 운영 BE API까지 성공하는지 확인한다.
7. 출발 완료 액션 후 `notificationEnabled=false`와 PushJob 취소가 DB에 반영되는지 확인한다.
8. 동일 사용자의 여러 기기와 같은 기기 계정 전환 상황에서 토큰 소유권을 확인한다.
9. 실제 Tmap ETA 변화 시나리오에서 correction push가 적절히 발송되는지 확인한다.
10. 서버 다중 인스턴스, 재시도, DB 반영 실패 상황에서 중복 발송 위험을 검증한다.

## Main Code

Backend:

- `src/main/kotlin/com/noLate/schedule/application/useCase/ScheduleUseCase.kt`
- `src/main/kotlin/com/noLate/schedule/application/service/SchedulePushJobService.kt`
- `src/main/kotlin/com/noLate/schedule/application/service/SchedulePushJobWorker.kt`
- `src/main/kotlin/com/noLate/schedule/application/service/policy/DepartureReminderPolicy.kt`
- `src/main/kotlin/com/noLate/schedule/application/service/policy/PeriodicPushPolicy.kt`
- `src/main/kotlin/com/noLate/schedule/application/service/policy/TrafficChangePolicy.kt`
- `src/main/kotlin/com/noLate/notification/application/useCase/NotificationUseCase.kt`
- `src/main/kotlin/com/noLate/notification/application/service/PushSendHistoryService.kt`

Frontend:

- `NoLate_FE/src/modules/notification/foregroundPush.ts`
- `NoLate_FE/src/modules/notification/pushNavigation.ts`
- `NoLate_FE/src/modules/notification/pushRegistration.ts`
- `NoLate_FE/src/api/schedule.ts`
- `NoLate_FE/app/_layout.tsx`

## Verification Commands

BE selected tests:

```powershell
cd D:\DevSpace\application\no-late\NoLate_BE
.\gradlew.bat --no-daemon test `
  --tests "com.noLate.schedule.application.service.SchedulePushJobWorkerTest" `
  --tests "com.noLate.notification.application.NotificationUseCaseUnitTest" `
  --tests "com.noLate.notification.application.NotificationTokenServiceIntegrationTest" `
  --tests "com.noLate.schedule.infrastructure.SchedulePushJobRepositoryIntegrationTest"
```

FE selected tests:

```powershell
cd D:\DevSpace\application\no-late\NoLate_FE
npm test -- --runInBand
npx tsc --noEmit
```

## Time Zone Warning

MySQL `DATETIME`에 `Instant` 값을 직접 넣을 때는 `UTC_TIMESTAMP()`를 사용한다. KST session에서 `NOW()`를 사용하면 runtime 해석이 9시간 밀릴 수 있다.
