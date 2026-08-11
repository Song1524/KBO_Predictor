-- 설명 가능한 Baseline Prediction Engine v1 메타데이터

ALTER TABLE system_predictions
    ADD COLUMN predicted_outcome VARCHAR(20) NULL AFTER predicted_winner_team_id,
    ADD COLUMN model_version VARCHAR(50) NULL AFTER away_win_probability,
    ADD COLUMN feature_coverage DECIMAL(5,3) NULL AFTER model_version,
    ADD COLUMN home_stat_date DATE NULL AFTER feature_coverage,
    ADD COLUMN away_stat_date DATE NULL AFTER home_stat_date,
    ADD COLUMN home_pitcher_stat_date DATE NULL AFTER away_stat_date,
    ADD COLUMN away_pitcher_stat_date DATE NULL AFTER home_pitcher_stat_date,
    ADD COLUMN generated_at DATETIME NULL AFTER reason;

UPDATE system_predictions
SET
    predicted_outcome = CASE
        WHEN COALESCE(draw_probability, -1) >= COALESCE(home_win_probability, -1)
             AND COALESCE(draw_probability, -1) >= COALESCE(away_win_probability, -1)
            THEN 'DRAW'
        WHEN COALESCE(home_win_probability, -1) >= COALESCE(away_win_probability, -1)
            THEN 'HOME_WIN'
        ELSE 'AWAY_WIN'
    END,
    model_version = 'legacy',
    generated_at = COALESCE(created_at, CURRENT_TIMESTAMP)
WHERE predicted_outcome IS NULL;

ALTER TABLE system_predictions
    MODIFY COLUMN predicted_outcome VARCHAR(20) NOT NULL,
    MODIFY COLUMN model_version VARCHAR(50) NOT NULL,
    MODIFY COLUMN generated_at DATETIME NOT NULL;

CREATE INDEX idx_system_predictions_model_version
    ON system_predictions (model_version);
