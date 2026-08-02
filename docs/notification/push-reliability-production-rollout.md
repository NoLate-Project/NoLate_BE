# Push reliability production rollout

이 배포는 online/rolling schema migration이 아니다. `app_notifications`,
`push_deliveries`, token ownership, JWT session fence를 동시에 바꾸므로 old writer와 새
DDL을 겹치면 누락 column insert, 잘못된 device ownership, 과거 event 재전송이 생길 수 있다.
아래 maintenance 순서를 생략하지 않는다.

## 1. 배포 전 준비

1. DB snapshot/backup과 복구 시점을 기록한다.
2. 새 이미지가 `spring.jpa.hibernate.ddl-auto=validate`,
   `spring.sql.init.mode=never`인지 artifact에서 확인한다.
3. `2026-07-22-app-notifications.sql`이 이미 적용되어 `app_notifications`가 있는지 확인한다.
   아직 없다면 old writer를 중지한 뒤 먼저 적용하고 아래 v4 3개 및 후속 migration으로
   진행한다.
4. 모든 old API 인스턴스, `SchedulePushJobWorker`, route-setup worker, outbox drainer를
   중지한다. 일부 인스턴스를 남긴 rolling rollout은 금지한다.
5. load balancer target과 scheduler/배치 목록이 0인지 확인하고, DB에서 아래 row count를
   두 번 연속 확인해 writer가 멈췄음을 검증한다. 조회 결과에는 token/device 원문을 넣지 않는다.

```sql
SELECT COUNT(*) AS existing_push_deliveries_table
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'push_deliveries';

-- existing_push_deliveries_table=1일 때만 실행한다. table 부재는 legacy row 0건이다.
SELECT COUNT(*) AS push_delivery_count FROM push_deliveries;

SELECT COUNT(*) AS legacy_schedule_event_count
FROM app_notifications
WHERE deduplication_key LIKE 'schedule-push-job:%';

SELECT COUNT(*) AS legacy_push_token_count
FROM push_device_token;

SELECT COUNT(*) AS legacy_schedule_push_job_count
FROM schedule_push_job;

SELECT COUNT(*) AS duplicate_token_fingerprint_groups
FROM (
    SELECT SHA2(CAST(token AS BINARY), 256) AS fingerprint
    FROM push_device_token
    GROUP BY SHA2(CAST(token AS BINARY), 256)
    HAVING COUNT(*) > 1
) duplicates;

SELECT COUNT(*) AS duplicate_global_device_fingerprint_groups
FROM (
    SELECT SHA2(CAST(device_id AS BINARY), 256) AS fingerprint
    FROM push_device_token
    WHERE device_id IS NOT NULL AND CHAR_LENGTH(device_id) > 0
    GROUP BY SHA2(CAST(device_id AS BINARY), 256)
    HAVING COUNT(*) > 1
) duplicates;

SELECT COUNT(*) AS revoked_legacy_category_share_sources
FROM app_notifications source
LEFT JOIN schedule_categories category
  ON category.id = source.category_id
 AND category.deleted = FALSE
LEFT JOIN schedule_category_shares grant_row
  ON grant_row.category_id = source.category_id
 AND grant_row.target_member_id = source.member_id
 AND grant_row.status = 'ACTIVE'
 AND grant_row.deleted = FALSE
WHERE source.type = 'CATEGORY_SHARE_RECEIVED'
  AND (category.id IS NULL OR grant_row.id IS NULL);

SELECT COUNT(*) AS revoked_legacy_calendar_share_sources
FROM app_notifications source
LEFT JOIN schedule_calendars calendar
  ON calendar.id = CAST(
      CASE
          WHEN JSON_VALID(source.data_json)
           AND JSON_TYPE(source.data_json) = 'OBJECT'
           AND JSON_UNQUOTE(JSON_EXTRACT(source.data_json, '$.calendarId'))
               REGEXP '^[1-9][0-9]*$'
          THEN JSON_UNQUOTE(JSON_EXTRACT(source.data_json, '$.calendarId'))
          ELSE NULL
      END AS UNSIGNED
  )
 AND calendar.status = 'ACTIVE'
 AND calendar.deleted = FALSE
LEFT JOIN schedule_calendar_members membership
  ON membership.calendar_id = calendar.id
 AND membership.member_id = source.member_id
 AND membership.status = 'ACTIVE'
 AND membership.deleted = FALSE
WHERE source.type = 'CALENDAR_SHARE_RECEIVED'
  AND (calendar.id IS NULL OR membership.id IS NULL);

SELECT COUNT(*) AS revoked_legacy_category_share_histories
FROM push_send_history history
LEFT JOIN schedule_categories category
  ON category.id = CAST(
      CASE
          WHEN JSON_VALID(history.data_json)
           AND JSON_TYPE(history.data_json) = 'OBJECT'
           AND JSON_UNQUOTE(JSON_EXTRACT(history.data_json, '$.categoryId'))
               REGEXP '^[1-9][0-9]*$'
          THEN JSON_UNQUOTE(JSON_EXTRACT(history.data_json, '$.categoryId'))
          ELSE NULL
      END AS UNSIGNED
  )
 AND category.deleted = FALSE
LEFT JOIN schedule_category_shares grant_row
  ON grant_row.category_id = category.id
 AND grant_row.target_member_id = history.member_id
 AND grant_row.status = 'ACTIVE'
 AND grant_row.deleted = FALSE
WHERE history.payload_type = 'CATEGORY_SHARE_RECEIVED'
  AND (category.id IS NULL OR grant_row.id IS NULL);

SELECT COUNT(*) AS revoked_legacy_calendar_share_histories
FROM push_send_history history
LEFT JOIN schedule_calendars calendar
  ON calendar.id = CAST(
      CASE
          WHEN JSON_VALID(history.data_json)
           AND JSON_TYPE(history.data_json) = 'OBJECT'
           AND JSON_UNQUOTE(JSON_EXTRACT(history.data_json, '$.calendarId'))
               REGEXP '^[1-9][0-9]*$'
          THEN JSON_UNQUOTE(JSON_EXTRACT(history.data_json, '$.calendarId'))
          ELSE NULL
      END AS UNSIGNED
  )
 AND calendar.status = 'ACTIVE'
 AND calendar.deleted = FALSE
LEFT JOIN schedule_calendar_members membership
  ON membership.calendar_id = calendar.id
 AND membership.member_id = history.member_id
 AND membership.status = 'ACTIVE'
 AND membership.deleted = FALSE
WHERE history.payload_type = 'CALENDAR_SHARE_RECEIVED'
  AND (calendar.id IS NULL OR membership.id IS NULL);
```

