-- Four-occurrence native departure-alarm plan and ownership-safe fallback coverage (MySQL 8.x).
-- Stop all API and worker instances before applying DDL. This migration is additive/idempotent;
-- existing v1 receipts and desired states remain valid but are deliberately not coverage evidence.

DROP PROCEDURE IF EXISTS assert_departure_alarm_plan_v2_preconditions;
DELIMITER //
CREATE PROCEDURE assert_departure_alarm_plan_v2_preconditions()
BEGIN
    IF (
        SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name IN (
              'application_schema_migrations', 'departure_alarm_sync_state',
              'departure_alarm_fire_events', 'departure_alarm_schedule_receipts',
              'app_notifications'
          )
    ) <> 5 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'departure alarm plan v2 migration blocked: required tables are absent';
    END IF;
    IF (
        SELECT COUNT(*) FROM application_schema_migrations
        WHERE version IN (
            '2026-07-29-departure-alarm-sync-v1',
            '2026-08-01-departure-alarm-fire-evidence-v1',
            '2026-08-01-departure-alarm-schedule-receipts-v1'
        )
    ) <> 3 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'departure alarm plan v2 migration blocked: predecessor marker is absent';
    END IF;
    IF EXISTS (
        SELECT 1 FROM application_schema_migrations
        WHERE version = '2026-08-04-departure-alarm-plan-v2'
          AND description <> 'Four-occurrence native departure alarm with ownership-safe fallback'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'departure alarm plan v2 migration blocked: marker is incompatible';
    END IF;
END//
DELIMITER ;

CALL assert_departure_alarm_plan_v2_preconditions();
DROP PROCEDURE assert_departure_alarm_plan_v2_preconditions;

DROP PROCEDURE IF EXISTS add_departure_alarm_plan_column;
DELIMITER //
CREATE PROCEDURE add_departure_alarm_plan_column(
    IN target_table VARCHAR(64),
    IN target_column VARCHAR(64),
    IN column_ddl TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = target_table
          AND column_name = target_column
    ) THEN
        SET @departure_alarm_plan_column_ddl = column_ddl;
        PREPARE departure_alarm_plan_column_statement
            FROM @departure_alarm_plan_column_ddl;
        EXECUTE departure_alarm_plan_column_statement;
        DEALLOCATE PREPARE departure_alarm_plan_column_statement;
    END IF;
END//
DELIMITER ;

CALL add_departure_alarm_plan_column(
    'departure_alarm_sync_state',
    'alarm_plan_schema_version',
    'ALTER TABLE departure_alarm_sync_state ADD COLUMN alarm_plan_schema_version VARCHAR(8) NULL COMMENT ''Complete occurrence plan schema; 2 for v2'''
);
CALL add_departure_alarm_plan_column(
    'departure_alarm_sync_state',
    'alarm_occurrences_json',
    'ALTER TABLE departure_alarm_sync_state ADD COLUMN alarm_occurrences_json LONGTEXT NULL COMMENT ''Canonical M15/M10/M5/M0 occurrence plan JSON'''
);
CALL add_departure_alarm_plan_column(
    'departure_alarm_sync_state',
    'validation_requested_at',
    'ALTER TABLE departure_alarm_sync_state ADD COLUMN validation_requested_at DATETIME(6) NULL COMMENT ''Latest capability refresh request'''
);
CALL add_departure_alarm_plan_column(
    'departure_alarm_sync_state',
    'validation_revision',
    'ALTER TABLE departure_alarm_sync_state ADD COLUMN validation_revision BIGINT NOT NULL DEFAULT 0 COMMENT ''Same-generation validation command nonce'''
);
CALL add_departure_alarm_plan_column(
    'departure_alarm_fire_events',
    'occurrence_id',
    'ALTER TABLE departure_alarm_fire_events ADD COLUMN occurrence_id VARCHAR(16) NULL COMMENT ''M15/M10/M5/M0; null for legacy evidence'''
);
CALL add_departure_alarm_plan_column(
    'departure_alarm_schedule_receipts',
    'device_token_id',
    'ALTER TABLE departure_alarm_schedule_receipts ADD COLUMN device_token_id BIGINT NULL COMMENT ''Server-frozen token row id'''
);
CALL add_departure_alarm_plan_column(
    'departure_alarm_schedule_receipts',
    'token_ownership_version',
    'ALTER TABLE departure_alarm_schedule_receipts ADD COLUMN token_ownership_version BIGINT NULL COMMENT ''Server-frozen token ownership epoch'''
);
CALL add_departure_alarm_plan_column(
    'departure_alarm_schedule_receipts',
    'occurrence_id',
    'ALTER TABLE departure_alarm_schedule_receipts ADD COLUMN occurrence_id VARCHAR(16) NULL COMMENT ''M15/M10/M5/M0; null for legacy receipt'''
);
CALL add_departure_alarm_plan_column(
    'departure_alarm_schedule_receipts',
    'mutation_sequence',
    'ALTER TABLE departure_alarm_schedule_receipts ADD COLUMN mutation_sequence BIGINT NULL COMMENT ''Monotonic native apply revision'''
);
CALL add_departure_alarm_plan_column(
    'app_notifications',
    'native_alarm_covered_recipient_count',
    'ALTER TABLE app_notifications ADD COLUMN native_alarm_covered_recipient_count INT NOT NULL DEFAULT 0 COMMENT ''Recipients served by current native alarm evidence'''
);

-- occurrence_id is part of the physical fire identity. Without it, a snoozed M15 that lands on
-- M10 is incorrectly deduplicated against the independent M10 occurrence.
SET @departure_alarm_legacy_fire_unique_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'departure_alarm_fire_events'
      AND index_name = 'uk_departure_alarm_fire_member_device_trigger'
);
SET @departure_alarm_legacy_fire_unique_ddl = IF(
    @departure_alarm_legacy_fire_unique_exists > 0,
    'ALTER TABLE departure_alarm_fire_events DROP INDEX uk_departure_alarm_fire_member_device_trigger',
    'DO 0'
);
PREPARE departure_alarm_legacy_fire_unique_statement
    FROM @departure_alarm_legacy_fire_unique_ddl;
