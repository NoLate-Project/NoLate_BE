-- Versioned client push-delivery ACK capability and rolling-cohort index (MySQL 8.x).
-- Stop all application instances before running. MySQL DDL commits implicitly.

DROP PROCEDURE IF EXISTS assert_push_ack_capability_preconditions;
DELIMITER //
CREATE PROCEDURE assert_push_ack_capability_preconditions()
BEGIN
    IF (
        SELECT COUNT(*) FROM application_schema_migrations
        WHERE version = '2026-08-01-departure-alarm-schedule-receipts-v1'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'push ACK capability migration blocked: predecessor marker is absent';
    END IF;

    IF EXISTS (
        SELECT 1 FROM application_schema_migrations
        WHERE version = '2026-08-01-push-delivery-ack-capability-v1'
    ) OR EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name IN ('push_device_token', 'push_deliveries')
          AND column_name = 'delivery_ack_capability_version'
    ) OR EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'push_deliveries'
          AND index_name = 'idx_push_deliveries_reliability_cohort'
    ) OR EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND constraint_name IN (
              'chk_push_device_token_ack_capability',
              'chk_push_deliveries_ack_capability'
          )
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'push ACK capability migration blocked: partial or applied schema requires inspection';
    END IF;
END//
DELIMITER ;

CALL assert_push_ack_capability_preconditions();
DROP PROCEDURE assert_push_ack_capability_preconditions;

ALTER TABLE push_device_token
    ADD COLUMN delivery_ack_capability_version INT NULL
        COMMENT 'Null for legacy clients; 1 promises authenticated per-delivery ACK upload'
        AFTER retirement_requested,
    ADD CONSTRAINT chk_push_device_token_ack_capability
        CHECK (delivery_ack_capability_version IS NULL OR delivery_ack_capability_version = 1);

ALTER TABLE push_deliveries
    ADD COLUMN delivery_ack_capability_version INT NULL
        COMMENT 'Frozen ACK protocol version from the token manifest'
        AFTER payload_type,
    ADD INDEX idx_push_deliveries_reliability_cohort
        (status, delivered_at, delivery_ack_capability_version, client_received_at),
    ADD CONSTRAINT chk_push_deliveries_ack_capability
        CHECK (delivery_ack_capability_version IS NULL OR delivery_ack_capability_version = 1);

DROP PROCEDURE IF EXISTS assert_push_ack_capability_postconditions;
DELIMITER //
CREATE PROCEDURE assert_push_ack_capability_postconditions()
BEGIN
    IF (
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name IN ('push_device_token', 'push_deliveries')
          AND column_name = 'delivery_ack_capability_version'
          AND data_type = 'int'
          AND is_nullable = 'YES'
    ) <> 2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'push ACK capability verification failed: columns are absent or incompatible';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM (
            SELECT index_name
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'push_deliveries'
              AND index_name = 'idx_push_deliveries_reliability_cohort'
            GROUP BY index_name
            HAVING MIN(non_unique) = 1
               AND GROUP_CONCAT(column_name ORDER BY seq_in_index) =
                   'status,delivered_at,delivery_ack_capability_version,client_received_at'
        ) verified_index
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'push ACK capability verification failed: cohort index is absent';
    END IF;

    IF (
        SELECT COUNT(*) FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND constraint_type = 'CHECK'
          AND constraint_name IN (
              'chk_push_device_token_ack_capability',
              'chk_push_deliveries_ack_capability'
          )
    ) <> 2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'push ACK capability verification failed: constraints are absent';
    END IF;

    IF EXISTS (
        SELECT 1 FROM push_device_token
        WHERE delivery_ack_capability_version IS NOT NULL
          AND delivery_ack_capability_version <> 1
    ) OR EXISTS (
        SELECT 1 FROM push_deliveries
        WHERE delivery_ack_capability_version IS NOT NULL
          AND delivery_ack_capability_version <> 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'push ACK capability verification failed: unsupported capability value';
    END IF;
END//
DELIMITER ;

CALL assert_push_ack_capability_postconditions();
DROP PROCEDURE assert_push_ack_capability_postconditions;

INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES (
    '2026-08-01-push-delivery-ack-capability-v1',
    'Versioned client ACK capability and indexed delivery reliability cohort',
    CURRENT_TIMESTAMP(6)
);

SELECT COUNT(*) AS push_ack_capability_marker_count
FROM application_schema_migrations
WHERE version = '2026-08-01-push-delivery-ack-capability-v1';