`push_deliveries` table이 이미 있으면 row count가 0이어야 하고, 나머지 오류 count도 모두
0이어야 한다. 특히 `legacy_push_token_count`, `legacy_schedule_push_job_count`, 그리고
네 `revoked_legacy_*_share_*` count는 반드시 0이어야 한다. SQL은 count/fingerprint만
보여주며 raw token/device id나 notification title/body/data를 운영 로그, ticket, chat에
복사하지 않는다.

## 2. Legacy drain

count가 0이 아니면 배포를 계속하지 않는다.

- `push_deliveries`: `push_send_history`와 provider evidence로 성공/모호/확정 실패를 분류하고
  감사 가능한 별도 보관본을 만든다. 중복 가능성이 있는 event를 새 key로 자동 변환하지 않는다.
- 이전 `schedule-push-job:*` inbox key: 해당 알림의 전달 evidence와 현재 job state를 대조해
  제품/운영자가 보관 또는 명시적 보상 event를 결정한다.
- `schedule_push_job`: v4 fingerprint는 제목·도착지·회원별 출발지/경로/이동수단·알림 정책을
  함께 묶지만 legacy row에는 그 전체 snapshot이 없다. 부분 hash를 정상 fingerprint로
  승격하면 첫 동일 PUT도 의미 변경으로 오판하므로, 활성 알림 회차를 모두 종료하거나 승인된
  보상 계획을 기록한 뒤 job row를 전량 drain한다. 새 앱은 future owner와 participant
  travel-plan job을 authoritative row에서 full runtime fingerprint로 재구성한다.
- token fingerprint 중복: 동일 provider token의 현재 account owner를 인증/session evidence로
  결정하고 stale row를 제거한다.
- global device fingerprint 중복: platform 값과 무관하게 installation owner를 하나로
  결정한다. 단순히 가장 큰 id를 선택하지 말고 최근 인증된 account registration evidence를
  사용한다.
