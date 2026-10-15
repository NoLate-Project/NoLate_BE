# NoLate 일정 공유 정책 v1

상태: 제품 정책 확정, 구현 정합성 작업 중

최종 갱신: 2026-09-09 KST

## 1. 사용자에게 보이는 규칙

NoLate의 공유 단위는 아래 세 가지뿐이다.

| 공유 단위 | 공유 범위 | `보기` | `편집` |
| --- | --- | --- | --- |
| 일정 | 일정 한 건 | 일정 조회 | 해당 일정의 내용 수정·삭제 |
| 카테고리 | 카테고리와 현재·향후 일정 | 카테고리 및 포함 일정 조회 | 카테고리 안에서 일정 생성·수정·삭제 |
| 캘린더 | 캘린더의 모든 카테고리와 일정 | 전체 조회 | 카테고리·일정 생성·수정·삭제 |

신규 공유 화면에서 선택할 수 있는 권한은 `보기`와 `편집` 두 가지다. `OWNER`는 소유자를
표현하는 내부 권한이며 공유 API로 부여하지 않는다. 댓글 기능이 없는 동안 기존
`COMMENTER`는 조회 시 `보기`로 표시하고, 신규 쓰기에서는 만들지 않는다.

공유 단위와 권한은 서로 독립적이다. 예를 들어 한 사용자가 캘린더 `보기`와 그 안의 특정
카테고리 `편집`을 동시에 받으면 그 카테고리 일정에는 `편집`, 나머지 일정에는 `보기`가
적용된다. 동일 리소스에 여러 경로가 있으면 가장 강한 활성 권한을 사용한다.

## 2. 소유자와 편집자의 경계

`편집`은 콘텐츠 협업 권한이지 공유 관리 권한이 아니다.

개인 카테고리의 관리 소유자는 `schedule_categories.member_id`이며, 공유 캘린더에 속한
카테고리의 관리 소유자는 현재 calendar `OWNER`다. calendar category의 `member_id`는 최초
생성자/audit 값이므로 공유·이동 권한 판정에 사용하지 않는다. 캘린더 소유권을 이전하면 그
안의 category 관리 권한도 새 calendar owner에게 함께 넘어간다.

캘린더 소유권 이전 시 그 캘린더 안의 기존 일정·카테고리 직접 grant는 유지하고 새
owner가 관리한다. 대상, 권한, 상태, resource id는 바뀌지 않고 관리 owner만 새 calendar
owner로 바뀐다.

소유권 이전 transaction은 캘린더 자체, 그 안의 카테고리, 그 카테고리에 포함된 일정의
초대를 함께 처리한다.

- 아직 `PENDING`인 세 범위의 링크는 모두 회수하고 새 owner가 필요한 링크를 다시 발급한다.
- `ACCEPTED` 초대와 acceptance ledger는 유지하되 관리 owner를 새 owner로 바꿔, 기존
  수락자의 매칭 grant/멤버십이 유효한 동안 동일 토큰 재요청이 idempotent하게 응답할
  수 있게 한다.
- `EXPIRED`/`REVOKED` 레거시 레코드는 접근 권한이 아니며, 활성 링크로 표시하지 않는다.

이 규칙으로 목록에는 활성으로 보이지만 수락 시에만 실패하는 링크를 남기지 않는다.

- 일정 편집자는 일정의 제목, 시각, 메모, 공통 장소 등 공유 콘텐츠를 수정하고 일정을
  삭제할 수 있다.
- 카테고리 편집자는 그 카테고리 안에서 일정을 만들고, 수정하고, 삭제할 수 있다.
- 캘린더 편집자는 그 캘린더 안에서 카테고리와 일정을 만들고, 수정하고, 삭제할 수 있다.
- 모든 공유 대상은 출발지, 경로, 출발 알림 같은 자신의 이동 계획만 수정한다.
- 공유 대상 추가·제거, 권한 변경, 링크 회수, 소유권 이전, 캘린더 보관은 소유자만 한다.
- 일정 또는 카테고리를 다른 공유 범위로 옮기는 작업은 단순 콘텐츠 편집이 아니라 audience
  변경이다. 출발 범위의 소유자만 수행할 수 있다.