EXECUTE departure_alarm_legacy_fire_unique_statement;
DEALLOCATE PREPARE departure_alarm_legacy_fire_unique_statement;

SET @departure_alarm_occurrence_fire_unique_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'departure_alarm_fire_events'
      AND index_name = 'uk_departure_alarm_fire_member_device_occurrence_trigger'
);
SET @departure_alarm_occurrence_fire_unique_ddl = IF(
    @departure_alarm_occurrence_fire_unique_exists = 0,
    'ALTER TABLE departure_alarm_fire_events ADD UNIQUE INDEX uk_departure_alarm_fire_member_device_occurrence_trigger (member_id, device_fingerprint, alarm_id, generation, occurrence_id, scheduled_for)',
    'DO 0'
);
PREPARE departure_alarm_occurrence_fire_unique_statement
    FROM @departure_alarm_occurrence_fire_unique_ddl;
EXECUTE departure_alarm_occurrence_fire_unique_statement;
DEALLOCATE PREPARE departure_alarm_occurrence_fire_unique_statement;

SET @departure_alarm_plan_constraint_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'departure_alarm_sync_state'
      AND constraint_name = 'chk_departure_alarm_sync_plan'
);
SET @departure_alarm_plan_constraint_ddl = IF(
    @departure_alarm_plan_constraint_exists = 0,
    'ALTER TABLE departure_alarm_sync_state ADD CONSTRAINT chk_departure_alarm_sync_plan CHECK ((alarm_plan_schema_version IS NULL AND alarm_occurrences_json IS NULL) OR (operation = ''UPSERT'' AND alarm_plan_schema_version = ''2'' AND alarm_occurrences_json IS NOT NULL))',
    'DO 0'
);
PREPARE departure_alarm_plan_constraint_statement FROM @departure_alarm_plan_constraint_ddl;
EXECUTE departure_alarm_plan_constraint_statement;
DEALLOCATE PREPARE departure_alarm_plan_constraint_statement;

