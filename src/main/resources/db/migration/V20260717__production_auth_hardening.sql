ALTER TABLE member
    ADD COLUMN tokens_valid_after DATETIME(6) NULL;

ALTER TABLE refresh_token
    MODIFY COLUMN token VARCHAR(1024) NOT NULL;

-- COMMON 계정은 SNS subject가 없으므로 과거 기본값 ''를 NULL로 정규화한다.
UPDATE member SET sns_id = NULL WHERE login_type = 'COMMON';

CREATE UNIQUE INDEX uk_refresh_token_token ON refresh_token (token);
CREATE UNIQUE INDEX uk_refresh_token_member ON refresh_token (member_id);

-- 이 인덱스 생성이 중복 데이터 때문에 실패하면 배포를 중단하고 중복 계정을 병합해야 한다.
-- 중복을 임의 삭제해 통과시키지 않는 것이 계정 탈취/오연결보다 안전하다.
CREATE UNIQUE INDEX uk_member_email ON member (email);
CREATE UNIQUE INDEX uk_member_login_type_sns_id ON member (login_type, sns_id);
