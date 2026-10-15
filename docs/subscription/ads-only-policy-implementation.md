# 광고 전용 구독 정책 적용 결과

2026-09-25. 앞선 `ads-only-policy-step-1.md`의 실패 테스트 단계 이후 구현 결과이다. 기존 사용자 변경을 보존했고 커밋·배포·DB 수동 변경은 수행하지 않았다.

## 적용한 동작

- FREE/PREMIUM 모두 일정 수 제한 없이 같은 일정·알림 기능을 사용한다. 플랜 차이는 광고 표시 가능 여부뿐이다.
- 광고는 서버 kill switch가 켜져 있고 FREE일 때만 허용한다. 프론트의 `FREE && adsEnabled` 방어 조건은 유지했다.
- 입력 검증은 구독에서 분리했다. 리드타임 최대 120분, 허용 간격 10~60분(5분 단위)은 전 사용자 공통이다. 서버의 누락값 기본 리드는 120분이고 기존 폼의 60분 선택 기본값은 유지한다.
- 내부 ETA 기본 갱신 주기는 10분이다. 출발 알림 경계에 가까워지면 기존 로직대로 더 이른 시각을 택할 수 있다. 저장된 사용자 간격은 내부 갱신 품질을 낮추지 않는다.
- 기존 ACTIVE 작업은 매 워커 실행마다 설정된 batchSize(최대 200)만큼 전환한다. 처리 중인 작업은 완료/복구 후 ACTIVE가 되었을 때 전환한다. 별도 일괄 SQL 없이 점진적으로 적용된다.
- 구독 조회 실패가 일정 사용을 제한하지 않는다. ATT는 광고 eligibility를 확인하는 기존 광고 초기화 경로에만 남으며 푸시 등록·알람 복구는 이를 기다리지 않는다.

## 운영 코드 변경 위치

