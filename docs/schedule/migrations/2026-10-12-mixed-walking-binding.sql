-- MySQL 8, manual maintenance window only, AFTER walking snapshots. No backfill or runtime enablement.
DROP PROCEDURE IF EXISTS assert_mixed_walking_binding;
DELIMITER //
CREATE PROCEDURE assert_mixed_walking_binding()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM application_schema_migrations
        WHERE version='2026-10-12-journey-walking-snapshots-v1')
        OR (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
        AND table_name='automatic_journey_preparations') <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Mixed walking requires reviewed snapshot and automatic schemas';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='automatic_journey_preparations' AND column_name='walking_snapshot_id')
        OR EXISTS (SELECT 1 FROM application_schema_migrations
        WHERE version='2026-10-12-mixed-walking-binding-v1') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Mixed walking binding exists or is partial; inspect before retry';
    END IF;
END//
DELIMITER ;
CALL assert_mixed_walking_binding();
DROP PROCEDURE assert_mixed_walking_binding;
ALTER TABLE automatic_journey_preparations
    ADD COLUMN walking_snapshot_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
DROP PROCEDURE IF EXISTS assert_mixed_walking_postconditions;
DELIMITER //
CREATE PROCEDURE assert_mixed_walking_postconditions()
BEGIN
    IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='automatic_journey_preparations' AND column_name='walking_snapshot_id'
        AND data_type='varchar' AND character_maximum_length=36 AND is_nullable='YES'
        AND collation_name='ascii_bin') <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Mixed walking binding verification failed; keep API stopped';
    END IF;
END//
DELIMITER ;
CALL assert_mixed_walking_postconditions();
DROP PROCEDURE assert_mixed_walking_postconditions;
INSERT INTO application_schema_migrations(version,description,applied_at)
VALUES('2026-10-12-mixed-walking-binding-v1','Bind automatic sessions to separately owned walking snapshot',NOW(6));
