# Schedule Sharing Roadmap

Last verified: 2026-07-19 KST

이 문서는 NoLate 일정 공유 기능의 현재 구현 상태와 고도화 계획을 정리한다.

공유는 **앱 ID/이메일 직접 공유**와 **링크 초대**를 함께 제공한다. 직접 공유는 이미 가입한 회원에게 즉시 권한과 푸시를 전달하고, 링크는 대상이 정해지지 않은 초대장으로 사용한다.

## Current Implementation

### Backend

- 개별 일정 공유: `schedule_shares`
- 카테고리 공유: `schedule_category_shares`
- 링크 초대: `schedule_share_invitations`
- 공유 일정 참가자별 출발 상태: `schedule_departure_statuses`
- 공유 일정 참가자별 개인 이동 계획: `schedule_travel_plans`
- 일정 제목/시각/공통 목적지는 한 일정이 소유하고, 출발지/수단/경로/출발 알림은 `(schedule_id, member_id)`별로 분리
- 공유 대상 권한: `VIEWER`, `COMMENTER`, `EDITOR`, `OWNER`
- 공유 상태: `ACTIVE`, `REVOKED`
- 초대 상태: `PENDING`, `ACCEPTED`, `EXPIRED`, `REVOKED`
- 초대 토큰은 원문을 저장하지 않고 SHA-256 hash만 저장
- 초대 수락 시 `PESSIMISTIC_WRITE`로 invitation row를 잠가 단일 사용 링크 동시 수락을 방지
- 만료 시각을 지난 `PENDING` 링크는 API에서 `EXPIRED`로 계산하고 활성 링크 목록에서 제외
- 카테고리 공유는 해당 카테고리에 속한 일정 묶음을 공유하는 모델
- 일정 조회는 내 일정, 직접 공유받은 일정, 공유 카테고리에 속한 일정을 포함
- 프로필의 숫자 회원 ID 또는 전체 이메일로 직접 공유
- 직접 공유 요청은 `targetAppId`, `targetEmail` 중 정확히 하나만 허용
- 받은 공유와 내가 공유한 리소스/활성 링크를 각각 inbox/outbox로 조회
- 일정·카테고리 공유 대상 조회, 권한 변경, 공유 해제 API 구현
- 신규 공유와 해제 후 재공유에만 수신자 push event 발행
- 동일 대상 동시 공유는 share row와 push event가 각각 한 건으로 수렴
- 실제 push 발송은 공유 트랜잭션 `AFTER_COMMIT` 이후 실행
- 일정 공유 push는 일정 상세, 카테고리 공유 push는 통합 공유함으로 이동
- 오너와 공유 대상자의 출발 상태를 분리하고, 공유 대상자의 출발 완료가 오너 PushJob을 취소하지 않도록 처리
- 일정 상세 응답에 `myDepartedAt`, `departureParticipants`를 포함
- 일정 목록/상세의 기존 평탄형 경로 필드는 로그인 사용자의 개인 계획으로 투영
- 공유 수신자에게 오너의 출발지, 경로 JSON, 알림 설정을 숨기고 경로 계산에 필요한 공통 목적지만 제공
- 오너와 `EDITOR`는 참가자 계획 상세를 조회할 수 있고, `VIEWER`/`COMMENTER`는 본인 상세와 타인의 설정 상태만 조회
- 개인 계획 수정은 권한과 관계없이 본인의 `/travel-plans/my` API로만 허용
- PushJob 유일키를 `(schedule_id, member_id)`로 분리해 참가자별 알림 등록/취소 지원
- 일정 시각 또는 공통 목적지가 바뀌면 계획을 `STALE`로 표시하고 해당 참가자의 오래된 PushJob을 취소
- Push worker에서도 계획 지문을 재검증해 배포 경합 중 오래된 경로 알림 발송 차단

### Frontend

- 일정 상세와 카테고리 관리 화면에 공유 진입점 구현
- 앱 ID/이메일 직접 공유와 링크 초대를 segmented share sheet로 통합
- 링크 복사, OS share sheet, 앱 딥링크 수락 화면 구현
- 받은 공유, 내가 공유한 항목, 활성 링크를 한 공유함 화면에서 제공
- 메인 화면에 공유함 알림 배지와 프로필 진입점을 분리
- 공유받은 일정의 읽기 전용 상세 화면과 공유 범위 표시
- 오너/수신자 상세 화면에 참가자별 출발 현황과 현재 사용자 출발 액션 표시
- 지도 중심 일정 상세를 3단계 bottom sheet 구조로 재배치하고, 시트 뒤 지도 마커가 컨트롤처럼 비치지 않게 차폐
- 공유 일정에서 현재 사용자의 이동 경로를 생성/수정하고 저장 직후 최신 상세 상태로 동기화
- 일반 일정 상세에도 `내 이동 경로` 진입점과 접을 수 있는 참가자 계획 목록 제공
- 오너/`EDITOR`가 참가자 행을 선택하면 해당 사용자의 저장 경로를 지도와 타임라인에 읽기 전용으로 표시

