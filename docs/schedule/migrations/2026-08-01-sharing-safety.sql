-- Sharing report and member-block safety boundary.
-- Apply with every API and worker stopped. The production guard requires the marker below.

DROP PROCEDURE IF EXISTS assert_sharing_safety_preconditions;
DELIMITER //
CREATE PROCEDURE assert_sharing_safety_preconditions()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'application_schema_migrations'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'sharing safety migration blocked: migration marker table is absent';
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name IN (
              'sharing_member_blocks',
              'sharing_reports',
              'schedule_share_invitation_acceptances'
          )
    ) OR EXISTS (
        SELECT 1 FROM application_schema_migrations
        WHERE version = '2026-08-01-sharing-safety-v1'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'sharing safety migration blocked: partial or existing schema requires inspection';
    END IF;
END//
DELIMITER ;

CALL assert_sharing_safety_preconditions();
DROP PROCEDURE assert_sharing_safety_preconditions;

CREATE TABLE sharing_member_blocks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    blocker_member_id BIGINT NOT NULL,
    blocked_member_id BIGINT NOT NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sharing_member_blocks_pair (blocker_member_id, blocked_member_id),
    INDEX idx_sharing_member_blocks_blocker (blocker_member_id, deleted),
    INDEX idx_sharing_member_blocks_blocked (blocked_member_id, deleted),
    CONSTRAINT chk_sharing_member_blocks_not_self CHECK (blocker_member_id <> blocked_member_id)
) COMMENT='Recoverable member blocks enforced across schedule sharing';

CREATE TABLE sharing_reports (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reporter_member_id BIGINT NOT NULL,
    reported_member_id BIGINT NOT NULL,
    resource_type VARCHAR(30) NOT NULL,
    resource_id BIGINT NOT NULL,
    reason VARCHAR(40) NOT NULL,
    details VARCHAR(500) NULL,
    status VARCHAR(30) NOT NULL,
    moderator_member_id BIGINT NULL,
    resolution_note VARCHAR(500) NULL,
    resolved_at DATETIME(6) NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_sharing_reports_reporter_created (reporter_member_id, created_at),
    INDEX idx_sharing_reports_status_created (status, created_at),
    INDEX idx_sharing_reports_resource (resource_type, resource_id, reported_member_id),
    CONSTRAINT chk_sharing_reports_not_self CHECK (reporter_member_id <> reported_member_id)
) COMMENT='User reports for shared schedule content and senders';

CREATE TABLE schedule_share_invitation_acceptances (
    id BIGINT NOT NULL AUTO_INCREMENT,
    invitation_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_share_invitation_acceptance_member (invitation_id, member_id),
    INDEX idx_share_invitation_acceptance_member (member_id, accepted_at)
) COMMENT='Durable per-member idempotency ledger for invitation acceptance';

DROP PROCEDURE IF EXISTS assert_sharing_safety_postconditions;
DELIMITER //
CREATE PROCEDURE assert_sharing_safety_postconditions()
BEGIN
    IF (
        SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name IN (
              'sharing_member_blocks',
              'sharing_reports',
              'schedule_share_invitation_acceptances'
          )
    ) <> 3 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'sharing safety migration verification failed: tables are absent';
    END IF;
    IF (
        SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'sharing_member_blocks'
          AND index_name = 'uk_sharing_member_blocks_pair'
          AND non_unique = 0
    ) <> 2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'sharing safety migration verification failed: block unique key is absent';
    END IF;
    IF (
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'sharing_reports'
          AND column_name IN ('moderator_member_id', 'resolution_note', 'resolved_at')
    ) <> 3 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'sharing safety migration verification failed: moderation columns are absent';
    END IF;
    IF (
        SELECT COUNT(*) FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_share_invitation_acceptances'
          AND index_name = 'uk_share_invitation_acceptance_member'
          AND non_unique = 0
    ) <> 2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'sharing safety migration verification failed: acceptance unique key is absent';
    END IF;
END//
DELIMITER ;

CALL assert_sharing_safety_postconditions();
DROP PROCEDURE assert_sharing_safety_postconditions;

INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES (
    '2026-08-01-sharing-safety-v1',
    'Sharing blocks, moderation lifecycle, and invitation acceptance idempotency',
    CURRENT_TIMESTAMP(6)
);

SELECT COUNT(*) AS sharing_safety_marker_count
FROM application_schema_migrations
WHERE version = '2026-08-01-sharing-safety-v1';

SELECT COUNT(*) AS sharing_safety_table_count
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN (
      'sharing_member_blocks',
      'sharing_reports',
      'schedule_share_invitation_acceptances'
  );