- revoked legacy share source/history: 현재 ACTIVE category grant 또는 ACTIVE
  calendar+membership가 없는 `*_SHARE_RECEIVED`는 권한 근거가 아니라 private payload
  잔존물이다. 신규 logical event key로 바꾸거나 보관하지 않고, 승인된 evidence 보관 후
  history를 먼저 지우고 inbox source를 지운다. 같은 category/calendar를 가진 일반 schedule
  history는 삭제 대상이 아니다.

아래 drain은 모든 writer가 중지되고 backup/change 승인이 끝난 뒤에만 실행한다. 임시 테이블은
private payload가 아니라 PK만 담는다. `payload_type`이 정확히 share-received인 legacy history와
같은 타입의 inbox source만 대상으로 하므로, direct schedule grant가 남은 schedule push
history를 category/calendar 단위로 과삭제하지 않는다.

```sql
START TRANSACTION;

CREATE TEMPORARY TABLE revoked_legacy_share_history_ids (
    id BIGINT NOT NULL PRIMARY KEY
);
CREATE TEMPORARY TABLE revoked_legacy_share_source_ids (
    id BIGINT NOT NULL PRIMARY KEY
);

INSERT IGNORE INTO revoked_legacy_share_history_ids(id)
SELECT history.id
FROM push_send_history history
LEFT JOIN schedule_categories category
  ON category.id = CAST(
      CASE
          WHEN JSON_VALID(history.data_json)
           AND JSON_TYPE(history.data_json) = 'OBJECT'
           AND JSON_UNQUOTE(JSON_EXTRACT(history.data_json, '$.categoryId'))
               REGEXP '^[1-9][0-9]*$'
          THEN JSON_UNQUOTE(JSON_EXTRACT(history.data_json, '$.categoryId'))
          ELSE NULL
      END AS UNSIGNED
  )
 AND category.deleted = FALSE
LEFT JOIN schedule_category_shares grant_row
  ON grant_row.category_id = category.id
 AND grant_row.target_member_id = history.member_id
 AND grant_row.status = 'ACTIVE'
 AND grant_row.deleted = FALSE
WHERE history.payload_type = 'CATEGORY_SHARE_RECEIVED'
  AND (category.id IS NULL OR grant_row.id IS NULL);

INSERT IGNORE INTO revoked_legacy_share_history_ids(id)
SELECT history.id
FROM push_send_history history
LEFT JOIN schedule_calendars calendar
  ON calendar.id = CAST(
      CASE
          WHEN JSON_VALID(history.data_json)
           AND JSON_TYPE(history.data_json) = 'OBJECT'
           AND JSON_UNQUOTE(JSON_EXTRACT(history.data_json, '$.calendarId'))
               REGEXP '^[1-9][0-9]*$'
          THEN JSON_UNQUOTE(JSON_EXTRACT(history.data_json, '$.calendarId'))
          ELSE NULL
      END AS UNSIGNED
  )
 AND calendar.status = 'ACTIVE'
 AND calendar.deleted = FALSE
LEFT JOIN schedule_calendar_members membership
  ON membership.calendar_id = calendar.id
 AND membership.member_id = history.member_id
 AND membership.status = 'ACTIVE'
 AND membership.deleted = FALSE
WHERE history.payload_type = 'CALENDAR_SHARE_RECEIVED'
  AND (calendar.id IS NULL OR membership.id IS NULL);

INSERT IGNORE INTO revoked_legacy_share_source_ids(id)
SELECT source.id
FROM app_notifications source
LEFT JOIN schedule_categories category
  ON category.id = source.category_id
 AND category.deleted = FALSE
LEFT JOIN schedule_category_shares grant_row
  ON grant_row.category_id = source.category_id
 AND grant_row.target_member_id = source.member_id
 AND grant_row.status = 'ACTIVE'
 AND grant_row.deleted = FALSE
WHERE source.type = 'CATEGORY_SHARE_RECEIVED'
  AND (category.id IS NULL OR grant_row.id IS NULL);

INSERT IGNORE INTO revoked_legacy_share_source_ids(id)
SELECT source.id
FROM app_notifications source
LEFT JOIN schedule_calendars calendar
  ON calendar.id = CAST(
      CASE
          WHEN JSON_VALID(source.data_json)
           AND JSON_TYPE(source.data_json) = 'OBJECT'
           AND JSON_UNQUOTE(JSON_EXTRACT(source.data_json, '$.calendarId'))
               REGEXP '^[1-9][0-9]*$'
          THEN JSON_UNQUOTE(JSON_EXTRACT(source.data_json, '$.calendarId'))
          ELSE NULL
      END AS UNSIGNED
  )
 AND calendar.status = 'ACTIVE'
 AND calendar.deleted = FALSE
LEFT JOIN schedule_calendar_members membership
  ON membership.calendar_id = calendar.id
 AND membership.member_id = source.member_id
 AND membership.status = 'ACTIVE'
 AND membership.deleted = FALSE
WHERE source.type = 'CALENDAR_SHARE_RECEIVED'
  AND (calendar.id IS NULL OR membership.id IS NULL);

SELECT COUNT(*) AS approved_revoked_share_histories
FROM revoked_legacy_share_history_ids;
SELECT COUNT(*) AS approved_revoked_share_sources
FROM revoked_legacy_share_source_ids;

DELETE history
FROM push_send_history history
JOIN revoked_legacy_share_history_ids target ON target.id = history.id;

DELETE source
FROM app_notifications source
JOIN revoked_legacy_share_source_ids target ON target.id = source.id;

SELECT COUNT(*) AS remaining_approved_revoked_share_histories
FROM push_send_history history
JOIN revoked_legacy_share_history_ids target ON target.id = history.id;
SELECT COUNT(*) AS remaining_approved_revoked_share_sources
FROM app_notifications source
JOIN revoked_legacy_share_source_ids target ON target.id = source.id;

-- 두 remaining count가 0인 경우에만 승인된 change record와 대조 후 commit한다.
COMMIT;
```

