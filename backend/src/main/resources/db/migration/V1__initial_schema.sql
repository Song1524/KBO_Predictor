-- Initial schema that existed before the manually applied V2 migration.
-- Existing databases through V9 must be baselined at version 9 with Flyway's
-- official baseline command. New empty databases execute V1 through V9.

CREATE TABLE teams (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NULL,
    short_name VARCHAR(50) NULL,
    primary_color VARCHAR(50) NULL,
    secondary_color VARCHAR(50) NULL,
    created_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_teams_name UNIQUE (name),
    CONSTRAINT uk_teams_short_name UNIQUE (short_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NULL,
    password VARCHAR(255) NULL,
    nickname VARCHAR(100) NULL,
    provider VARCHAR(50) NULL,
    favorite_team_id BIGINT NULL,
    point INT NULL,
    role VARCHAR(50) NULL,
    status VARCHAR(50) NULL,
    created_at DATETIME NULL,
    updated_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_nickname UNIQUE (nickname)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE games (
    id BIGINT NOT NULL AUTO_INCREMENT,
    season INT NULL,
    game_date DATE NULL,
    game_time TIME NULL,
    home_team_id BIGINT NULL,
    away_team_id BIGINT NULL,
    stadium VARCHAR(100) NULL,
    status VARCHAR(50) NULL,
    home_score INT NULL,
    away_score INT NULL,
    winner_team_id BIGINT NULL,
    prediction_close_at DATETIME NULL,
    cancel_reason VARCHAR(255) NULL,
    created_at DATETIME NULL,
    updated_at DATETIME NULL,
    PRIMARY KEY (id),
    INDEX idx_games_game_date (game_date),
    INDEX idx_games_status (status),
    INDEX idx_games_date_status (game_date, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE user_predictions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NULL,
    game_id BIGINT NULL,
    selected_team_id BIGINT NULL,
    point_amount INT NULL,
    is_correct TINYINT(1) NULL,
    settled TINYINT(1) NULL,
    created_at DATETIME NULL,
    updated_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_predictions_user_game UNIQUE (user_id, game_id),
    INDEX idx_user_predictions_game (game_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE system_predictions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NULL,
    predicted_winner_team_id BIGINT NULL,
    home_win_probability DECIMAL(5,2) NULL,
    away_win_probability DECIMAL(5,2) NULL,
    home_score_point DECIMAL(10,2) NULL,
    away_score_point DECIMAL(10,2) NULL,
    reason TEXT NULL,
    created_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_system_predictions_game UNIQUE (game_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE team_stats (
    id BIGINT NOT NULL AUTO_INCREMENT,
    team_id BIGINT NULL,
    season INT NULL,
    stat_date DATE NULL,
    win INT NULL,
    lose INT NULL,
    draw INT NULL,
    win_rate DECIMAL(5,3) NULL,
    recent_10_win INT NULL,
    recent_10_lose INT NULL,
    home_win INT NULL,
    home_lose INT NULL,
    away_win INT NULL,
    away_lose INT NULL,
    batting_avg DECIMAL(5,3) NULL,
    era DECIMAL(5,2) NULL,
    collected_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_team_stats_team_date
        UNIQUE (team_id, season, stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