공유 편집자는 카테고리 또는 캘린더 배치를 바꿔 다른 사용자의 접근을 임의로 부여하거나
회수할 수 없다. API는 화면의 버튼 노출 여부와 무관하게 이 규칙을 서버에서 검사한다.

### 카테고리 편집자가 만든 일정

카테고리 `EDITOR`가 그 카테고리 안에서 만든 일정은 개인 일정으로 빠져나가지 않고 category
콘텐츠가 된다.

- 최초 작성자는 화면과 감사 정보에 계속 표시한다.
- 위에서 정의한 category 관리 소유자는 작성자와 무관하게 모든 포함 일정을 수정·삭제할 수
  있다.
- 작성자의 category grant가 해제되거나 작성자가 공유에서 나가도 일정은 category에 남는다.
- 작성자는 다른 직접 일정 공유, category 공유 또는 calendar membership이 없는 한 더 이상 그
  일정을 조회하거나 편집할 수 없다.
- 따라서 `schedules.member_id == currentMemberId`만으로 영구 owner 권한을 주면 안 된다.
  category 안에서 생성된 일정의 작성자와 현재 공유 리소스의 관리 소유자를 구분해 판정한다.

현재 `schedules.member_id`를 작성자와 소유자 양쪽 의미로 사용하는 경로는 이 계약을 만족할
때까지 구현 완료로 간주하지 않는다. 목록 native query, 상세 조회, 중앙 접근 정책, 수정·삭제,
push audience를 모두 같은 의미로 맞춰야 한다.

### 계정 탈퇴

- 활성 공유 캘린더 owner는 소유권을 이전하거나 캘린더를 보관하기 전에 탈퇴할 수 없다.
- 캘린더 `EDITOR`/`VIEWER` 또는 직접 카테고리 `EDITOR`가 탈퇴해도 그가 만든
  카테고리·일정은 기존 container의 콘텐츠로 남는다. 탈퇴 계정의 작성자 참조는
  감사 안정성을 위해 유지하되 계정 자체는 비식별화한다.
- 개인 카테고리 owner가 탈퇴하면 그 카테고리와 owner가 소유한 일정은 삭제한다. 다른
  회원이 작성한 일정 row는 삭제하지 않고 삭제되는 카테고리의 canonical id를 해제해
  standalone 작성자 소유 일정으로 전환한다. 기존 category snapshot은 표시 정보로만
  남고 ACL 경계로 재사용하지 않는다.
- 탈퇴로 사라지는 공유 범위의 직접 grant, 초대 acceptance, push job, 개인 이동 상태는
  같은 transaction에서 정리한다.

## 3. 카테고리와 캘린더

- 카테고리는 정확히 한 위치에 속한다. `calendar_id = null`은 개인 캘린더, 값이 있으면 해당
  공유 캘린더다.
- 카테고리 공유 권한은 캘린더 멤버십과 별개다. 카테고리만 공유받은 사용자는 같은 캘린더의
  다른 카테고리를 볼 수 없다.
- 공유 캘린더 안의 카테고리도 카테고리 단위로 공유할 수 있다.
- 공유 캘린더의 멤버가 아니어도 카테고리 `편집` 권한이 있으면 그 카테고리 안에서 일정을
  만들고 수정할 수 있다. 이 작업으로 생성된 일정은 해당 캘린더 멤버에게도 보인다.
- 캘린더 멤버십을 제거해도 별도의 일정 또는 카테고리 공유가 남아 있으면 그 범위의 접근은
  유지된다. 화면에는 접근이 남는 이유를 구분해 표시한다.