주요 API:

```http
GET  /api/shares/inbox
GET  /api/shares/outbox
GET  /api/schedules/{scheduleId}/shares
POST /api/schedules/{scheduleId}/shares/invitations
GET  /api/schedules/{scheduleId}/shares/invitations
POST /api/schedule-categories/{categoryId}/shares/invitations
GET  /api/schedule-categories/{categoryId}/shares/invitations
POST /api/schedules/{scheduleId}/shares
POST /api/schedule-categories/{categoryId}/shares
PATCH /api/schedules/{scheduleId}/shares/{shareId}
PATCH /api/schedule-categories/{categoryId}/shares/{shareId}
DELETE /api/schedules/{scheduleId}/shares/{shareId}
DELETE /api/schedule-categories/{categoryId}/shares/{shareId}
POST /api/share-invitations/{token}/accept
GET  /api/schedule-categories/{categoryId}/shares
GET  /api/schedules/{scheduleId}/travel-plans
GET  /api/schedules/{scheduleId}/travel-plans/{memberId}
PUT  /api/schedules/{scheduleId}/travel-plans/my
```

관련 테스트:

- 링크 초대 생성/수락/만료 단위 테스트
- 단일 링크 동시 수락 통합 테스트
- 직접 일정 공유/카테고리 공유 동시성 테스트
- 동시 직접 공유 시 push event 1회 발행 테스트
- 일정/카테고리 공유 push payload 단위 테스트
- 직접 공유 및 카테고리 공유 일정 조회 통합 테스트
- 참가자별 출발 상태 생성, 최초 출발 시각 보존, 오너/공유 대상 분리 단위 테스트
- 공유 대상 출발 시 오너 PushJob 미취소 use case 테스트
- 동일 회원의 개인 계획 동시 최초 저장이 한 row로 수렴하는 비관적 잠금 통합 테스트
- 오너/`EDITOR`/일반 참여자의 참가자 경로 상세 권한 테스트
- 오너 경로 비노출과 공통 목적지 유지 회귀 테스트
- 같은 일정에서 서로 다른 회원 PushJob 저장 및 동일 회원 중복 차단 DB 테스트
- 일정 변경 후 오래된 개인 계획 PushJob 취소와 worker 이중 차단 테스트
- 만료 링크 활성 목록 제외 및 수락 거절 테스트

2026-07-15 검증 기록:

- BE 공유/초대/출발 상태 관련 34개 테스트 통과
- 격리된 전체 BE 테스트는 191개 중 21개가 기본 프로필의 외부 MySQL host 의존으로 context load 실패; 공유 관련 테스트 실패는 없음
- FE Jest 전체 31 suites, 200 tests 및 TypeScript 검사 통과 기록 보유
- iOS 시뮬레이터에서 오너/수신자 계정, 3명 이상 직접 공유, 일정/카테고리 공유, 공유함, 상세 진입, 출발 상태 확인
- APNs/FCM 실기기 수신은 아직 acceptance 대상이며 완료로 보지 않음

2026-07-19 검증 기록:

- BE 전체 309개 테스트 통과: 참가자별 이동 계획 단위/권한/동시성, PushJob 저장/취소/worker 방어 포함
- FE 전체 106 suites, 713 tests 및 TypeScript 검사 통과; lint 오류 0건(기존 warning 144건)
- 운영 반영 SQL: `docs/schedule/migrations/2026-07-19-member-travel-plans.sql`
- 반복 가능한 로컬 수용 테스트: `scripts/qa/member-travel-plan-acceptance.sh`
- 실제 API 수용 시나리오 통과
  - 오너 1명과 신규 참여자 3명으로 일정 생성
  - 이메일 `EDITOR` 일정 공유, 앱 ID `VIEWER` 일정 공유, 앱 ID `VIEWER` 카테고리 공유
  - 네 계정의 출발지/수단/소요 시간/경로를 각각 저장하고 받은 공유함 노출 확인
  - 오너와 `EDITOR`의 전체 계획 조회, `VIEWER`의 본인 계획 전용 조회, 오너 경로 비노출 확인
- 신규 iPhone 17 Pro / iOS 26.5 시뮬레이터 Release 빌드 및 화면 수용 테스트 통과
  - 오너: 서울역 출발, 자동차 30분 경로와 공통 강남 도착지 표시
  - 공유 수신자: 홍대입구역 출발, 자동차 35분 개인 경로와 같은 공통 도착지 표시
