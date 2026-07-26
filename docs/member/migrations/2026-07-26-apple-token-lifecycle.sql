-- Sign in with Apple authorization-code / durable revoke migration (MySQL 8.x).
--
-- Quiesce every old API instance first. Apply after
-- 2026-07-24-push-delivery-linearization.sql and before deploying the application version whose
-- production schema guard requires 2026-07-26-apple-token-lifecycle-v1.

DROP PROCEDURE IF EXISTS assert_apple_token_lifecycle_preconditions;
DELIMITER //
CREATE PROCEDURE assert_apple_token_lifecycle_preconditions()
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
                'apple token lifecycle migration blocked: push reliability v4 marker is absent';
    END IF;
END//
DELIMITER ;

CALL assert_apple_token_lifecycle_preconditions();
DROP PROCEDURE assert_apple_token_lifecycle_preconditions;

CREATE TABLE IF NOT EXISTS apple_provider_credentials (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Encrypted Apple provider credential primary key',
    credential_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Random envelope AAD identifier',
    member_id BIGINT NULL COMMENT 'Local account id retained only until provider revocation succeeds',
    apple_subject_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'One-way Apple subject fingerprint',
    authorization_code_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'Single-use authorization-code replay fingerprint',
    refresh_token_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'One-way refresh-token deduplication fingerprint',
    client_id VARCHAR(255) NOT NULL COMMENT 'Apple client id that issued this token',
    encryption_key_id VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'Environment-owned envelope key id',
    initialization_vector VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'Base64 AES-GCM initialization vector',
    encrypted_refresh_token VARCHAR(16384) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'Base64 AES-256-GCM ciphertext; never plaintext',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        COMMENT 'ACTIVE, PENDING, PROCESSING, BLOCKED, or REVOKED',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT 'Physical Apple revoke attempts',
    next_attempt_at DATETIME(6) NULL COMMENT 'Next revocation eligibility time',
    locked_at DATETIME(6) NULL COMMENT 'Current revocation lease time',
    locked_by VARCHAR(80) NULL COMMENT 'Current revocation worker id',
    last_failure_code VARCHAR(120) NULL COMMENT 'Sanitized provider/local failure code',
    revoked_at DATETIME(6) NULL COMMENT 'Provider-confirmed token deletion time',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_apple_provider_credentials_credential_key (credential_key),
    UNIQUE KEY uk_apple_provider_credentials_authorization_code_hash (authorization_code_hash),
    UNIQUE KEY uk_apple_provider_credentials_refresh_token_hash (refresh_token_hash),
    INDEX idx_apple_provider_credentials_member_status (member_id, status, id),
    INDEX idx_apple_provider_credentials_due (status, next_attempt_at, id),
    INDEX idx_apple_provider_credentials_stale (status, locked_at, id)
) COMMENT='Encrypted Sign in with Apple credentials and durable revoke leases';

DROP PROCEDURE IF EXISTS assert_apple_token_lifecycle_postconditions;
DELIMITER //
CREATE PROCEDURE assert_apple_token_lifecycle_postconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'apple_provider_credentials'
    ) <> 24 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'apple token lifecycle migration failed: credential table shape is unexpected';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'apple_provider_credentials'
          AND column_name IN (
              'credential_key',
              'member_id',
              'apple_subject_hash',
              'authorization_code_hash',
              'refresh_token_hash',
              'client_id',
              'encryption_key_id',
              'initialization_vector',
              'encrypted_refresh_token',
              'status',
              'attempt_count',
              'next_attempt_at',
              'locked_at',
              'locked_by',
              'last_failure_code',
              'revoked_at',
              'version'
          )
    ) <> 17 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'apple token lifecycle migration failed: required credential columns are incomplete';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM (
            SELECT
                index_name,
                MAX(non_unique) AS non_unique,
                GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS indexed_columns
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'apple_provider_credentials'
            GROUP BY index_name
        ) AS actual_indexes
        WHERE (
            index_name = 'uk_apple_provider_credentials_credential_key'
            AND non_unique = 0
            AND indexed_columns = 'credential_key'
        ) OR (
            index_name = 'uk_apple_provider_credentials_authorization_code_hash'
            AND non_unique = 0
            AND indexed_columns = 'authorization_code_hash'
        ) OR (
            index_name = 'uk_apple_provider_credentials_refresh_token_hash'
            AND non_unique = 0
            AND indexed_columns = 'refresh_token_hash'
        ) OR (
            index_name = 'idx_apple_provider_credentials_member_status'
            AND non_unique = 1
            AND indexed_columns = 'member_id,status,id'
        ) OR (
            index_name = 'idx_apple_provider_credentials_due'
            AND non_unique = 1
            AND indexed_columns = 'status,next_attempt_at,id'
        ) OR (
            index_name = 'idx_apple_provider_credentials_stale'
            AND non_unique = 1
            AND indexed_columns = 'status,locked_at,id'
        )
    ) <> 6 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'apple token lifecycle migration failed: required credential indexes are incomplete';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.key_column_usage
        WHERE table_schema = DATABASE()
          AND table_name = 'apple_provider_credentials'
          AND referenced_table_name IS NOT NULL
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'apple token lifecycle migration failed: credential retries must not have foreign keys';
    END IF;
END//
DELIMITER ;

CALL assert_apple_token_lifecycle_postconditions();
DROP PROCEDURE assert_apple_token_lifecycle_postconditions;

INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES (
    '2026-07-26-apple-token-lifecycle-v1',
    'Encrypted Apple refresh credentials and durable idempotent account-deletion revocation',
    CURRENT_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE
    description = VALUES(description),
    applied_at = applied_at;

SELECT COUNT(*) AS apple_token_lifecycle_column_count
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'apple_provider_credentials';

SELECT COUNT(*) AS missing_required_schema_marker
FROM application_schema_migrations
WHERE version = '2026-07-26-apple-token-lifecycle-v1'
HAVING COUNT(*) <> 1;
