-- This migration was first deployed against an existing production schema.
-- MySQL DDL is not transactional, so every step must tolerate a retry after a
-- partially applied run.  The dynamic statements intentionally avoid MySQL
-- variants whose IF NOT EXISTS support differs by server version.
SET @sql = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'member'
              AND column_name = 'tokens_valid_after'
        ),
        'SELECT 1',
        'ALTER TABLE member ADD COLUMN tokens_valid_after DATETIME(6) NULL'
    )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE refresh_token
    -- JWT refresh tokens are Base64URL/ASCII and case-sensitive.  Keeping this
    -- as utf8mb4 would require up to 4096 bytes for a 1024-character unique
    -- index, exceeding InnoDB's normal 3072-byte key limit.
    MODIFY COLUMN token VARCHAR(1024) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;

-- COMMON 계정은 SNS subject가 없으므로 과거 기본값 ''를 NULL로 정규화한다.
UPDATE member SET sns_id = NULL WHERE login_type = 'COMMON';

SET @sql = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'refresh_token'
              AND index_name = 'uk_refresh_token_token'
        ),
        'SELECT 1',
        'CREATE UNIQUE INDEX uk_refresh_token_token ON refresh_token (token)'
    )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'refresh_token'
              AND index_name = 'uk_refresh_token_member'
        ),
        'SELECT 1',
        'CREATE UNIQUE INDEX uk_refresh_token_member ON refresh_token (member_id)'
    )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 이 인덱스 생성이 중복 데이터 때문에 실패하면 배포를 중단하고 중복 계정을 병합해야 한다.
-- 중복을 임의 삭제해 통과시키지 않는 것이 계정 탈취/오연결보다 안전하다.
SET @sql = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'member'
              AND index_name = 'uk_member_email'
        ),
        'SELECT 1',
        'CREATE UNIQUE INDEX uk_member_email ON member (email)'
    )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'member'
              AND index_name = 'uk_member_login_type_sns_id'
        ),
        'SELECT 1',
        'CREATE UNIQUE INDEX uk_member_login_type_sns_id ON member (login_type, sns_id)'
    )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
