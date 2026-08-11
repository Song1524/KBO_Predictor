-- 경기 전 예측 입력 데이터 스냅샷 및 선발투수 데이터

ALTER TABLE team_stats
    ADD COLUMN recent_10_draw INT NULL AFTER recent_10_lose,
    ADD COLUMN home_draw INT NULL AFTER home_lose,
    ADD COLUMN away_draw INT NULL AFTER away_lose,
    ADD COLUMN recent_5_win_rate DECIMAL(5,3) NULL AFTER away_draw,
    ADD COLUMN recent_10_win_rate DECIMAL(5,3) NULL AFTER recent_5_win_rate,
    ADD COLUMN recent_5_avg_runs DECIMAL(6,2) NULL AFTER recent_10_win_rate,
    ADD COLUMN recent_5_avg_runs_allowed DECIMAL(6,2) NULL AFTER recent_5_avg_runs,
    ADD COLUMN recent_10_avg_runs DECIMAL(6,2) NULL AFTER recent_5_avg_runs_allowed,
    ADD COLUMN recent_10_avg_runs_allowed DECIMAL(6,2) NULL AFTER recent_10_avg_runs;

-- 기존 uk_team_stats_team_date(team_id, season, stat_date)를 그대로 사용한다.

CREATE TABLE players (
    id BIGINT NOT NULL AUTO_INCREMENT,
    kbo_player_id VARCHAR(20) NOT NULL,
    team_id BIGINT NULL,
    name VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_players_kbo_player_id UNIQUE (kbo_player_id),
    INDEX idx_players_team_id (team_id)
);

CREATE TABLE pitcher_stats (
    id BIGINT NOT NULL AUTO_INCREMENT,
    player_id BIGINT NOT NULL,
    season INT NOT NULL,
    stat_date DATE NOT NULL,
    era DECIMAL(5,2) NULL,
    win INT NULL,
    lose INT NULL,
    innings VARCHAR(20) NULL,
    whip DECIMAL(5,2) NULL,
    collected_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_pitcher_stats_player_date
        UNIQUE (player_id, season, stat_date),
    INDEX idx_pitcher_stats_lookup (player_id, stat_date)
);

CREATE TABLE starting_pitchers (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    player_id BIGINT NOT NULL,
    side VARCHAR(10) NOT NULL,
    first_collected_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_starting_pitchers_game_side UNIQUE (game_id, side),
    INDEX idx_starting_pitchers_player_id (player_id),
    INDEX idx_starting_pitchers_team_id (team_id)
);