위 evidence를 보관하고 `push_deliveries`/legacy event를 먼저 정리한 뒤, 승인된 운영 change로
legacy schedule job과 모든 기존 push token을 명시적으로 삭제한다. v4 이전 JWT에는 `sg`가
없으므로 어떤 기존 token row도 새 session fence를 넘어 보존하지 않는다. 아래 명령은 backup
완료, 모든 writer quiesce, 활성 reminder 처리/보상 결정, 삭제 change 승인 뒤 운영자가 직접
실행한다.

```sql
START TRANSACTION;
DELETE FROM schedule_push_job;
DELETE FROM push_device_token;
SELECT COUNT(*) AS legacy_schedule_push_job_count_after_drain
FROM schedule_push_job;
SELECT COUNT(*) AS legacy_push_token_count_after_drain
FROM push_device_token;
COMMIT;
```

commit 전 `legacy_push_token_count_after_drain=0`과
`legacy_schedule_push_job_count_after_drain=0`을 확인하고, commit 뒤에도 같은 count가 0인지
다시 확인한다.
기존 사용자는 새 버전에서 재로그인한 뒤 push token을 재등록해야 한다. 삭제/보관 과정에는 raw
token/device id 대신 row count와 승인 change ID만 기록한다. drain 뒤 위 preflight를 처음부터
다시 실행한다. migration 자체는 legacy token/job을 자동 삭제하지 않으며 한 row라도 남아
있으면 첫 DDL 전에 `SIGNAL`로 중단한다.

## 3. Manual migration

old writer가 모두 중지된 상태에서 정확히 다음 순서로 실행한다.

1. [`2026-07-24-push-deliveries.sql`](./2026-07-24-push-deliveries.sql)
2. [`2026-07-24-push-delivery-followup.sql`](./2026-07-24-push-delivery-followup.sql)
3. [`2026-07-24-push-delivery-linearization.sql`](./2026-07-24-push-delivery-linearization.sql)

v4 이후의 독립 migration은 각 migration의 predecessor marker 순서대로 같은 maintenance
window에서 계속 적용한다. 특히 아래 ACK capability migration은
`2026-08-01-departure-alarm-schedule-receipts-v1` marker가 정확히 한 건인 것을 먼저 확인한 뒤 마지막 후속
단계로 실행한다. API/worker를 먼저 재개하거나 marker를 수동 insert해서 predecessor 검사를
우회하지 않는다.

4. [`2026-08-01-push-delivery-ack-capability.sql`](../schedule/migrations/2026-08-01-push-delivery-ack-capability.sql)

