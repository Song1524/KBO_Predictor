-- 공식 KBO 일정/결과 수집을 위한 안정적인 외부 식별자와 10개 구단 기준정보
-- 현재 프로젝트는 Flyway 런타임이 연결되어 있지 않으므로 기존 V2~V5와 동일하게 수동 실행한다.

ALTER TABLE teams
    ADD COLUMN kbo_team_code VARCHAR(10) NULL AFTER id;

INSERT INTO teams (
    kbo_team_code,
    name,
    short_name,
    primary_color,
    secondary_color,
    created_at
)
VALUES
    ('LG', 'LG 트윈스', 'LG', '#C30452', '#000000', CURRENT_TIMESTAMP),
    ('HH', '한화 이글스', '한화', '#FC4E00', '#000000', CURRENT_TIMESTAMP),
    ('SK', 'SSG 랜더스', 'SSG', '#CE0E2D', '#FFB81C', CURRENT_TIMESTAMP),
    ('SS', '삼성 라이온즈', '삼성', '#074CA1', '#FFFFFF', CURRENT_TIMESTAMP),
    ('NC', 'NC 다이노스', 'NC', '#315288', '#C8A45D', CURRENT_TIMESTAMP),
    ('KT', 'KT 위즈', 'KT', '#000000', '#EF1B23', CURRENT_TIMESTAMP),
    ('LT', '롯데 자이언츠', '롯데', '#041E42', '#D00F31', CURRENT_TIMESTAMP),
    ('HT', 'KIA 타이거즈', 'KIA', '#EA0029', '#06141F', CURRENT_TIMESTAMP),
    ('OB', '두산 베어스', '두산', '#131230', '#ED1C24', CURRENT_TIMESTAMP),
    ('WO', '키움 히어로즈', '키움', '#570514', '#B07F4A', CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE
    kbo_team_code = VALUES(kbo_team_code),
    short_name = VALUES(short_name),
    primary_color = VALUES(primary_color),
    secondary_color = VALUES(secondary_color);

ALTER TABLE teams
    ADD CONSTRAINT uk_teams_kbo_team_code UNIQUE (kbo_team_code);

ALTER TABLE games
    ADD COLUMN external_game_id VARCHAR(30) NULL AFTER id;

ALTER TABLE games
    ADD CONSTRAINT uk_games_external_game_id UNIQUE (external_game_id);

CREATE INDEX idx_games_natural_key
    ON games (game_date, game_time, home_team_id, away_team_id);
