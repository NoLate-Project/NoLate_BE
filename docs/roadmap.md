# NoLate Project Roadmap

Last verified: 2026-07-24 KST (Redis calendar query cache and metadata cache)

이 문서는 NoLate 문서의 상위 인덱스다. 세부 구현, 검증 절차, 테스트 목록은 분야별 문서에서 관리하고, 이 파일에는 현재 제품 상태와 우선순위만 둔다.

## Current Focus

MVP 1차 목표는 **일정 기반 출발 알림 엔진의 신뢰성 검증**이다.

사용자가 일정, 목적지, 도착 시간을 등록하면 NoLate가 경로와 ETA를 기준으로 추천 출발 시각을 계산하고, 교통 상황이나 일정 정보가 바뀌어도 올바른 사용자와 기기에만 출발 알림을 보내야 한다.

MVP 1차 완료 조건:

- 실제 일정 기반 push 3종을 실기기에서 수신한다.
- 알림 터치가 정확한 일정 상세로 이동한다.
- `departNow=true` 출발 완료 액션이 운영 BE까지 성공하고 PushJob을 취소한다.
- 일정 변경, 교통 변경, 기기 변경 상황에서 중복 발송이나 잘못된 사용자 발송이 없다.

병행 트랙인 일정 공유는 BE/FE 기능 연결과 시뮬레이터 시나리오 검증까지 완료했다. 운영 반영 전에는 DB migration, 권한별 쓰기 차단, 실기기 공유 push, 공개 handle/친구 검색을 순서대로 마무리한다.

월 이동 조회 성능 개선도 완료했다. 사용자·revision·월 단위 일정 Redis 캐시와 전 사용자 공용 음력/공휴일 월 캐시를 적용했고, 일정 생성·수정·삭제와 일정/카테고리/캘린더 공유 변경은 커밋 이후 영향 회원의 revision을 올린다. 실제 Redis 검증에서 warm metadata 조회는 회원·달력 SQL 없이 반환됐다.

## Area Index

| Area | Source Of Truth | Status | Next Focus |
| --- | --- | --- | --- |
| Schedule / Push | [`schedule/PUSH_NOTIFICATION_STATUS.md`](schedule/PUSH_NOTIFICATION_STATUS.md) | 코드와 자동 테스트 기준 4단계 완료, 5단계 acceptance 진행 중 | iPhone TestFlight, 운영 BE, 중복 발송 방지 |
| Schedule / Push Implementation | [`schedule/schedule-push-implementation-guide.md`](schedule/schedule-push-implementation-guide.md) | 구현/검증 가이드 | 최신 남은 작업과 실행 절차 유지 |
| Push Scenario Runner | [`schedule/push-scenario-runner.md`](schedule/push-scenario-runner.md) | 수동 E2E 도구 완료 | 실제 일정 기반 Runner 결과 기록 |
| Quick Schedule | [`schedule/quick-schedule-creation-roadmap.md`](schedule/quick-schedule-creation-roadmap.md) | 자연어 일정 생성 기반 완료, 확장 설계 진행 | 입력 채널 확장, OCR/share |
| Calendar Query Cache | [`quality/quality-ops-developer-tools-roadmap.md`](quality/quality-ops-developer-tools-roadmap.md) | 일정/공유 변경 revision 캐시와 공용 메타데이터 월 캐시 구현·실 Redis 검증 완료 | 운영 hit ratio/p95, 다중 BE 인스턴스 single-flight |
| Schedule Sharing | [`schedule/schedule-sharing-roadmap.md`](schedule/schedule-sharing-roadmap.md) | 앱 ID/이메일·링크 공유, 통합 공유함, 참가자별 이동/출발 상태, 첫 출발 자동 push, 오너 지정 push 구현 | 운영 migration, 권한 집행, 실기기 push, handle/친구 검색 |
| Notification / FCM / App Push | [`notification/notification-fcm-app-push-roadmap.md`](notification/notification-fcm-app-push-roadmap.md) | 기존 일정 알림과 공유 참가자 출발/지정 알림 payload, 상세 이동, 발송 이력 구현 | 실기기 수신·클릭·액션 검증 |
| FE App / Route UX | [`frontend/fe-app-route-ux-roadmap.md`](frontend/fe-app-route-ux-roadmap.md) | 로그인, 일정, 경로, 알림 UX 기반 완료 | 실기기 UX 검증, 오류/빈 상태 정리 |
| MVP Acceptance | [`quality/mvp-acceptance-checklist.md`](quality/mvp-acceptance-checklist.md) | 남은 BE/FE 검증 항목 정리 | 체크리스트 실행 결과 기록 |
| Quality / Ops | [`quality/quality-ops-developer-tools-roadmap.md`](quality/quality-ops-developer-tools-roadmap.md) | BE/FE 테스트 일부, TestFlight 업로드 경로 확인 | CI, 환경변수, 관측성, 운영 배포 절차 |
| Member / Auth / Profile | [`member/member-auth-profile-roadmap.md`](member/member-auth-profile-roadmap.md) | 회원가입, 로그인, refresh, 프로필, 비밀번호, 탈퇴 완료 | 이메일 인증, 비밀번호 재설정, SNS 토큰 검증 |
| Onboarding / Calendar Curation | [`onboarding/calendar-curation-onboarding-roadmap.md`](onboarding/calendar-curation-onboarding-roadmap.md) | device calendar import 1차 구현 | 온보딩 상태 영구 저장, 외부 event link |
| Subscription / Policy | [`subscription/subscription-policy-roadmap.md`](subscription/subscription-policy-roadmap.md) | FREE/PREMIUM 정책 모델과 일정 정책 검증 완료 | 결제/구독 모델, plan 변경, paywall |
| External Calendar | [`integrations/external-calendar-integration-roadmap.md`](integrations/external-calendar-integration-roadmap.md) | 로드맵 정의 | Google/Apple import MVP 설계 |

## Current Phase

전체 진행은 **4단계 완료, 5단계 진행 중**이다.

1. PushJob 생성/취소 안정화: 완료
2. ETA fallback, PROCESSING 복구, 재시도 안정화: 완료
3. 실제 FCM 발송과 발송 이력 관리: 완료
4. FE payload 라우팅, 상세 이동 규칙, `depart-now` 액션: 완료
5. 실기기 acceptance와 운영 안정화: 진행 중

## Recommended Work Order

1. 운영 BE에 `POST /api/schedules/{scheduleId}/depart-now` 포함 최신 commit 배포 확인
2. iPhone TestFlight 최신 빌드에서 token 재등록 확인
3. 실제 일정 기반 push 3종 수신 확인
4. background/terminated 상태 알림 터치 상세 이동 확인
5. `departNow=true` 알림 액션 후 PushJob 취소 확인
6. 서버 다중 인스턴스 또는 재시도 상황의 중복 발송 방지 검증
7. 실제 Tmap ETA 변화 시나리오 검증
8. CI에 BE test, FE test, FE typecheck 추가
9. 공유 참가자 첫 출발 자동 push와 오너 지정 출발 확인 push를 실기기 2대에서 수신·클릭 검증

## Verification Commands

자세한 검증 명령과 운영 체크리스트는 [`quality/quality-ops-developer-tools-roadmap.md`](quality/quality-ops-developer-tools-roadmap.md)에서 관리한다.

BE:

```powershell
cd D:\DevSpace\application\no-late\NoLate_BE
.\gradlew.bat --no-daemon test
```

FE:

```powershell
cd D:\DevSpace\application\no-late\NoLate_FE
npm test -- --runInBand
npx tsc --noEmit
```