4번은 legacy row를 capability v1으로 추정 backfill하지 않는다. 기존 row의 nullable 값은
그대로 measurement-coverage 대상에 남기고, 새 클라이언트가 token 등록에서 명시한 v1만 이후
delivery manifest에 동결한다. script의 postcondition이 두 nullable column, 정확한 cohort
index 순서, 두 CHECK constraint를 검증한 뒤에만
`2026-08-01-push-delivery-ack-capability-v1` marker를 기록한다.

3번은 precondition을 다시 검사하고 다음을 함께 적용한다.

- immutable app notification manifest/outbox lease와 optimistic version
- legacy `data_json`을 JSON object로 정규화하고 모든 inbox payload에
  `logicalEventKey`/`recipientMemberId` account binding을 검증
- case-sensitive token SHA-256 global unique
- platform과 member에 무관한 device SHA-256 global unique
- per-delivery ownership snapshot
- immutable shared-calendar id on source and delivery rows; `CALENDAR_SHARE_RECEIVED` dispatch
  revalidates an active calendar and active recipient membership under the recipient member lock
- `push_send_history` canonical logical event/category/calendar identity and exact
  member+event cleanup; legacy resource fallback is restricted to the matching
  `CATEGORY_SHARE_RECEIVED`/`CALENDAR_SHARE_RECEIVED` payload type
- revoked legacy share source와 source가 이미 사라진 standalone share history의
  pre/post fail-fast; 승인된 drain 없이는 marker 기록 금지
- claim 뒤 account ownership 이동을 막는 token별 provider dispatch lease와
  logout/withdraw 중 lease identity를 보존하는 retirement marker
- schedule notification generation/action receipt
- legacy schedule job 0건 보장과 full-fingerprint owner/participant startup 재구성
- `member.session_generation`
- 최종 postcondition과 `2026-07-24-push-reliability-v4` marker

`session_generation`은 기존 회원을 0으로 backfill한다. 구버전 JWT에는 서명된 `sg` claim이
없어 새 filter/refresh 경로에서 fail closed되므로 v4 배포 직후 기존 세션은 재로그인이
필요하다. DB에 아직 저장된 정상 legacy refresh token은 logout cleanup에만 허용하지만, 배포
전에 모든 legacy push token을 이미 전량 drain하므로 이 호환 경로가 provider endpoint 보존
수단이 되지는 않는다. 새 로그인으로 발급한 access/refresh token부터 현재 generation의 `sg`를
포함하며, 새 인증 context에서 push token을 다시 등록한다.

MySQL DDL은 implicit commit한다. 3번 도중 예기치 않은 DDL 오류가 나면 marker가 없으므로 새
애플리케이션은 시작되지 않는다. old app을 재개하거나 이미 적용된 statement를 임의로 되돌리지
말고, writer를 계속 중지한 채 DBA가 적용 지점을 확인해 roll-forward한다.

## 4. Post verification

3번 script 끝의 결과에서 모든 오류 count는 0, `case_distinct_fingerprints`는 1이어야 한다.
추가로 다음을 확인한다.

```sql
SELECT COUNT(*) AS remaining_legacy_push_tokens
FROM push_device_token;

SELECT COUNT(*) AS remaining_legacy_schedule_push_jobs
FROM schedule_push_job;

SELECT version, description, applied_at
FROM application_schema_migrations
WHERE version IN (
    '2026-07-24-push-reliability-v4',
    '2026-08-01-push-delivery-ack-capability-v1'
)
ORDER BY version;

SELECT table_name, column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('push_device_token', 'push_deliveries')
  AND column_name = 'delivery_ack_capability_version'
ORDER BY table_name;

SELECT index_name,
       GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns_in_index
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'push_deliveries'
  AND index_name = 'idx_push_deliveries_reliability_cohort'
GROUP BY index_name;

SELECT table_name, constraint_name, constraint_type
FROM information_schema.table_constraints
WHERE constraint_schema = DATABASE()
  AND table_name IN ('push_device_token', 'push_deliveries')
  AND constraint_name IN (
      'chk_push_device_token_ack_capability',
      'chk_push_deliveries_ack_capability'
  )
ORDER BY table_name;

SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns_in_index
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'push_device_token'
GROUP BY index_name
ORDER BY index_name;

SELECT COUNT(*) AS remaining_raw_opaque_unique_indexes
FROM (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'push_device_token'
      AND non_unique = 0
      AND index_name <> 'PRIMARY'
    GROUP BY index_name
    HAVING SUM(
        CASE WHEN column_name IN ('token', 'device_id') THEN 1 ELSE 0 END
    ) > 0
) raw_unique_indexes;

SELECT column_name, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND (
      (table_name = 'member' AND column_name = 'session_generation')
      OR
      (table_name = 'app_notifications' AND column_name IN (
          'calendar_id',
          'manifest_state', 'manifest_recipient_count', 'manifest_frozen_at',
          'dispatch_status', 'dispatch_attempt_count', 'dispatch_failure_count', 'next_dispatch_at',
          'dispatch_locked_by', 'dispatch_locked_at', 'dispatch_completed_at',
          'dispatch_failure_reason', 'version'
      ))
      OR
      (table_name = 'push_send_history' AND column_name IN (
          'logical_event_key', 'category_id', 'calendar_id'
      ))
  )
ORDER BY table_name, ordinal_position;

SELECT COUNT(*) AS invalid_notification_account_binding
FROM app_notifications
WHERE JSON_TYPE(data_json) <> 'OBJECT'
   OR JSON_EXTRACT(data_json, '$.logicalEventKey') IS NULL
   OR JSON_EXTRACT(data_json, '$.recipientMemberId') IS NULL
   OR JSON_UNQUOTE(JSON_EXTRACT(data_json, '$.logicalEventKey')) <> logical_event_key
   OR JSON_UNQUOTE(JSON_EXTRACT(data_json, '$.recipientMemberId')) <> CAST(member_id AS CHAR);
```

