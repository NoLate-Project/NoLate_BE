# 광고만 다른 구독 정책 — 1단계 테스트

> 이 문서는 최초 실패 테스트 단계의 기록입니다. 후속 구현과 현재 검증 결과는 [적용 결과](ads-only-policy-implementation.md)를 확인하세요.

2026-09-25 현재 작업 트리 기준. 이번 단계는 테스트 추가와 변경 범위 확인이다.
운영 코드는 아직 변경하지 않았으며 신규 테스트의 실패는 미구현 요구사항을 나타낸다.
기존 변경이 있는 roadmap 및 일정·공유·광고 구현 파일은 수정하지 않았다.

## 제품 계약

- FREE와 PREMIUM은 스마트 일정 수, 알림 리드타임, 알림 방식, ETA 품질이 같다.
- 구독 계약의 목표 필드는 `plan`, `adsEnabled`이다. 월 사용량을 조회하지 않는다.
- 광고는 `FREE && adsEnabled`일 때만 가능하며 서버의 광고 kill switch를 유지한다.
- 월 구독 quota는 제거한다. 정상적인 입력 검증과 권한 검사는 유지한다.
- 공통 리드타임·주기 수치 자체는 제품 원칙에서 지정되지 않았다. 이번 테스트는 기존 PREMIUM이 허용하던 120분 리드·10분 간격을 FREE에서도 허용하는 최소 회귀 기준이다.

## 추가한 테스트와 읽는 순서

`src/test/kotlin/com/noLate/subscription/application/SubscriptionAdsOnlyContractTest.kt`

1. 32행: 직렬화한 구독 응답에 `plan`, `adsEnabled`만 남는지 검증한다.
2. 41행: 구독 조회가 Schedule/TravelPlan 저장소의 사용량 집계를 호출하지 않는지 검증한다.
3. 49행: FREE/PREMIUM 모두 120분 리드·10분 간격을 허용한다.
4. 59행: 과거 양쪽 월 한도를 넘는 101개 사용량도 새 알림을 막지 않는다.
5. 71행: 플랜 두 종류 × 광고 스위치 두 상태의 광고 판정을 유지한다.

`NoLate_FE/__tests__/notificationSettingsCard.test.tsx`의 173행부터:
FREE 5개/PREMIUM 100개라는 과거 한도를 초과해도 경로가 준비되어 있으면
출발 알림 스위치가 활성화되고 콜백이 호출되며 한도 소진 문구가 없어야 한다.

새 정책 클래스가 아직 없으므로 백엔드 행동 테스트는 현재
`validateNotificationSettings` 진입점을 사용한다. 다음 단계에서는 이를
`ScheduleNotificationPolicy` 테스트로 옮기고 실제 일정 서비스 연결 테스트를 추가한다.
기존 `SubscriptionPolicyServiceUnitTest`의 quota/플랜 제한 기대값은 다음 단계에서 교체한다.

## 확인된 구현 변경 지점

아래 위치는 이번 조사 시점 기준이며 아직 수정하지 않았다.