| 파일·위치 | 이유 |
| --- | --- |
| `NoLate_BE/src/main/kotlin/com/noLate/subscription/domain/SubscriptionPlan.kt:4` | 플랜별 기능 수치 제거. FREE/PREMIUM 이름은 DB 호환을 위해 유지. |
| `NoLate_BE/src/main/kotlin/com/noLate/subscription/domain/SubscriptionPolicyDto.kt:3` | 응답은 plan과 adsEnabled만 제공. |
| `NoLate_BE/src/main/kotlin/com/noLate/subscription/application/SubscriptionPolicyService.kt:11` | 회원 플랜과 광고 kill switch만 판정. 일정 저장소·사용량 집계 의존 제거. |
| `NoLate_BE/src/main/kotlin/com/noLate/subscription/controller/SubscriptionController.kt:18` | API 설명을 광고 구독 계약으로 수정. |
| `NoLate_BE/src/main/kotlin/com/noLate/schedule/application/service/policy/ScheduleNotificationPolicy.kt:7` | 플랜 중립 입력 검증. 리드 10~120분, 간격 10~60분의 5분 단위. |
| `NoLate_BE/src/main/kotlin/com/noLate/schedule/application/service/policy/EtaRefreshCadencePolicy.kt:4` | 내부 ETA 기본 주기를 10분으로 분리. |
| `NoLate_BE/src/main/kotlin/com/noLate/schedule/application/service/ScheduleService.kt:1243` | 일정 저장에서 구독 조회·quota 검증 제거. 공통 정책 적용. |
| `NoLate_BE/src/main/kotlin/com/noLate/schedule/application/service/ScheduleTravelPlanService.kt:686` | 개인 이동 계획과 오너 동기화에도 공통 입력 검증 적용. |
| `NoLate_BE/src/main/kotlin/com/noLate/schedule/application/service/SchedulePushJobService.kt:217` | 기존 ScheduleRoute·TravelPlan에 저장된 20분 값과 무관하게 작업 생성. |
| `NoLate_BE/src/main/kotlin/com/noLate/schedule/application/service/SchedulePushJobWorker.kt:110` | 기존 작업 전환 실행. 재조회 예약과 freshness 계산에도 공통 주기 적용. |
| `NoLate_BE/src/main/kotlin/com/noLate/schedule/application/service/SchedulePushJobCoordinator.kt:112` | ACTIVE 레거시 작업을 제한된 배치로 전환. 회원→작업 잠금 순서 유지. |
| `NoLate_BE/src/main/kotlin/com/noLate/schedule/infrastructure/SchedulePushJobRepository.kt:31` | 배치 전환 후보를 회원·작업 순서로 조회. |
| `NoLate_BE/src/main/kotlin/com/noLate/schedule/domain/SchedulePushJob.kt:296` | 기존 nextCheckAt을 당길 수 있게 전환하되 retry 시각·발송 이력·세대 보존. PROCESSING 건은 건드리지 않음. |
| `NoLate_FE/src/api/subscription.ts:6` | 프론트 구독 계약도 광고 필드만 유지. 기본 광고 off 유지. |
| `NoLate_FE/src/modules/schedule/scheduleNotificationPolicy.ts:2` | 일정 폼과 가져오기의 공통 기본값 정의. |
| `NoLate_FE/src/modules/schedule/components/form/NotificationSettingsCard.tsx:74` | 월 한도 차단·소진 문구·플랜별 clamp 제거. 경로 조건은 유지. |
| `NoLate_FE/src/modules/schedule/components/form/NotificationSettingsCard.types.ts:16` | 기존 호출의 policy는 호환용 선택 prop으로만 수용하고 무시. 구독 타입 의존 제거. |
| `NoLate_FE/src/modules/schedule/components/form/useScheduleAddFormState.ts:37` | 생성 폼의 구독 조회·clamp 제거. 초기화·리셋 간격 10분 통일. |
| `NoLate_FE/src/modules/schedule/components/form/useScheduleAddModalController.tsx:49` | 구독 상태 전달 제거. |
| `NoLate_FE/src/modules/schedule/components/form/ScheduleAddModal.tsx:40` | 카드에 구독 정책 전달 제거. |
| `NoLate_FE/src/modules/schedule/screens/useScheduleEditRemoteData.ts:30` | 수정 폼의 구독 조회·clamp 제거. |
| `NoLate_FE/src/modules/schedule/screens/useScheduleEditScreen.ts:161` | 구독 상태 제거. 누락 간격은 공통 기본값 사용, 기존 저장값은 보존. |
| `NoLate_FE/src/modules/schedule/screens/ScheduleEditScreen.tsx:23` | 구독 정책 전달 제거. |
| `NoLate_FE/src/routeSupport/onboarding/useCalendarImportController.ts:198` | 구독 조회와 quota에 따른 토글 차단 제거. |
| `NoLate_FE/src/routeSupport/onboarding/calendarImportActions.ts:180` | 한도와 관계없이 적격 일정 모두 경로·알림 준비. 공급자 요청 배치 크기는 유지. |
| `NoLate_FE/app/onboarding/calendar-import.tsx:537` | 한도 소진 안내·토글 비활성화 제거. |
| `NoLate_FE/src/AppProviders.tsx:279` | 전역 ATT 부트스트랩 제거. 알람 복구는 ATT와 독립 실행. |
| `NoLate_FE/src/modules/notification/pushRegistration.ts:152` | 푸시 권한·등록 경로에서 ATT 대기 제거. |

## 테스트 변경 위치

