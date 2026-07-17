ALTER TABLE schedules
    ADD COLUMN external_source_key VARCHAR(64) NULL
    COMMENT 'Member-scoped external calendar occurrence idempotency key';

-- NULL인 일반 일정은 제한하지 않고, 외부 원본 키가 있는 일정만 회원별로 한 번 저장한다.
CREATE UNIQUE INDEX uk_schedules_member_external_source
    ON schedules (member_id, external_source_key);