- APNs/FCM 실기기 push 수신과 알림 탭 이동은 아직 별도 acceptance가 필요

## Product Principles

- 링크는 접근 권한 자체가 아니라 권한을 받을 계정으로 이어지는 초대장이다.
- 로그인하지 않은 익명 사용자는 개인 일정 데이터에 접근하지 못한다.
- 공유 수락 이후에는 링크가 아니라 계정 기반 공유 row가 권한의 기준이 된다.
- 카테고리 공유는 공유 캘린더처럼 동작한다.
- 이메일 직접 공유는 보조 수단으로 남길 수 있지만, 기본 UX는 링크 공유와 앱 ID/핸들 기반 공유를 우선한다.

## Enhancement Roadmap

### 1. Link UX and Deep Link

- 앱 scheme 딥링크와 초대 수락 화면 구현 완료
- Universal Link 설계 및 Associated Domains 구성
- 설치 전 사용자를 위한 fallback landing route 설계
- 수락 전 초대 링크 미리보기 API/화면 추가
  - 일정 제목, 날짜, 소유자 표시
  - 카테고리 공유인 경우 카테고리명과 공유 범위 표시
  - 로그인 전에는 민감한 위치/메모/경로 상세 숨김
- 이미 수락한 링크 재클릭 시 기존 공유 화면으로 이동
- 만료/회수/수락 완료 링크의 사용자 메시지 표준화

### 2. App ID or Handle Sharing

- 숫자 회원 ID 기반 정확 일치 직접 공유 구현 완료
- 이메일 정확 일치 직접 공유 구현 완료
- 이메일 대신 사람이 읽기 쉬운 앱 내부 식별자로 사용자 검색 고도화
  - 예: `@nolate_minsu`
  - 또는 랜덤 초대 코드: `NL-7KQ2-MA9P`
- 회원 프로필에 고유 handle 필드 추가
- handle 변경 정책, 예약어, 금칙어, 중복 검증 추가
- 공개 handle을 도입하면 내부 PK 기반 숫자 ID 노출을 단계적으로 제거
- 앱 ID 직접 공유와 링크 초대가 같은 share row 생성 로직을 재사용하도록 정리

### 3. Permission Model Hardening

- 현재 권한 enum은 있으나 실제 쓰기 권한은 보수적으로 제한되어 있다.
- `VIEWER`
  - 일정/카테고리 조회만 가능
  - 본인 이동 계획 생성/수정 및 본인 상세 조회 가능
  - 다른 참가자는 이동 계획 설정 상태만 확인 가능
- `COMMENTER`
  - 댓글, 참석 상태, 간단한 메모 같은 협업 기능이 생긴 뒤 활성화
- `EDITOR`
  - 공유 카테고리에 일정 추가/수정 가능
  - 참가자별 저장 이동 계획 상세 읽기 가능
  - 다른 참가자의 이동 계획 수정은 불가
  - 개별 공유 일정 수정 가능 여부는 제품 정책 확정 필요
- `OWNER`
  - 공유 row로 직접 부여하지 않고 소유자 표현 또는 소유권 이전 플로우에서만 사용
- 수정, 삭제, 알림 설정, 멤버 초대 권한을 별도 정책 함수로 분리

### 4. Invitation Lifecycle

- 만료 시각 기반 유효 상태 계산과 활성 목록 제외 구현 완료
- 초대 링크 회수 API 추가
- 초대 재발급 API 추가
- 만료된 초대의 DB 상태를 `EXPIRED`로 영속화하는 배치 추가
- 초대 목록에서 `PENDING`, `ACCEPTED`, `EXPIRED`, `REVOKED` 필터 지원
- 다중 수락 링크의 수락자 목록 조회
- 단일 사용 링크에서 이미 수락된 사용자의 재수락은 idempotent하게 처리할지 결정

### 5. Security and Abuse Prevention

- 초대 생성 rate limit
- 초대 수락 rate limit
- 너무 짧거나 비정상 토큰 요청에 대한 로그/차단
- token hash에 server-side pepper 적용 검토
- 공유 링크 생성/수락/회수 감사 로그 추가
- 의심스러운 다중 수락 실패 관측 지표 추가
- 카테고리 공유 시 새로 생성되는 일정이 자동 공유된다는 안내와 서버 정책 검증

### 6. Notifications