`remaining_legacy_push_tokens`와 migration 직후의
`remaining_legacy_schedule_push_jobs`는 0이어야 한다. token table에는 raw `token`/`device_id`
unique index나 member-scoped device fingerprint
unique index가 없어야 한다. `uk_push_device_token_token_fingerprint`와
`uk_push_device_token_device_fingerprint`만 opaque ownership unique 경계여야 한다.
`remaining_raw_opaque_unique_indexes`도 index 이름과 무관하게 0이어야 한다.
`invalid_notification_account_binding`도 0이어야 한다. valid JSON scalar/array였던 legacy
payload는 기존 앱의 `Map<String, String>` 계약 밖 데이터이므로 migration이 최소 account
binding object로 정규화한다.

두 marker query 결과는 각각 정확히 한 row여야 한다. capability column query는 두 table에서
각각 `int`/nullable 한 row를 반환해야 하고, cohort index는 정확히
`status,delivered_at,delivery_ack_capability_version,client_received_at` 순서여야 한다. CHECK
constraint도 table별 한 건이어야 한다. 하나라도 다르면 새 binary를 시작하지 말고 모든
writer를 중지한 채 migration postcondition 실패 원인을 조사한다.

아래 calendar snapshot/history/grant 검증도 모두 0이어야 한다. 0이 아니면 payload에서 id를
추정해 발송하지 않고 writer를 계속 중지한 채 승인된 source+history drain/보정을 수행한 뒤
migration postcondition을 다시 실행한다.

```sql
SELECT COUNT(*) AS invalid_calendar_notification_resource_snapshot
FROM app_notifications
WHERE type = 'CALENDAR_SHARE_RECEIVED'
  AND (
      calendar_id IS NULL
      OR JSON_UNQUOTE(JSON_EXTRACT(data_json, '$.calendarId')) <> CAST(calendar_id AS CHAR)
  );

SELECT COUNT(*) AS invalid_calendar_delivery_resource_snapshot
FROM push_deliveries delivery
JOIN app_notifications source
  ON source.member_id = delivery.member_id
 AND source.logical_event_key = delivery.event_key
WHERE source.type = 'CALENDAR_SHARE_RECEIVED'
  AND (
      delivery.calendar_id IS NULL
      OR delivery.calendar_id <> source.calendar_id
  );

SELECT COUNT(*) AS invalid_push_history_source_identity
FROM push_send_history
WHERE (payload_type = 'CATEGORY_SHARE_RECEIVED' AND category_id IS NULL)
   OR (payload_type = 'CALENDAR_SHARE_RECEIVED' AND calendar_id IS NULL);

SELECT COUNT(*) AS revoked_category_share_histories
FROM push_send_history history
WHERE history.payload_type = 'CATEGORY_SHARE_RECEIVED'
  AND NOT EXISTS (
      SELECT 1
      FROM schedule_categories category
      JOIN schedule_category_shares grant_row
        ON grant_row.category_id = category.id
       AND grant_row.target_member_id = history.member_id
       AND grant_row.status = 'ACTIVE'
       AND grant_row.deleted = FALSE
      WHERE category.id = history.category_id
        AND category.deleted = FALSE
  );

SELECT COUNT(*) AS revoked_calendar_share_histories
FROM push_send_history history
WHERE history.payload_type = 'CALENDAR_SHARE_RECEIVED'
  AND NOT EXISTS (
      SELECT 1
      FROM schedule_calendars calendar
      JOIN schedule_calendar_members membership
        ON membership.calendar_id = calendar.id
       AND membership.member_id = history.member_id
       AND membership.status = 'ACTIVE'
       AND membership.deleted = FALSE
      WHERE calendar.id = history.calendar_id
        AND calendar.status = 'ACTIVE'
        AND calendar.deleted = FALSE
  );
```

