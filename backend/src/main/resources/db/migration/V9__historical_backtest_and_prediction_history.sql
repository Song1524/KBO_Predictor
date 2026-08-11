CREATE TABLE prediction_feature_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    feature_as_of DATETIME NOT NULL,
    generation_method VARCHAR(40) NOT NULL,
    data_source VARCHAR(500) NOT NULL,
    missing_features TEXT NULL,
    home_historical_game_count INT NOT NULL DEFAULT 0,
    away_historical_game_count INT NOT NULL DEFAULT 0,
    home_season_win INT NOT NULL DEFAULT 0,
    home_season_lose INT NOT NULL DEFAULT 0,
    home_season_draw INT NOT NULL DEFAULT 0,
    away_season_win INT NOT NULL DEFAULT 0,
    away_season_lose INT NOT NULL DEFAULT 0,
    away_season_draw INT NOT NULL DEFAULT 0,
    home_season_win_rate DECIMAL(5,3) NULL,
    away_season_win_rate DECIMAL(5,3) NULL,
    home_recent_5_win_rate DECIMAL(5,3) NULL,
    away_recent_5_win_rate DECIMAL(5,3) NULL,
    home_recent_10_win_rate DECIMAL(5,3) NULL,
    away_recent_10_win_rate DECIMAL(5,3) NULL,
    home_recent_5_avg_runs DECIMAL(6,2) NULL,
    away_recent_5_avg_runs DECIMAL(6,2) NULL,
    home_recent_5_avg_runs_allowed DECIMAL(6,2) NULL,
    away_recent_5_avg_runs_allowed DECIMAL(6,2) NULL,
    home_recent_10_avg_runs DECIMAL(6,2) NULL,
    away_recent_10_avg_runs DECIMAL(6,2) NULL,
    home_recent_10_avg_runs_allowed DECIMAL(6,2) NULL,
    away_recent_10_avg_runs_allowed DECIMAL(6,2) NULL,
    home_batting_average DECIMAL(5,3) NULL,
    away_batting_average DECIMAL(5,3) NULL,
    home_era DECIMAL(5,2) NULL,
    away_era DECIMAL(5,2) NULL,
    home_venue_win_rate DECIMAL(5,3) NULL,
    away_venue_win_rate DECIMAL(5,3) NULL,
    home_starting_pitcher_name VARCHAR(100) NULL,
    away_starting_pitcher_name VARCHAR(100) NULL,
    home_starting_pitcher_stat_date DATE NULL,
    away_starting_pitcher_stat_date DATE NULL,
    home_starting_pitcher_era DECIMAL(5,2) NULL,
    away_starting_pitcher_era DECIMAL(5,2) NULL,
    home_starting_pitcher_whip DECIMAL(5,2) NULL,
    away_starting_pitcher_whip DECIMAL(5,2) NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_prediction_feature_snapshots_game
        FOREIGN KEY (game_id) REFERENCES games (id),
    CONSTRAINT uk_prediction_feature_snapshot_point
        UNIQUE (game_id, feature_as_of, generation_method),
    INDEX idx_prediction_feature_snapshots_as_of (feature_as_of)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE system_prediction_histories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    feature_snapshot_id BIGINT NULL,
    home_win_probability DECIMAL(5,2) NOT NULL,
    draw_probability DECIMAL(5,2) NOT NULL,
    away_win_probability DECIMAL(5,2) NOT NULL,
    predicted_outcome VARCHAR(20) NOT NULL,
    model_version VARCHAR(50) NOT NULL,
    feature_coverage DECIMAL(5,3) NOT NULL,
    reason TEXT NULL,
    prediction_stage VARCHAR(30) NOT NULL,
    prediction_source VARCHAR(30) NOT NULL,
    generated_at DATETIME NOT NULL,
    recorded_at DATETIME NOT NULL,
    deduplication_key VARCHAR(191) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_system_prediction_histories_game
        FOREIGN KEY (game_id) REFERENCES games (id),
    CONSTRAINT fk_system_prediction_histories_snapshot
        FOREIGN KEY (feature_snapshot_id)
        REFERENCES prediction_feature_snapshots (id),
    CONSTRAINT uk_system_prediction_histories_dedup
        UNIQUE (deduplication_key),
    INDEX idx_prediction_history_evaluation
        (model_version, prediction_source, prediction_stage, game_id),
    INDEX idx_prediction_history_generated_at (generated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO system_prediction_histories (
    game_id,
    feature_snapshot_id,
    home_win_probability,
    draw_probability,
    away_win_probability,
    predicted_outcome,
    model_version,
    feature_coverage,
    reason,
    prediction_stage,
    prediction_source,
    generated_at,
    recorded_at,
    deduplication_key
)
SELECT
    prediction.game_id,
    NULL,
    prediction.home_win_probability,
    prediction.draw_probability,
    prediction.away_win_probability,
    prediction.predicted_outcome,
    prediction.model_version,
    COALESCE(prediction.feature_coverage, 0),
    prediction.reason,
    CASE
        WHEN game.prediction_close_at IS NOT NULL
             AND game.prediction_close_at <= CURRENT_TIMESTAMP
            THEN 'FINAL'
        ELSE 'INITIAL'
    END,
    'OPERATIONAL',
    prediction.generated_at,
    CURRENT_TIMESTAMP,
    CONCAT(
        'MIGRATED:', prediction.game_id, ':', prediction.model_version, ':',
        CASE
            WHEN game.prediction_close_at IS NOT NULL
                 AND game.prediction_close_at <= CURRENT_TIMESTAMP
                THEN 'FINAL'
            ELSE 'INITIAL'
        END
    )
FROM system_predictions prediction
JOIN games game ON game.id = prediction.game_id;
