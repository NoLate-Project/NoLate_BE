# PushScenarioRunner

Last verified: 2026-06-26 KST

## 역할

`PushScenarioRunner`는 개발/검증 환경에서 FE 앱이 푸시 알림을 실제로 받을 수 있는지 빠르게 확인하기 위한 수동 E2E Runner다.

일정 스케줄러가 직접 실행되는 것을 기다리지 않고, 일정/교통 알림에서 FE가 처리해야 하는 대표 payload를 현재 로그인한 회원에게 순차 발송한다. 이 Runner로 확인하는 범위는 다음과 같다.

- 앱 푸시 토큰 등록 여부
- 일반 푸시 수신 여부
- 교통 상황 변경 알림 payload 처리
- 출발 임박 알림 payload 처리
- 즉시 출발 필요 알림 payload 처리
- 알림 터치 후 일정 상세 이동 payload 처리

`SchedulePushScenarioRunner`는 실제 일정 기반 검증 도구다. payload만 직접 보내는 대신 로그인 회원의 실제 `scheduleId`, 실제 `SchedulePushJob`, 기존 `SchedulePushJobWorker`, 실제 `NotificationUseCase`를 사용한다. Android emulator에서 실제 FCM 3종 수신을 검증할 때는 이 Runner를 기준으로 한다.

## 필수 조건

- BE 실행 시 `notification.push-scenario.enabled=true` 설정
- FE 앱에서 테스트 계정으로 로그인
- 앱 푸시 알림 권한 허용
- FE가 `/api/notifications/token`으로 FCM 토큰 등록
- 실제 단말 푸시 확인 시 `firebase.enabled=true` 설정
- 실제 일정 기반 Runner 확인 시 `notification.push-schedule-scenario.enabled=true` 설정
- Firebase Admin 인증 정보 설정
  - `firebase.credentials-path`
  - 또는 Application Default Credentials
- Firebase 프로젝트 ID 설정
  - `firebase.project-id`

`firebase.enabled=false` 또는 미설정 상태에서는 Dummy PushClient가 동작하므로 서버 로그로 전송 시도만 확인할 수 있고, 실제 앱 알림은 도착하지 않는다.

출발 완료 액션까지 확인하려면 운영 또는 검증 BE가 `POST /api/schedules/{scheduleId}/depart-now` API를 포함한 최신 commit으로 배포되어 있어야 한다.

## 실행 API

### Payload-only Runner

```http
POST /api/dev/push-scenarios/run
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "scheduleId": 4,
  "titlePrefix": "NoLate 로컬 검증",
  "changedTravelMinutes": 45,
  "trafficChangeMinutes": 15
}
```

### 실제 일정 기반 Runner

```http
POST /api/dev/schedule-push-scenarios/run
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "scheduleId": 13,
  "trafficChangeMinutes": 15
}
```

실제 일정 기반 Runner는 아래 세 가지를 실제 `SchedulePushJobWorker` 경로로 발송한다.

1. `TRAFFIC_CHANGED`
   - `SCHEDULE_TRAFFIC`
2. `DEPARTURE_SOON`
   - `SCHEDULE_DEPARTURE_REMINDER`, `departNow=false`
3. `DEPART_NOW`
   - `SCHEDULE_DEPARTURE_REMINDER`, `departNow=true`
   - iOS APNs에는 `schedule_depart_now` category가 포함된다.
   - FE에서는 이 payload를 출발 완료 액션과 연결한다.

## 발송 시나리오

1. `TOKEN_CHECK`
   - 현재 로그인한 앱이 푸시를 받을 수 있는지 확인한다.
2. `TRAFFIC_CHANGED`
   - 교통 상황 변화로 이동 시간이 늘어난 상황을 확인한다.
3. `DEPARTURE_SOON`
   - 예상 출발 시각이 가까워지는 알림 표현을 확인한다.
4. `DEPART_NOW`
   - 지금 출발해야 하는 긴급 알림 표현을 확인한다.
5. `DETAIL_NAVIGATION`
   - 알림 터치 시 일정 상세 화면으로 이동할 수 있는 payload를 확인한다.

## 주의사항

이 Runner는 자동화 테스트가 아니라 수동 E2E 검증 도구다. 운영 서버에서는 `notification.push-scenario.enabled`를 켜지 않는다.
