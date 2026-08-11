-- Spring Security 세션 인증용 로컬 테스트 계정
-- email: test@test.com
-- password: test1234!

INSERT INTO users (
    email,
    password,
    nickname,
    provider,
    point,
    role,
    status,
    created_at,
    updated_at
)
SELECT
    'test@test.com',
    '$2a$10$V5LAiJSvsgl2clD6gkPKW.pRftvAll2BvW1BhzOmVP6CzSBXPO5s2',
    '테스트유저',
    'LOCAL',
    1000,
    'USER',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1
    FROM users
    WHERE email = 'test@test.com'
);

UPDATE users
SET
    password = '$2a$10$V5LAiJSvsgl2clD6gkPKW.pRftvAll2BvW1BhzOmVP6CzSBXPO5s2',
    provider = 'LOCAL',
    role = 'USER',
    status = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE email = 'test@test.com';