## 5. Deploy and resume

1. 첫 새 인스턴스에는 반드시 `SCHEDULE_PUSH_ENABLED=false`를 명시한다.
   이 값은 ETA schedule-push job claim만 멈춘다. Scheduling infrastructure와 route-setup,
   durable outbox, token/alarm/retention cleanup은 각자의 enable flag에 따라 계속 동작한다.
   따라서 push job claim 증가가 0인지 확인하되, 기존 outbox drain과 cleanup 활동은 정상으로
   간주한다. maintenance에서 모든 scheduler를 멈춰야 한다면
   `SPRING_TASK_SCHEDULING_ENABLED=false`를 별도로 사용하고 각 backlog의 영향도 함께 승인한다.
2. worker-off 상태로 새 애플리케이션 한 인스턴스를 배포한다. `ApplicationReadyEvent`
   startup backfill은 scheduler와 별개로 실행되므로 future owner와 participant travel-plan
   job이 full runtime fingerprint로 재구성됐는지 count를 확인한다. Backfill은 scan 전체를
   감싼 장기 transaction이 아니라 candidate별 `REQUIRES_NEW(member → schedule/plan → job)`
   commit이므로, 처리 중 member/job lock이 다음 candidate까지 누적되지 않는지도 staging의
   lock-wait/deadlock 지표로 확인한다.
3. Hibernate `validate`와 `ProductionSchemaVersionGuard` 통과를 확인한다. marker를 설정으로
   우회하거나 운영에서 `ddl-auto=update`로 바꾸지 않는다.
4. worker-off 인스턴스의 smoke test에서 로그인 후 새 generation JWT로 token을 재등록한다.
   mandatory drain 때문에 기존 endpoint는 하나도 승계되지 않으며, generation claim이 없는
   JWT는 의도적으로 재로그인이 필요하다. 동일 event/zero-device 검증용 row는 provider를
   호출하지 않는 승인된 진단 절차로만 만든다.
5. worker-off 인스턴스를 종료하고 실행 중 schedule-push claim이 다시 0인지 확인한다.
   같은 검증된 artifact를 `SCHEDULE_PUSH_ENABLED=true`로 명시해 제한된 한 인스턴스로
   재시작한다. 운영 startup guard는 이때 `SPRING_TASK_SCHEDULING_ENABLED=true`,
   `NOTIFICATION_PUSH_OUTBOX_ENABLED=true`, `FIREBASE_ENABLED=true`가 아니면 기동을 거절한다.
   route-setup, durable outbox와 cleanup worker는 이 전환과 독립적으로 계속 동작한다.
6. 동일 event redrive, zero-device event, token ownership transfer, outbox retry 지표와
   backlog/lease/error 지표가 안정된 뒤 나머지 새 인스턴스를 올린다.

