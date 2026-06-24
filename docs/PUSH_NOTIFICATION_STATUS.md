# Schedule Push Notification Status

Last verified: 2026-06-14 (Asia/Seoul)

This document is the handoff point for future Codex sessions working on schedule push notifications.

## Product Rules

1. The scheduler scans due `SchedulePushJob` rows every minute.
2. ETA is refreshed at the configured interval (default 20 minutes).
3. Before the alert window, ETA is stored without sending a push.
4. At 15 minutes before the current recommended departure time, an advance push is sent.
5. If ETA changes after an advance push, the departure time is recalculated and a correction push is sent.
6. At the recommended departure time, a depart-now push is sent and the job is completed.
7. Updating a schedule resets its job timing and notification history. Disabling or deleting it cancels the job.

## Main Code

Backend:

- `src/main/kotlin/com/noLate/schedule/application/service/SchedulePushJobWorker.kt`
- `src/main/kotlin/com/noLate/schedule/application/service/policy/DepartureReminderPolicy.kt`
- `src/main/kotlin/com/noLate/schedule/application/service/policy/PeriodicPushPolicy.kt`
- `src/main/kotlin/com/noLate/schedule/application/service/policy/TrafficChangePolicy.kt`
- `src/main/kotlin/com/noLate/schedule/domain/SchedulePushJob.kt`
- `src/main/kotlin/com/noLate/notification/application/useCase/NotificationUseCase.kt`
- `src/main/kotlin/com/noLate/notification/application/service/NotificationTokenService.kt`

Frontend:

- `NoLate_FE/src/modules/notification/pushRegistration.ts`
- `NoLate_FE/src/modules/notification/foregroundPush.ts`
- `NoLate_FE/app/_layout.tsx`

## Automated Verification

The test time constants are at the top of `SchedulePushJobWorkerTest.kt`.

Verified:

- ETA refresh without a push before the alert window
- Advance notification 15 minutes before departure
- Correction notification after ETA changes
- Depart-now notification and job completion
- Schedule update resets job state
- Two members processed without schedule/member crossover
- Member payloads delivered only to that member's tokens
- Token ownership moves to the latest account when a device changes accounts
- Repository due-job filtering

```bash
./gradlew test \
  --tests 'com.noLate.schedule.application.service.SchedulePushJobWorkerTest' \
  --tests 'com.noLate.notification.application.NotificationUseCaseUnitTest' \
  --tests 'com.noLate.notification.application.NotificationTokenServiceIntegrationTest' \
  --tests 'com.noLate.schedule.infrastructure.SchedulePushJobRepositoryIntegrationTest'
```

Result on 2026-06-14: all 16 selected tests passed.

## Runtime Verification

- The real one-minute scheduler detected and processed two due jobs.
- An ETA-only job stored ETA 30 with no push and moved its next check.
- An advance job produced `ADVANCE_NOTICE`; Android FCM reported one successful send.
- A multi-account run independently stored member 1 ETA 30 and member 20 ETA 35.
- Duplicate tokens previously assigned to multiple members were cleaned up.
- iOS Simulator displayed an injected APNs notification banner.

## Remaining Acceptance Checks

1. Complete Apple Developer enrollment and upload an APNs `.p8` key to Firebase.
2. Receive and tap a backend push on a signed physical iPhone build.
3. Confirm the tap opens the matching authenticated schedule detail.
4. Visually confirm Android delivery on a connected device or emulator.
5. Test two physical devices with two different accounts.
6. Enable real TMAP and repeat controlled ETA-change scenarios.

## Time Zone Warning

When manually inserting `Instant` data into MySQL `DATETIME`, use `UTC_TIMESTAMP()`.
Using `NOW()` in a KST session can shift runtime interpretation by nine hours.