SET @departure_alarm_validation_revision_constraint_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'departure_alarm_sync_state'
      AND constraint_name = 'chk_departure_alarm_sync_validation_revision'
);
SET @departure_alarm_validation_revision_constraint_ddl = IF(
    @departure_alarm_validation_revision_constraint_exists = 0,
    'ALTER TABLE departure_alarm_sync_state ADD CONSTRAINT chk_departure_alarm_sync_validation_revision CHECK (validation_revision BETWEEN 0 AND 9007199254740991)',
    'DO 0'
);
PREPARE departure_alarm_validation_revision_constraint_statement
    FROM @departure_alarm_validation_revision_constraint_ddl;
EXECUTE departure_alarm_validation_revision_constraint_statement;
DEALLOCATE PREPARE departure_alarm_validation_revision_constraint_statement;

SET @departure_alarm_fire_occurrence_constraint_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'departure_alarm_fire_events'
      AND constraint_name = 'chk_departure_alarm_fire_occurrence'
);
SET @departure_alarm_fire_occurrence_constraint_ddl = IF(
    @departure_alarm_fire_occurrence_constraint_exists = 0,
    'ALTER TABLE departure_alarm_fire_events ADD CONSTRAINT chk_departure_alarm_fire_occurrence CHECK (occurrence_id IS NULL OR occurrence_id IN (''M15'', ''M10'', ''M5'', ''M0''))',
    'DO 0'
);
PREPARE departure_alarm_fire_occurrence_constraint_statement
    FROM @departure_alarm_fire_occurrence_constraint_ddl;
EXECUTE departure_alarm_fire_occurrence_constraint_statement;
DEALLOCATE PREPARE departure_alarm_fire_occurrence_constraint_statement;

SET @departure_alarm_receipt_ownership_constraint_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'departure_alarm_schedule_receipts'
      AND constraint_name = 'chk_departure_alarm_receipt_ownership'
);
SET @departure_alarm_receipt_ownership_constraint_ddl = IF(
    @departure_alarm_receipt_ownership_constraint_exists = 0,
    'ALTER TABLE departure_alarm_schedule_receipts ADD CONSTRAINT chk_departure_alarm_receipt_ownership CHECK ((device_token_id IS NULL AND token_ownership_version IS NULL) OR (device_token_id > 0 AND token_ownership_version >= 0))',
    'DO 0'
);
PREPARE departure_alarm_receipt_ownership_constraint_statement
    FROM @departure_alarm_receipt_ownership_constraint_ddl;
EXECUTE departure_alarm_receipt_ownership_constraint_statement;
DEALLOCATE PREPARE departure_alarm_receipt_ownership_constraint_statement;

SET @departure_alarm_receipt_occurrence_constraint_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'departure_alarm_schedule_receipts'
      AND constraint_name = 'chk_departure_alarm_receipt_occurrence'
);
SET @departure_alarm_receipt_occurrence_constraint_ddl = IF(
    @departure_alarm_receipt_occurrence_constraint_exists = 0,
    'ALTER TABLE departure_alarm_schedule_receipts ADD CONSTRAINT chk_departure_alarm_receipt_occurrence CHECK ((occurrence_id IS NULL AND mutation_sequence IS NULL) OR (occurrence_id IN (''M15'', ''M10'', ''M5'', ''M0'') AND mutation_sequence > 0))',
    'DO 0'
);
PREPARE departure_alarm_receipt_occurrence_constraint_statement
    FROM @departure_alarm_receipt_occurrence_constraint_ddl;
EXECUTE departure_alarm_receipt_occurrence_constraint_statement;
DEALLOCATE PREPARE departure_alarm_receipt_occurrence_constraint_statement;