| 파일·위치 | 이유 |
| --- | --- |
| `NoLate_BE/src/test/kotlin/com/noLate/subscription/application/SubscriptionAdsOnlyContractTest.kt:17` | 광고 전용 JSON 계약·회원 조회·광고 스위치 조합 검증. |
| `NoLate_BE/src/test/kotlin/com/noLate/subscription/application/SubscriptionPolicyServiceUnitTest.kt:9` | 기존 차등 기대값을 제거하고 없는 회원 처리 검증. |
| `NoLate_BE/src/test/kotlin/com/noLate/schedule/application/service/policy/ScheduleNotificationPolicyTest.kt:8` | 102회 공통 입력 허용 및 잘못된 입력 거절. |
| `NoLate_BE/src/test/kotlin/com/noLate/schedule/application/service/policy/EtaRefreshCadencePolicyTest.kt:8` | 기존 20분 작업 전환, 재시도·lease·모니터링 시작 보존. |
| `NoLate_BE/src/test/kotlin/com/noLate/schedule/application/service/ScheduleServiceUnitTest.kt:93` | 실제 일정 서비스에서 102개 알림 저장과 월 집계 미호출 검증. 생성자 연결 수정. |
| `NoLate_BE/src/test/kotlin/com/noLate/schedule/application/service/ScheduleTravelPlanServiceUnitTest.kt:75` | 개인 계획의 공통 기본값 및 집계 미호출 검증. 생성자 연결 수정. |
| `NoLate_BE/src/test/kotlin/com/noLate/schedule/application/service/ScheduleCategoryEditorPolicyIntegrationTest.kt:314` | 기존 테스트의 생성자에서 제거된 구독 의존 인수만 제거. |
| `NoLate_BE/src/test/kotlin/com/noLate/schedule/application/useCase/ScheduleTravelPlanUseCaseRollbackIntegrationTest.kt:180` | 구독 DTO fixture를 새 계약에 맞춤. 롤백 검증 유지. |
| `NoLate_BE/src/test/kotlin/com/noLate/schedule/application/service/SchedulePushJobWorkerTest.kt:196` | 공통 10분 재조회 기대값 반영. 출발 경계가 더 이르면 그 경계 유지. |
| `NoLate_BE/src/test/kotlin/com/noLate/schedule/application/service/SchedulePushJobUpdateTest.kt:92` | 일정 수정 후 작업도 공통 10분을 사용하는지 검증. |
| `NoLate_BE/src/test/kotlin/com/noLate/schedule/infrastructure/SchedulePushJobRepositoryIntegrationTest.kt:28` | 실제 H2 저장소에서 제한된 배치와 PROCESSING 제외 검증. |
| `NoLate_FE/__tests__/notificationSettingsCard.test.tsx:173` | 기존 FREE/PREMIUM quota를 초과해도 토글 활성화. 이전 단계에서 추가. |
| `NoLate_FE/__tests__/routeDetailAdvertisingPolicy.test.ts:58` | 광고 테스트 fixture에서 구독 기능 제한 필드 제거. 기존 광고·ATT 검증 유지. |
| `NoLate_FE/__tests__/appProvidersAlarmScheduleReceipt.test.tsx:259` | 미해결 ATT와 무관하게 복구·등록·foreground 처리가 진행됨을 검증. |
| `NoLate_FE/__tests__/scheduleAddModalCloseFlow.test.tsx:35` | 구독 fixture를 광고 전용 계약에 맞춤. |
| `NoLate_FE/__tests__/scheduleEditRouteReturn.test.tsx:625` | 구독 fixture와 새 기본 간격 기대값 반영. 경로 저장 회귀 검증 유지. |
| `NoLate_FE/__tests__/calendarImportAdsOnlyPolicy.test.ts:27` | 102개 적격 일정 모두 알림 활성화 검증. |

## 검증

- BE: 관련 11개 테스트 클래스, 164개 테스트 통과. 일정 생성·개인 계획·worker·작업 수정·H2 repository·rollback 포함.
- FE: 관련 11개 suite, 97개 테스트 통과.
- `npm run typecheck` 통과.
- 수정한 프론트 운영 파일 및 신규 가져오기 테스트 ESLint: 오류 0개, inline style 경고 11개.
- 두 저장소 `git diff --check` 통과.
- 전체 테스트, MySQL 실제 운영 데이터, iOS 실기기 권한 UI는 실행하지 않았다.

## 배포 경계

`/api/subscriptions/me`에서 종전 quota/lead/interval 필드를 제거했다. 이전 앱은 해당 필드로 폼을 보정하므로, 구버전 앱이 사용 중인 환경에는 이 API 변경을 단독 배포하면 안 된다. 앱 업데이트 적용 또는 API 버전 분리 후 배포해야 한다. 현재 작업은 로컬 구현·검증까지이며 서버 배포는 하지 않았다.

워커가 꺼져 있으면 기존 작업 전환도 실행되지 않는다. 켜진 환경에서는 매 배치마다 순차 전환되므로 전체 데이터가 한 번에 바뀌지는 않는다. 진행 중인 작업의 잠금과 알림 이력을 보존하기 위한 방식이다.

실제 구매·복원·만료에 따른 PREMIUM 상태 변경은 기존과 같이 별도 구현 범위다. 대중교통 ETA 정확도 엔진은 변경하지 않았다.
