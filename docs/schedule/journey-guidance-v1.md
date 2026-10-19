# 수동 대중교통 길안내 1차 API

작성일: 2026-10-03. 위치 추적·자동 출발·음성·새 실시간 조회가 없는 수동 파일럿이다.

## 활성화와 스키마

`docs/schedule/migrations/2026-10-03-journey-guidance-sessions.sql`을 먼저 적용한 후
`journey.guidance.enabled=true`로 활성화한다(환경변수 `JOURNEY_GUIDANCE_ENABLED=true`).
기본값은 false이며 HTTP 403 / `C006 FEATURE_DISABLED`를 반환한다.
스키마는 비활성 배포에서도 필요하다. 운영 DB에 이 작업에서 마이그레이션을 실행하지 않는다.

## API

모든 요청은 기존 access JWT와 로그인 session-generation 검증을 사용한다. 본인의 현재 개인
대중교통 계획과 일정의 이동 접근권한이 필요하다. 다른 참가자의 경로로 대체하지 않는다.

| 메서드 | 경로 | 요청 |
|---|---|---|
| POST | `/api/schedules/{scheduleId}/journey/start` | `{requestId: UUID, deviceId?: string}` |
| GET | `/api/schedules/{scheduleId}/journey` | 없음; 최신 본인 세션 또는 null |
| PUT | `/api/schedules/{scheduleId}/journey/{sessionId}/progress` | generation, routeRevision, sequence, phase, activeLegIndex, currentStopIndex?, remainingStops?, legProgress, observedAt ISO, source MANUAL |
| POST | `/api/schedules/{scheduleId}/journey/{sessionId}/arrive` | generation, routeRevision, sequence, observedAt ISO |
| POST | `/api/schedules/{scheduleId}/journey/{sessionId}/stop` | generation, routeRevision, sequence, observedAt ISO |

응답은 `ApiResponse.data`의 flat 세션이다. sessionId, scheduleId(string), generation,
routeRevision, sequence, phase, activeLegIndex, currentStopIndex, remainingStops, legProgress,
nextInstruction, observedAt, source, startedAt, expiresAt, routeSnapshot, routeLegs를 반환한다.
`routeSnapshot`은 저장된 본인 경로 전체, `routeLegs`는 원본 상세 필드를 보존한 실제 구간 배열이다.
수동 단계는 정류장 위치를 추정하지 않는다. currentStopIndex/remainingStops는 null을 보낸다.

같은 start requestId의 재전송은 같은 세션을 돌려준다. 활성 세션이 있으면 새 UUID도 그 세션을
반환한다. 이후 재시작은 새 requestId를 사용한다. generation은 회원/일정별 증가하고 경로는
세션 동안 고정(routeRevision=1)한다. 경로 변경 시 기존 진행 요청을 거절하고 재시작한다.
sequence는 정확히 현재+1이어야 하며 동일한 요청의 재시도만 멱등 성공한다. 다른 payload의
중복, 역행, 건너뛰기, 오래된 generation/routeRevision은 HTTP 409 / C002로 거절한다.
observedAt은 이전 시각 이상, 서버 기준 15분 이전~60초 이후 사이만 받는다.

## 상태와 Live Activity

첫 실제 구간이 도보이면 WALKING_TO_STOP, 승차 구간이면 WAITING으로 시작한다.
승차 구간은 WAITING → RIDING → ALIGHTING, 구간 사이는 실제 도보 여부에 따라
TRANSFERRING 또는 다음 승차 구간 WAITING, 마지막 도보는 FINAL_WALK으로 진행한다.
마지막 구간을 마친 뒤 ARRIVED, 언제든 사용자 종료 시 STOPPED로 끝낸다.

start 트랜잭션은 출발 완료·기존 출발 알림/기기 알람 취소·새 세션 저장·Activity outbox를
원자적으로 처리한다. 기존 depart endpoint의 기본 동작은 유지하되 활성 여정 이후의 중복
DEPARTED intent는 여정을 종료하지 않는다.
시작 이전에 접수된 END intent가 아직 정리 중이면 start는 C002를 반환하고 재시도를 요청한다.
활성 여정은 기존 ETA 투영보다 우선하며, 동일 세션의 원격 START 실패를 무한 재시작하지 않는다.

deviceId를 보내면 그 기기의 기존 Activity를 inTransit으로 전환하거나 등록된 push-to-start
토큰으로 새 Activity를 시작한다. 기기 토큰이 없거나 provider lease가 진행 중이면 세션을
durable 재시도 원본으로 사용한다. 기기에서 ActivityKit 기능/알림 권한이 지원되지 않으면
앱 안의 수동 길안내는 사용 가능하다. deviceId를 생략하면 remote Activity는 생성하지 않는다.
APNs의 외부 content revision은 서버만 증가시킨다. 여정 generation과 알림 generation은 별개다.

30초 주기(100세션씩 cursor 회전)의 재검증이 로그인 세대, 이동 권한, 저장 경로, 8시간 만료를
확인한다. 권한 회수/로그아웃/일정 삭제/기능 비활성 시 종료한다. 실제 위치는 수집하지 않으며
종료 스냅샷은 7일 후 제거한다. 계정 탈퇴는 본인 및 소유 일정의 스냅샷을 즉시 삭제한다.

## 검증 및 다음 단계

단위 검증: 상태 역행·구간 건너뛰기·잘못된 승차 상태·stop count·재시도·terminal resurrection,
actor session/access fence, 본인 route provenance, 출발 Activity handoff, 원격 Activity 신규 시작.
실기기 검증은 앱 시작 → 수동 출발 → 탑승/하차/환승 → 도착, 앱 재실행 후 복원,
토큰 없는 시작 후 토큰 등록, 로그아웃, 서버 flag 비활성, 지연된 기존 출발 액션을 포함한다.
파일럿 이후 위치 기반 진행/자동 출발/음성/실시간 대기 알림을 별도 단계로 추가한다.

2026-10-03 로컬 검증: 아래 명령으로 25개 클래스의 166개 테스트 통과(실패/오류/스킵 0).
새 여정 테스트는 28개이며 기존 Live Activity 토큰/lease/outbox H2 통합 테스트도 포함한다.
실제 APNs 전달 및 MySQL 운영 마이그레이션 실행은 이 검증에 포함하지 않았다.

```sh
./gradlew test --tests 'com.noLate.global.config.ProductionSchemaVersionGuardTest' \
  --tests 'com.noLate.journey.*' --tests 'com.noLate.notification.liveactivity.*' \
  --tests 'com.noLate.schedule.application.service.SchedulePushJobServiceTest' \
  --tests 'com.noLate.schedule.application.service.ScheduleTravelPlanServiceUnitTest' \
  --offline --no-daemon
```