SET @departure_alarm_native_covered_constraint_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'app_notifications'
      AND constraint_name = 'chk_app_notifications_native_alarm_covered_count'
);
SET @departure_alarm_native_covered_constraint_ddl = IF(
    @departure_alarm_native_covered_constraint_exists = 0,
    'ALTER TABLE app_notifications ADD CONSTRAINT chk_app_notifications_native_alarm_covered_count CHECK (native_alarm_covered_recipient_count >= 0)',
    'DO 0'
);
PREPARE departure_alarm_native_covered_constraint_statement
    FROM @departure_alarm_native_covered_constraint_ddl;
EXECUTE departure_alarm_native_covered_constraint_statement;
DEALLOCATE PREPARE departure_alarm_native_covered_constraint_statement;

SET @departure_alarm_receipt_coverage_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'departure_alarm_schedule_receipts'
      AND index_name = 'idx_departure_alarm_receipt_coverage'
);
SET @departure_alarm_receipt_coverage_index_ddl = IF(
    @departure_alarm_receipt_coverage_index_exists = 0,
    'ALTER TABLE departure_alarm_schedule_receipts ADD INDEX idx_departure_alarm_receipt_coverage (member_id, schedule_id, generation, occurrence_id, trigger_at, device_token_id, token_ownership_version, mutation_sequence)',
    'DO 0'
);
PREPARE departure_alarm_receipt_coverage_index_statement
    FROM @departure_alarm_receipt_coverage_index_ddl;
EXECUTE departure_alarm_receipt_coverage_index_statement;
DEALLOCATE PREPARE departure_alarm_receipt_coverage_index_statement;

SET @departure_alarm_sync_validation_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'departure_alarm_sync_state'
      AND index_name = 'idx_departure_alarm_sync_validation'
);
SET @departure_alarm_sync_validation_index_ddl = IF(
    @departure_alarm_sync_validation_index_exists = 0,
    'ALTER TABLE departure_alarm_sync_state ADD INDEX idx_departure_alarm_sync_validation (operation, alarm_plan_schema_version, validation_requested_at, trigger_at, id)',
    'DO 0'
);
PREPARE departure_alarm_sync_validation_index_statement
    FROM @departure_alarm_sync_validation_index_ddl;
EXECUTE departure_alarm_sync_validation_index_statement;
DEALLOCATE PREPARE departure_alarm_sync_validation_index_statement;

CREATE TABLE IF NOT EXISTS departure_alarm_presentation_assignments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    logical_event_key VARCHAR(100) NOT NULL,
    schedule_id BIGINT NOT NULL,
    alarm_generation BIGINT NULL,
    occurrence_id VARCHAR(16) NOT NULL,
    trigger_at DATETIME(6) NOT NULL,
    device_token_id BIGINT NOT NULL,
    token_ownership_version BIGINT NOT NULL,
    device_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    platform VARCHAR(20) NOT NULL,
    presentation_mode VARCHAR(24) NOT NULL,
    semantic_warning_visible BOOLEAN NOT NULL DEFAULT FALSE,
    assigned_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_departure_alarm_assignment_event_ownership
        (member_id, logical_event_key, device_token_id, token_ownership_version),
    INDEX idx_departure_alarm_assignment_occurrence
        (schedule_id, alarm_generation, occurrence_id, trigger_at, assigned_at),
    INDEX idx_departure_alarm_assignment_member (member_id, id),
    INDEX idx_departure_alarm_assignment_measurement
        (trigger_at, platform, occurrence_id, presentation_mode, semantic_warning_visible, id),
    CONSTRAINT chk_departure_alarm_assignment_generation
        CHECK (alarm_generation IS NULL OR alarm_generation BETWEEN 0 AND 9007199254740991),
    CONSTRAINT chk_departure_alarm_assignment_occurrence
        CHECK (occurrence_id IN ('M15', 'M10', 'M5', 'M0')),
    CONSTRAINT chk_departure_alarm_assignment_ownership
        CHECK (device_token_id > 0 AND token_ownership_version >= 0),
    CONSTRAINT chk_departure_alarm_assignment_mode
        CHECK (presentation_mode IN ('NATIVE_ALARM', 'VISIBLE_FALLBACK')),
    CONSTRAINT chk_departure_alarm_assignment_platform
        CHECK (platform IN ('ANDROID', 'IOS', 'WEB', 'UNKNOWN')),
    CONSTRAINT chk_departure_alarm_assignment_semantic_warning
        CHECK (semantic_warning_visible IN (FALSE, TRUE))
);