메인 `전체 일정`은 개인 일정, 받은 일정, 받은 카테고리, 참여 중인 캘린더를 합쳐 보여준다.
캘린더 선택은 접근 권한을 바꾸는 기능이 아니라 표시/숨김 필터다.

## 4. 카테고리 이동 계약

v1에서 지원하는 이동은 `개인 -> 공유 캘린더`와 `공유 캘린더 -> 공유 캘린더`다. 공유
캘린더에서 개인 캘린더로 꺼내는 작업은 다른 작성자의 일정 소유권과 노출 위치가 불명확하므로
별도 추출 정책이 정해질 때까지 제공하지 않는다.

카테고리 이동은 다음 계약을 만족해야 한다.

1. 카테고리와 그 안의 모든 활성 일정이 하나의 트랜잭션에서 이동한다.
2. 카테고리 id, 일정 id, 일정의 최초 작성자, 직접 일정·카테고리 공유의 대상·권한·상태는
   바뀌지 않는다. 단, 공유 row의 관리 owner는 도착 캘린더 owner로 바뀐다.
3. 카테고리와 포함 일정의 `PENDING` 링크, `ACCEPTED` 초대, acceptance ledger는 유지되고
   관리 owner는 도착 캘린더 owner로 바뀐다. 따라서 이동 전 수락자의 token 재요청도
   매칭 grant가 유효한 동안 idempotent하게 처리한다.
4. 출발 캘린더 멤버의 캘린더 경유 접근은 종료되고 도착 캘린더 멤버의 접근은 시작된다.
5. 카테고리 직접 공유 대상은 캘린더 멤버 여부와 상관없이 기존 권한을 유지한다.
6. 동일 사용자가 여러 경로로 접근하면 가장 강한 권한을 적용한다.
7. 이동 전 미리보기는 일정 수, 기존 카테고리 직접 공유 유지 인원, 포함 일정의 직접 공유
   grant 유지 건수, 이동으로 category audience에 새로 들어오는 인원, audience에서 빠지는
   인원을 각각 보여준다. category audience는 actual owner, 직접 category grant, 출발·도착
   calendar membership만으로 계산한다. 일부 일정에만 접근하는 직접 schedule grant는 category
   audience 차집합에 포함하지 않는다.
8. 이동 후 실제 접근을 잃은 사용자에게만 출발 알림 job과 비공개 이동 상태를 정리한다.
9. 출발·도착 캘린더와 직접 카테고리 공유 audience의 캐시 revision을 같은 commit에서
   무효화한다.

카테고리 identity와 기존 링크를 보존하기 위해 이동 과정에서 source category를 자동으로
다른 category에 합쳐 삭제하지 않는다. 도착 캘린더에 같은 이름이 있으면 두 id를 유지한 채
이동하고, 사용자가 나중에 이름을 변경할 수 있게 한다. category 병합은 grant, 초대 수락
ledger, 알림 source를 함께 이전하는 별도 기능으로 취급한다.

공유 캘린더에서 이동할 때에는 출발 캘린더 `OWNER`만 이동을 시작할 수 있고, 도착 캘린더에는
`OWNER` 또는 `EDITOR` 권한이 있어야 한다. 개인 카테고리는 그 카테고리 소유자만 이동할 수
있다.

## 5. API 계약

신규 직접 공유와 링크 초대 요청의 외부 권한 값은 `VIEWER | EDITOR`다. 레거시 응답의
`COMMENTER`는 클라이언트에서 `VIEWER`로 표현한다.

```http
POST   /api/schedules/{scheduleId}/shares
PATCH  /api/schedules/{scheduleId}/shares/{shareId}
POST   /api/schedules/{scheduleId}/shares/invitations

POST   /api/schedule-categories/{categoryId}/shares
PATCH  /api/schedule-categories/{categoryId}/shares/{shareId}
POST   /api/schedule-categories/{categoryId}/shares/invitations

POST   /api/schedule-calendars/{calendarId}/members
PATCH  /api/schedule-calendars/{calendarId}/members/{memberId}
POST   /api/schedule-calendars/{calendarId}/invitations
```

