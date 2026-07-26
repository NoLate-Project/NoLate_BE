-- Login-free Google Play account deletion request state.
--
-- Maintenance requirements:
--   1) Stop every old API and worker instance.
--   2) Confirm 2026-07-24-push-reliability-v4 is the active required marker.
--   3) Apply this file before deploying the account-deletion application code.
--   4) Keep ACCOUNT_DELETION_ENABLED=false until identity verification, retention policy,
--      public origin, support ownership and the dedicated HMAC secret are verified.
--
-- MySQL DDL commits implicitly. The application refuses production startup until the marker
-- at the bottom exists, and the marker is inserted only after schema postconditions pass.

DROP PROCEDURE IF EXISTS assert_account_deletion_preconditions;
DELIMITER //
CREATE PROCEDURE assert_account_deletion_preconditions()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'application_schema_migrations'
    ) OR NOT EXISTS (
        SELECT 1
        FROM application_schema_migrations
        WHERE version = '2026-07-24-push-reliability-v4'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'account deletion migration blocked: required push-reliability-v4 marker is absent';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'account_deletion_requests'
    ) OR EXISTS (
        SELECT 1
        FROM application_schema_migrations
        WHERE version = '2026-07-26-account-deletion-v1'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'account deletion migration blocked: partial or already-applied schema requires inspection';
    END IF;
END//
DELIMITER ;

CALL assert_account_deletion_preconditions();
DROP PROCEDURE assert_account_deletion_preconditions;

CREATE TABLE account_deletion_requests (
    id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Opaque public request UUID',
    identifier_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Domain-separated keyed digest of normalized account email',
    requester_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Domain-separated keyed digest of requester network address',
    member_id BIGINT NULL
        COMMENT 'Internal binding; cleared after terminal processing and never exposed publicly',
    observed_session_generation BIGINT NULL
        COMMENT 'Session generation captured before external verification',
    manual_review_required BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'Provider-aware support is required; this row cannot authorize cleanup',
    status VARCHAR(40) NOT NULL
        COMMENT 'READY_TO_DELIVER, VERIFICATION_SENT, VERIFICATION_UNAVAILABLE, VERIFIED, PROCESSING, COMPLETED, or REJECTED',
    verification_token_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    verification_attempt_count INT NOT NULL DEFAULT 0,
    verification_expires_at DATETIME(6) NULL,
    deletion_grant_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    deletion_grant_expires_at DATETIME(6) NULL,
    processing_started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    failure_code VARCHAR(40) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    retention_expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_account_deletion_requests_status_expiry (status, verification_expires_at),
    INDEX idx_account_deletion_requests_retention (retention_expires_at),
    INDEX idx_account_deletion_requests_processing (status, processing_started_at)
) COMMENT='Login-free account deletion verification and single-use grant state';

DROP PROCEDURE IF EXISTS assert_account_deletion_postconditions;
DELIMITER //
CREATE PROCEDURE assert_account_deletion_postconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'account_deletion_requests'
          AND column_name IN (
              'id',
              'identifier_hash',
              'requester_hash',
              'member_id',
              'observed_session_generation',
              'manual_review_required',
              'status',
              'verification_token_hash',
              'verification_attempt_count',
              'verification_expires_at',
              'deletion_grant_hash',
              'deletion_grant_expires_at',
              'processing_started_at',
              'completed_at',
              'failure_code',
              'created_at',
              'updated_at',
              'retention_expires_at'
          )
    ) <> 18 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'account deletion migration verification failed: required columns are absent';
    END IF;

    IF (
        SELECT COUNT(DISTINCT index_name)
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'account_deletion_requests'
          AND index_name IN (
              'PRIMARY',
              'idx_account_deletion_requests_status_expiry',
              'idx_account_deletion_requests_retention',
              'idx_account_deletion_requests_processing'
          )
    ) <> 4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'account deletion migration verification failed: required indexes are absent';
    END IF;
END//
DELIMITER ;

CALL assert_account_deletion_postconditions();
DROP PROCEDURE assert_account_deletion_postconditions;

INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES (
    '2026-07-26-account-deletion-v1',
    'Login-free account deletion verification, one-time confirmation, and retention state',
    CURRENT_TIMESTAMP(6)
);

-- Human-readable verification: both counts must be 1 and no request row should exist yet.
SELECT COUNT(*) AS required_previous_marker_count
FROM application_schema_migrations
WHERE version = '2026-07-24-push-reliability-v4';

SELECT COUNT(*) AS account_deletion_marker_count
FROM application_schema_migrations
WHERE version = '2026-07-26-account-deletion-v1';

SELECT COUNT(*) AS unexpected_initial_request_count
FROM account_deletion_requests;