| 파일 | 위치 | 변경 이유 |
| --- | --- | --- |
| BE `subscription/domain/SubscriptionPlan.kt` | 3행 | 플랜별 기능 수치 제거 |
| BE `subscription/domain/SubscriptionPolicyDto.kt` | 3행 | 구독 응답을 광고 계약으로 축소 |
| BE `subscription/application/SubscriptionPolicyService.kt` | 23행, 36행 | 사용량 집계 및 일정 검증 책임 분리 |
| BE `schedule/application/service/ScheduleService.kt` | 1246행 | 구독 조회 없이 공통 입력 정책 적용 |
| BE `schedule/application/service/ScheduleTravelPlanService.kt` | 693행 | 개인 이동 계획에도 같은 정책 적용; 내부 검증 우회 호출도 조사 필요 |
| BE `schedule/application/service/SchedulePushJobService.kt` | 215행, 261행 | 저장된 간격과 20분 fallback에 의존하는 신규 작업 생성 분리 |
| BE `schedule/application/service/SchedulePushJobWorker.kt` | 404행, 1088행 | 이미 저장된 작업의 간격 사용도 공통 ETA cadence 적용 대상으로 포함 |
| BE `schedule/domain/SchedulePushJob.kt` | 87행, 872행 | 기본값 및 허용 간격과 새 내부 cadence의 호환성 확인 |
| FE `src/api/subscription.ts` | 6행, 17행 | 구독 타입과 실패 시 기본값에서 일정 제한 제거; 광고 기본값 false 유지 |
| FE `src/modules/schedule/components/form/NotificationSettingsCard.tsx` | 75행, 412행 | quota에 따른 스위치 차단·문구 제거 |
| FE `src/modules/schedule/components/form/useScheduleAddFormState.ts` | 416행 | 플랜 응답에 의한 lead/interval clamp 제거 |
| FE `src/modules/schedule/screens/useScheduleEditRemoteData.ts` | 144행 | 수정 화면에도 같은 처리 |
| FE `src/routeSupport/onboarding/useCalendarImportController.ts` | 203행, 491행 | quota에 따른 가져오기 준비 차단 제거 |
| FE `src/routeSupport/onboarding/calendarImportActions.ts` | 184행, 224행 | 가져온 일정의 경로 준비와 알림 활성화 quota 제거 |
| FE `app/onboarding/calendar-import.tsx` | 530행, 544행 | quota 안내 및 비활성화 제거 |
| FE `src/AppProviders.tsx` | 74행, 88행, 313행 | 전역 ATT 요청과 알람 복구의 ATT 대기 제거 |
| FE `src/modules/notification/pushRegistration.ts` | 163행 | 푸시 초기화의 ATT 의존 제거 |
| FE `src/modules/advertising/routeDetailInterstitial.ts` | 81행, 192행 | eligibility 확인 후 광고 SDK 초기화 전 ATT 처리 유지 |

BE 경로는 `src/main/kotlin/com/noLate/` 기준이다.

## 다음 단계별 검증

1. 구독 계약 축소 + 플랜 중립 `ScheduleNotificationPolicy`: 양 플랜의 동일 입력 허용/거절,
   잘못된 리드·간격 입력 거절, 일정 생성·수정·개인 이동 계획에서 월 quota 미적용을 검증한다.
2. `EtaRefreshCadencePolicy`: 새 작업뿐 아니라 기존 20분 ScheduleRoute/TravelPlan/PushJob도
   공통 주기를 사용하는지 검증한다. nextCheckAt이 이미 미래로 저장된 작업의 전환 방법도
   결정해야 하며 상수 변경만으로 완료 처리하지 않는다. 사용자 알림 리드타임과 알림 방식은 보존한다.
3. FE 계약과 일정 폼·가져오기: 정책 API 실패가 기능 제한으로 바뀌지 않으며, 과거 한도를
   초과하는 가져오기에서도 적격 일정 모두 경로 준비/알림 활성화를 수행하는지 검증한다.
4. ATT 분리: PREMIUM/광고 off/정책 조회 실패 시 ATT·광고 SDK를 호출하지 않고,
   ATT가 미해결이어도 푸시 등록·알람 복구가 진행되는지 검증한다.
   iOS 시스템 권한 창 동시 요청 방지 순서는 핵심 알림을 우선하는 형태로 별도 검증한다.

API 필드 제거는 구버전 앱이 기존 quota를 계속 집행하거나 누락 필드로 오동작할 수 있으므로,
최종 배포 전 앱/API 버전 호환 방식을 결정해야 한다. 큰 quota 숫자를 무제한의 대체물로 쓰지 않는다.
실제 결제·복원·구독 만료 및 대중교통 ETA 정확도 엔진 구현은 이번 작업 범위 밖이다.

## 1단계 실행 결과

- BE: `./gradlew test --offline --tests com.noLate.subscription.application.SubscriptionAdsOnlyContractTest --console=plain`
  — 9개 중 7개 예상 실패, 2개 통과. FREE 120분/10분 허용, 두 플랜의 quota 제거,
  응답 축소 및 사용량 의존 제거가 아직 구현되지 않았다. PREMIUM 기존 입력 허용과 광고 판정은 통과했다.
- FE: `npm test -- --runInBand --no-watchman --runTestsByPath __tests__/notificationSettingsCard.test.tsx __tests__/routeDetailAdvertisingPolicy.test.ts`
  — 신규 2개 예상 실패, 기존 24개 통과. 광고 정책 테스트 파일은 기존 사용자 작업이며 수정하지 않았다.
- 전체 테스트·타입 검사·네이티브 UI 검증은 이번 단계에서 실행하지 않았다.