CALL add_departure_alarm_plan_column(
    'departure_alarm_presentation_assignments',
    'platform',
    'ALTER TABLE departure_alarm_presentation_assignments ADD COLUMN platform VARCHAR(20) NOT NULL DEFAULT ''UNKNOWN'' COMMENT ''Immutable token platform at assignment'' AFTER device_fingerprint'
);
CALL add_departure_alarm_plan_column(
    'departure_alarm_presentation_assignments',
    'semantic_warning_visible',
    'ALTER TABLE departure_alarm_presentation_assignments ADD COLUMN semantic_warning_visible BOOLEAN NOT NULL DEFAULT FALSE COMMENT ''Visible safety/traffic warning expected in addition to reminder assignment'' AFTER presentation_mode'
);

SET @departure_alarm_assignment_warning_constraint_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'departure_alarm_presentation_assignments'
      AND constraint_name = 'chk_departure_alarm_assignment_semantic_warning'
);
SET @departure_alarm_assignment_warning_constraint_ddl = IF(
    @departure_alarm_assignment_warning_constraint_exists = 0,
    'ALTER TABLE departure_alarm_presentation_assignments ADD CONSTRAINT chk_departure_alarm_assignment_semantic_warning CHECK (semantic_warning_visible IN (FALSE, TRUE))',
    'DO 0'
);
PREPARE departure_alarm_assignment_warning_constraint_statement
    FROM @departure_alarm_assignment_warning_constraint_ddl;
EXECUTE departure_alarm_assignment_warning_constraint_statement;
DEALLOCATE PREPARE departure_alarm_assignment_warning_constraint_statement;

SET @departure_alarm_assignment_platform_constraint_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'departure_alarm_presentation_assignments'
      AND constraint_name = 'chk_departure_alarm_assignment_platform'
);
SET @departure_alarm_assignment_platform_constraint_ddl = IF(
    @departure_alarm_assignment_platform_constraint_exists = 0,
    'ALTER TABLE departure_alarm_presentation_assignments ADD CONSTRAINT chk_departure_alarm_assignment_platform CHECK (platform IN (''ANDROID'', ''IOS'', ''WEB'', ''UNKNOWN''))',
    'DO 0'
);
PREPARE departure_alarm_assignment_platform_constraint_statement
    FROM @departure_alarm_assignment_platform_constraint_ddl;
EXECUTE departure_alarm_assignment_platform_constraint_statement;
DEALLOCATE PREPARE departure_alarm_assignment_platform_constraint_statement;

SET @departure_alarm_assignment_measurement_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'departure_alarm_presentation_assignments'
      AND index_name = 'idx_departure_alarm_assignment_measurement'
);
SET @departure_alarm_assignment_measurement_index_ddl = IF(
    @departure_alarm_assignment_measurement_index_exists = 0,
    'ALTER TABLE departure_alarm_presentation_assignments ADD INDEX idx_departure_alarm_assignment_measurement (trigger_at, platform, occurrence_id, presentation_mode, semantic_warning_visible, id)',
    'DO 0'
);
PREPARE departure_alarm_assignment_measurement_index_statement
    FROM @departure_alarm_assignment_measurement_index_ddl;
EXECUTE departure_alarm_assignment_measurement_index_statement;
DEALLOCATE PREPARE departure_alarm_assignment_measurement_index_statement;

DROP PROCEDURE add_departure_alarm_plan_column;