- 앱 ID/이메일 직접 공유 수신 push 구현 완료
- 일정 공유 push의 일정 상세 deep link 구현 완료
- 카테고리 공유 push의 통합 공유함 deep link 구현 완료
- push payload 및 공급자 실패 격리 단위 테스트 완료
- TestFlight/실기기에서 foreground, background, terminated 수신 및 탭 이동 검증
- push 공급자 장애 시 재전송 가능한 outbox/worker 도입
- 링크 초대 생성은 대상 회원이 없으므로 push 대신 OS share sheet 유지
- 초대 수락 시 소유자에게 알림
- 공유 일정 변경 시 공유 대상에게 알림할지 정책화
- 공유받은 일정의 출발 알림 개인별 설정/PushJob 분리 구현 완료
- 공유 카테고리 일정의 알림 기본값 정책 설계

### 7. Frontend Integration

- 일정 상세 화면 공유 버튼 구현 완료
- 카테고리 관리 화면 공유 버튼 구현 완료
- 직접 공유/링크 초대 segmented share sheet 구현 완료
- 앱 ID/이메일 입력 검증 및 직접 공유 API 연동 완료
- 링크 복사/share sheet 연동 완료
- 초대 수락 화면 구현 완료
- 통합 받은 공유/내가 공유/활성 링크 화면 구현 완료
- 공유 알림 배지와 공유받은 일정 표시 구현 완료
- 공유받은 일정 읽기 전용 상세 상태 구현 완료
- 참가자별 출발 상태와 출발 액션 구현 완료
- 참가자별 개인 이동 계획 생성/수정과 오너/`EDITOR` 조회 UI 구현 완료
- 공유 멤버 목록, 권한 변경, 공유 해제 UI
- 링크 만료/회수/이미 수락 상태 UX

### 8. Data Migration and Backfill

- 기존 일정의 `schedules.category_id` backfill
- `schedule_category_snapshots.category_id`가 숫자가 아닌 값인 경우 처리 정책
- 운영 DB에 공유 테이블 추가 migration 작성
- 개인 이동 계획 테이블 및 PushJob 복합 유일키 운영 migration 작성 완료
- staging에서 `2026-07-19-member-travel-plans.sql` 실행, index/외래키/rollback 절차 검증
- `schema.sql`와 실제 migration 도구의 책임 분리 검토

### 9. Testing and CI

- 공유 API controller test 추가
- invitation controller test 추가
- 권한별 수정/삭제 차단 테스트 추가
- 카테고리 공유 후 새 일정 생성 시 자동 노출 테스트 추가
- 다중 수락 링크 동시성 테스트 추가
- 참가자별 출발 완료 DB 동시성 통합 테스트 추가
- 참가자별 개인 이동 계획 동시 최초 저장 통합 테스트 완료
- 오너/공유 대상/권한별 일정 수정·삭제 API 통합 테스트 추가
- 로컬 `SpringBootTest`가 MySQL env에 의존하지 않도록 test profile 정리
- CI에서 공유 관련 단위/통합 테스트 고정 실행

### 10. Observability

- invitation created/accepted/expired/revoked metric
- share created/reactivated/revoked metric
- token accept failure reason metric
- 공유 일정 조회 쿼리 latency 추적
- 공유 카테고리 일정 수가 많을 때 쿼리 성능 측정

## Open Decisions

- 링크 기본 만료 기간: 현재 기본 7일, 최대 30일
- 기본 링크 수락 횟수: 현재 1회
- 다중 수락 링크를 일반 사용자에게 노출할지 여부
- `EDITOR`가 공유 일정 자체를 수정할 수 있는지, 공유 카테고리에 새 일정만 추가할 수 있는지
- `EDITOR`에게 참가자 이동 계획의 출발지까지 공개할지, 시간/상태 요약만 공개할지
- 저장 경로 공유와 별개로 실시간 현재 위치를 공유할지 여부와 명시적 동의/만료 정책
- 공유 해제 후 개인 이동 계획 보관 기간과 재공유 시 복원 정책
- 앱 ID/handle 공개 범위와 변경 가능 횟수

## Recommended Next Work

1. 운영/staging DB에 개인 이동 계획 migration 적용 및 데이터/인덱스 검증
2. TestFlight 실기기에서 공유 push의 foreground/background/terminated 수신 및 탭 이동 검증
3. staging에서 실제 경로 공급자 응답으로 참가자별 경로 저장/재계산/`STALE` 전환 회귀 테스트
3. TestFlight 실기기에서 참가자별 출발 push 수신 및 상세 이동 acceptance
4. 권한 정책 함수 분리와 `VIEWER`/`EDITOR` 수정·삭제 차단 API 통합 테스트
5. 공개 `@handle`, 회원 검색 API, 친구/최근 공유 대상 모델 설계
6. 초대 회수·재발급 API와 만료 상태 영속화 배치 추가
7. push outbox/재시도 worker 추가