카테고리 이동 미리보기와 실행은 같은 권한 판단 및 같은 destination 표현을 사용해야 한다.
미리보기를 통과했다는 사실은 실행 권한을 보장하지 않으며, 실행 transaction에서 현재
멤버십과 audience를 다시 잠그고 검사한다.

미리보기 응답의 `gainedAccessMemberCount`/`lostAccessMemberCount`는 각각 이동 후와 이동 전
category audience의 정확한 차집합 크기다. `sourceCalendarMemberCount`/
`destinationCalendarMemberCount`는 캘린더의 전체 활성 멤버 수일 뿐 실제 접근 증감으로
표시하면 안 된다. 이전 서버 응답에 증감 필드가 없는 클라이언트만 전체 멤버 수라고
명시해 fallback 표시한다.

`retainedDirectShareCount`는 유지되는 활성 category 직접 공유 인원 수이고,
`retainedDirectScheduleShareCount`는 포함 일정에 남아 있는 활성 schedule 직접 공유 grant
건수다. 후자는 카테고리 전체 접근이 아니므로 gained/lost 계산에는 사용하지 않는다.

```http
GET  /api/schedule-categories/{categoryId}/move-preview?calendarId={destinationCalendarId}
POST /api/schedule-categories/{categoryId}/move
```

이동 요청의 `mergeIntoCategoryId`는 v1 신규 클라이언트에서 보내지 않는다. 과거 클라이언트가
보내더라도 직접 공유, 초대, 수락 이력이 있는 category를 자동 병합해서는 안 된다.

## 6. 레거시 backfill 주의사항

`2026-07-23-shared-calendars.sql`은 활성 category share마다 `legacy_category_id`가 있는
캘린더를 만들고 수신자를 calendar member로 복제한다. 이후
`2026-08-10-calendar-scoped-categories.sql`은 원래 category를 그 캘린더에 넣는다. 이는
카테고리와 캘린더를 모두 1급 공유 단위로 유지하는 현재 정책에서는 두 개의 활성 grant가 같은
일정에 중복되는 상태다.

그 상태를 그대로 운영하면 다음 문제가 생긴다.

- category share를 해제해도 복제된 calendar membership 때문에 접근이 남는다.
- 자동 생성 캘린더에 다른 category를 추가하면 과거 category 수신자에게 뜻하지 않은 접근이
  생긴다.
- category를 다른 calendar로 옮겨도 과거 자동 생성 calendar membership이 남는다.
- `calendar_id`가 생긴 category를 legacy 전용으로 거부하면 기존 pending CATEGORY 링크를
  수락할 수 없다.

운영 DB에서는 자동 DML로 한쪽 grant를 지우지 않는다. 먼저
`legacy_category_id IS NOT NULL` 캘린더를 아래 세 종류로 분류한다.

1. **미적용**: migration 자체가 적용되지 않음. 신규 정책 코드 배포 전 기존 backfill을 그대로
   실행하지 않는다.
2. **자동 생성 후 미사용**: legacy category 외에 콘텐츠·멤버·초대 변경이 없음. category share를
   정본으로 되돌리는 reconciliation 대상이다.
3. **사용자가 캘린더로 채택**: 다른 category, 별도 calendar invitation/member, 설정 변경이 있음.
   자동 정리하지 않고 사용자별 검토 또는 명시적 전환 대상이다.

정리 전후에는 최소한 아래 불변식을 확인한다.

- 활성 category share 한 건당 동일 대상의 의도하지 않은 legacy calendar membership 수
- 삭제되지 않은 category가 참조하는 활성 calendar 존재 여부
- schedule의 `calendar_id`와 category의 `calendar_id` 일치 여부
- `PENDING` CATEGORY invitation이 가리키는 활성 category 존재 여부
- 이동·병합으로 삭제된 category를 가리키는 invitation/notification/acceptance 수
- 접근 경로를 모두 합산한 결과와 실제 push/cache audience의 일치 여부