DROP PROCEDURE IF EXISTS assert_departure_alarm_plan_v2_postconditions;
DELIMITER //
CREATE PROCEDURE assert_departure_alarm_plan_v2_postconditions()
BEGIN
    IF (
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND (
            (table_name = 'departure_alarm_sync_state' AND
                column_name IN (
                    'alarm_plan_schema_version', 'alarm_occurrences_json',
                    'validation_requested_at', 'validation_revision'
                )) OR
            (table_name = 'departure_alarm_fire_events' AND column_name = 'occurrence_id') OR
            (table_name = 'departure_alarm_schedule_receipts' AND
                column_name IN (
                    'device_token_id', 'token_ownership_version',
                    'occurrence_id', 'mutation_sequence'
                )) OR
            (table_name = 'app_notifications' AND
                column_name = 'native_alarm_covered_recipient_count')
        )
    ) <> 10 OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_sync_state'
          AND column_name = 'validation_revision'
          AND data_type = 'bigint'
          AND is_nullable = 'NO'
          AND column_default = '0'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'app_notifications'
          AND column_name = 'native_alarm_covered_recipient_count'
          AND data_type = 'int'
          AND is_nullable = 'NO'
          AND column_default = '0'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'departure alarm plan v2 verification failed: columns are absent';
    END IF;
    IF (
        SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_sync_state'
          AND index_name = 'idx_departure_alarm_sync_validation'
    ) <> 5 OR (
        SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_sync_state'
          AND index_name = 'idx_departure_alarm_sync_validation'
    ) <> 'operation,alarm_plan_schema_version,validation_requested_at,trigger_at,id' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'departure alarm plan v2 verification failed: validation index is absent';
    END IF;
    IF (
        SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_fire_events'
          AND index_name = 'uk_departure_alarm_fire_member_device_occurrence_trigger'
          AND non_unique = 0
    ) <> 6 OR (
        SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_fire_events'
          AND index_name = 'uk_departure_alarm_fire_member_device_occurrence_trigger'
    ) <> 'member_id,device_fingerprint,alarm_id,generation,occurrence_id,scheduled_for' OR EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_fire_events'
          AND index_name = 'uk_departure_alarm_fire_member_device_trigger'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'departure alarm plan v2 verification failed: fire occurrence identity is absent';
    END IF;
    IF (
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_presentation_assignments'
    ) <> 14 OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_presentation_assignments'
          AND column_name = 'semantic_warning_visible'
          AND data_type = 'tinyint'
          AND is_nullable = 'NO'
          AND column_default IN ('0', 'FALSE')
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_presentation_assignments'
          AND column_name = 'platform'
          AND data_type = 'varchar'
          AND character_maximum_length = 20
          AND is_nullable = 'NO'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'departure alarm plan v2 verification failed: assignment table is absent';
    END IF;
    IF (
        SELECT COUNT(*) FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE() AND (
            (table_name = 'departure_alarm_sync_state' AND constraint_name IN (
                'chk_departure_alarm_sync_plan',
                'chk_departure_alarm_sync_validation_revision'
            )) OR
            (table_name = 'departure_alarm_fire_events' AND
                constraint_name = 'chk_departure_alarm_fire_occurrence') OR
            (table_name = 'departure_alarm_schedule_receipts' AND constraint_name IN (
                'chk_departure_alarm_receipt_ownership',
                'chk_departure_alarm_receipt_occurrence'
            )) OR
            (table_name = 'app_notifications' AND
                constraint_name = 'chk_app_notifications_native_alarm_covered_count') OR
            (table_name = 'departure_alarm_presentation_assignments' AND constraint_name IN (
                'chk_departure_alarm_assignment_generation',
                'chk_departure_alarm_assignment_occurrence',
                'chk_departure_alarm_assignment_ownership',
                'chk_departure_alarm_assignment_mode',
                'chk_departure_alarm_assignment_platform',
                'chk_departure_alarm_assignment_semantic_warning'
            ))
        )
    ) <> 12 OR (
        SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_schedule_receipts'
          AND index_name = 'idx_departure_alarm_receipt_coverage'
    ) <> 8 OR (
        SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_schedule_receipts'
          AND index_name = 'idx_departure_alarm_receipt_coverage'
    ) <> 'member_id,schedule_id,generation,occurrence_id,trigger_at,device_token_id,token_ownership_version,mutation_sequence' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'departure alarm plan v2 verification failed: constraints/index are absent';
    END IF;
    IF (
        SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_presentation_assignments'
          AND index_name = 'uk_departure_alarm_assignment_event_ownership'
          AND non_unique = 0
    ) <> 4 OR (
        SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_presentation_assignments'
          AND index_name = 'uk_departure_alarm_assignment_event_ownership'
    ) <> 'member_id,logical_event_key,device_token_id,token_ownership_version' OR (
        SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_presentation_assignments'
          AND index_name = 'idx_departure_alarm_assignment_occurrence'
    ) <> 5 OR (
        SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_presentation_assignments'
          AND index_name = 'idx_departure_alarm_assignment_occurrence'
    ) <> 'schedule_id,alarm_generation,occurrence_id,trigger_at,assigned_at' OR (
        SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_presentation_assignments'
          AND index_name = 'idx_departure_alarm_assignment_member'
    ) <> 2 OR (
        SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_presentation_assignments'
          AND index_name = 'idx_departure_alarm_assignment_member'
    ) <> 'member_id,id' OR (
        SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_presentation_assignments'
          AND index_name = 'idx_departure_alarm_assignment_measurement'
    ) <> 6 OR (
        SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_presentation_assignments'
          AND index_name = 'idx_departure_alarm_assignment_measurement'
    ) <> 'trigger_at,platform,occurrence_id,presentation_mode,semantic_warning_visible,id' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'departure alarm plan v2 verification failed: assignment indexes are absent';
    END IF;
    IF EXISTS (
        SELECT 1 FROM departure_alarm_sync_state
        WHERE validation_revision < 0 OR validation_revision > 9007199254740991
        LIMIT 1
    ) OR EXISTS (
        SELECT 1 FROM departure_alarm_schedule_receipts
        WHERE (device_token_id IS NULL) <> (token_ownership_version IS NULL)
           OR (occurrence_id IS NULL) <> (mutation_sequence IS NULL)
        LIMIT 1
    ) OR EXISTS (
        SELECT 1 FROM app_notifications
        WHERE native_alarm_covered_recipient_count < 0
        LIMIT 1
    ) OR EXISTS (
        SELECT 1 FROM departure_alarm_presentation_assignments
        WHERE semantic_warning_visible NOT IN (FALSE, TRUE)
           OR platform NOT IN ('ANDROID', 'IOS', 'WEB', 'UNKNOWN')
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'departure alarm plan v2 verification failed: invalid data remains';
    END IF;
END//
DELIMITER ;

CALL assert_departure_alarm_plan_v2_postconditions();
DROP PROCEDURE assert_departure_alarm_plan_v2_postconditions;

INSERT INTO application_schema_migrations(version, description, applied_at)
SELECT
    '2026-08-04-departure-alarm-plan-v2',
    'Four-occurrence native departure alarm with ownership-safe fallback',
    CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (
    SELECT 1 FROM application_schema_migrations
    WHERE version = '2026-08-04-departure-alarm-plan-v2'
);

SELECT COUNT(*) AS departure_alarm_plan_v2_marker_count
FROM application_schema_migrations
WHERE version = '2026-08-04-departure-alarm-plan-v2';

SELECT table_name, column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE() AND (
    (table_name = 'departure_alarm_sync_state' AND
        column_name IN (
            'alarm_plan_schema_version', 'alarm_occurrences_json',
            'validation_requested_at', 'validation_revision'
        )) OR
    (table_name = 'departure_alarm_fire_events' AND column_name = 'occurrence_id') OR
    (table_name = 'departure_alarm_schedule_receipts' AND
        column_name IN (
            'device_token_id', 'token_ownership_version', 'occurrence_id', 'mutation_sequence'
        )) OR
    (table_name = 'app_notifications' AND
        column_name = 'native_alarm_covered_recipient_count') OR
    (table_name = 'departure_alarm_presentation_assignments' AND
        column_name IN ('platform', 'semantic_warning_visible'))
)
ORDER BY table_name, ordinal_position;