Docker가 없는 개발 환경에서 MySQL Testcontainers 테스트가 skip될 수 있다. 실제 MySQL 8에서
v4 3개 script와 필수 후속 migration, global fingerprint 경합, 다중 인스턴스 claim,
schedule edit/backfill의
member→job gap→schedule 잠금, lock timeout/deadlock bounded retry를 실행한 결과를 staging
promotion gate로 남긴다. 느린 실제 provider를 사용한 lease recovery/confirmed failure
reconciliation과 FCM 응답 분류도 같은 gate에서 확인한다. provider 호출은 DB transaction
밖에서 실행하고, 짧은 transaction이 남긴 token별 lease만 유지한다. FCM connect/read/write
timeout의 최악 합은 `notification.push-token.provider-max-call-seconds`보다 작아야 하고,
provider max는 `dispatch-lease-seconds` 및 ownership-transfer wait보다 반드시 작아야 한다.
lease 획득 뒤 프로세스가 종료되면 delivery는 `DISPATCHING`으로 남아 자동 재전송되지 않으며,
logout/withdraw/remove는 활성 lease row를 즉시 삭제하지 않고 `retirement_requested`를
남긴다. 정상 provider 종료는 조건부 release에서 row를 삭제하고, 프로세스 종료는 TTL 뒤
reaper 또는 다음 registration이 삭제해 새 account가 과거 lease를 우회하지 못한다.
token lease 만료 뒤에만 등록/소유권 이전을 운영적으로 재개한다. 이 timeout/lease 불변식과
실제 느린 provider 중 ownership transfer 대기를 staging에서 검증한다. H2/단위 테스트만으로 이 gate를
대체하지 않는다.

artifact 기본값은 아래의 엄격한 부등식을 만족한다.

```text
FCM 5s(connect) + 30s(read) + 5s(write) = 40s
40s < provider max 60s < dispatch lease 600s
registration/ownership-transfer wait 70s > provider max 60s
```

firebase-admin 9.8.0의 단일 FCM 요청은 HTTP 503을 최대 네 번 재시도하고 각
`Retry-After`를 최대 60초까지 허용한다. 시작 검증은 고정된 SDK 계약의 최악 예산도
`(connect + read + write) * 5 + 60s * 4 = 440s < dispatch lease 600s`인지 확인한다.
ownership-transfer wait가 먼저 끝나면 등록 요청은 fail-closed로 실패할 뿐 활성 lease를
넘어 소유권을 이전하지 않는다. firebase-admin 버전을 올릴 때는 retry 상수, startup
invariant, 느린-provider staging gate를 함께 갱신하지 않으면 배포하지 않는다.

각 값은 `FIREBASE_CONNECT_TIMEOUT_MILLIS`, `FIREBASE_READ_TIMEOUT_MILLIS`,
`FIREBASE_WRITE_TIMEOUT_MILLIS`, `NOTIFICATION_PUSH_PROVIDER_MAX_CALL_SECONDS`,
`NOTIFICATION_PUSH_TOKEN_DISPATCH_LEASE_SECONDS`,
`NOTIFICATION_PUSH_TOKEN_DISPATCH_LEASE_WAIT_MILLIS`로 설정한다. 등호도 허용하지 않으며,
음수·0 또는 위 순서를 깨는 환경 변수 조합은 Firebase credential/provider 초기화 전에
startup을 실패시킨다. staging에서는 배포 artifact의 resolved configuration이 정확히
`5000/30000/5000ms`, `60/600s`, `70000ms`인지 먼저 확인한다.

## 6. Rollback policy

- token drain 전 첫 DDL 전 실패: v4 및 후속 migration을 적용하지 않고 old app/worker를 그대로
  재개할 수 있다.
- token/job drain 뒤 첫 DDL 전 실패: old app/worker를 재개할 수는 있지만 push token과
  schedule job row는 복원되지 않는다. snapshot 복원 승인이 없다면 사용자의
  재로그인/재등록과 old-app job 재구성 절차가 필요하며, stale token/job을 임의로 되살리지
  않는다.
- DDL 시작 후 실패: 모든 API/worker를 계속 중지한다. DB snapshot과 marker 상태를 보존하고
  migration 또는 애플리케이션 수정본으로 roll-forward한다.
- 새 앱 배포 후 실패: 새 API/worker를 즉시 모두 중지하되 marker나 새 column/index를 제거하지
  않는다. 구버전 앱은 frozen manifest/session generation/global ownership 계약을 쓰지 않으므로
  재배포하지 않는다.
- provider 호출 뒤 delivery가 `DISPATCHING`에 남은 경우: rollback이나 자동 FAILED 변환을
  하지 않는다. provider evidence가 모호하면 at-most-once 정책대로 억제하고, 필요 시 승인된
  새 logical event key로만 보상한다.

역방향 DDL은 지원하지 않는다. 복구 완료 뒤에도 marker는 실제 postcondition을 통과한 DB에만
남아 있어야 하며 수동 insert로 startup guard를 우회하지 않는다.