## 7. 필수 계약 테스트

릴리스 조건은 단순 API 성공이 아니라 다음 접근 결과다.

- 일정·카테고리·캘린더 각각 `VIEWER` 조회 성공과 콘텐츠 쓰기 거부
- 일정·카테고리·캘린더 각각 `EDITOR` 허용 범위의 생성·수정·삭제 성공
- `EDITOR`의 공유 관리, 권한 변경, category 이동 거부
- 캘린더 비멤버 category `EDITOR`의 일정 생성·수정·삭제 성공
- category owner가 category `EDITOR` 작성 일정을 조회·수정·삭제할 수 있는지 검증
- 작성자의 category grant 해제 뒤 작성자 접근은 종료되고 일정은 category에 남는지 검증
- category `EDITOR`의 category/calendar assignment 변경 거부
- category 이동 뒤 category id, 직접 grant, pending token 및 accepted-token 재시도 보존
- 이동 전·후 calendar member와 category recipient의 effective permission 검증
- 이동 미리보기의 gained/lost 수치가 겹침·직접 category grant를 제외한 실제 차집합과
  일치하는지 검증
- 동일 이름 category가 있어도 source identity가 보존되는지 검증
- 캘린더 소유권 이전 시 포함 일정·카테고리·캘린더의 pending 초대 회수와 accepted-token
  재요청 보존 검증
- 소유권 이전 또는 카테고리 이동으로 관리 owner가 바뀔 때 새 owner와의 차단 관계를 즉시
  반영해 전체 audience 캐시, push job, 기기 출발 알림이 함께 정리되는지 검증
- share 생성/회수, invitation 수락, calendar member 변경과 이동의 동시성 검증
- 공유 알림 outbox 생성 뒤 수신자가 owner를 차단한 경우 provider 직전 일정·카테고리·캘린더
  세 범위에서 모두 발송이 차단되는지 검증
- 실제 접근을 잃은 사용자만 push job이 취소되고 worker도 발송 직전 차단하는지 검증
- `SCHEDULE_ONLY` 편집자의 생성·가져오기·수정 요청에 출발지·경로·출발 알림이 포함돼도
  canonical 일정, 개인 plan, push job 또는 기기 알림에 저장되지 않는지 검증
- 작성자와 현재 resource owner가 다른 일정에서 공통 시각·목적지를 수정하면, 개인 plan 없이
  legacy canonical route/job만 가진 작성자의 stale job과 기기 알림이 취소되는지 검증
- 공유 기능 kill switch가 꺼진 동안 작성자가 아니라 실제 개인-category/calendar owner인
  사용자는 여섯 일정 조회 경로와 category 목록을 계속 사용할 수 있고 grant 수신자는 볼 수
  없는지 검증
- 캘린더 편집자/직접 카테고리 편집자의 탈퇴 후 작성 콘텐츠는 남고 작성자 접근만
  종료되는지 검증
- 소유권 이전 후 이전 owner가 탈퇴해도 캘린더 카테고리·일정이 삭제되지 않는지 검증
- 두 계정 E2E에서 직접 공유와 링크 공유를 각각 수락한 뒤 보기/편집 및 이동 결과 검증

## 8. 이번 정책에서 다루지 않는 것

- 댓글 또는 `COMMENTER` 전용 UX
- 캘린더에서 개인 캘린더로 category 추출
- 서로 다른 category identity를 합치는 기능
- 공유 대상자의 개인 이동 경로 복사
- 익명 링크를 데이터 접근 권한으로 사용하는 기능

링크는 로그인한 계정에게 grant를 부여하는 초대장일 뿐이며, 링크 원문만으로 일정 데이터에
접근할 수 없다.
