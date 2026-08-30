CREATE TABLE game_settlements (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    revision INT NOT NULL,
    state VARCHAR(20) NOT NULL,
    source VARCHAR(20) NOT NULL,
    game_status VARCHAR(50) NOT NULL,
    game_result VARCHAR(20) NULL,
    home_score INT NULL,
    away_score INT NULL,
    prediction_count INT NOT NULL DEFAULT 0,
    correct_count INT NOT NULL DEFAULT 0,
    incorrect_count INT NOT NULL DEFAULT 0,
    refunded_count INT NOT NULL DEFAULT 0,
    total_paid_points BIGINT NOT NULL DEFAULT 0,
    settled_by_user_id BIGINT NULL,
    settled_at DATETIME(6) NOT NULL,
    rolled_back_by_user_id BIGINT NULL,
    rolled_back_at DATETIME(6) NULL,
    rollback_reason VARCHAR(255) NULL,
    reversed_point_total BIGINT NOT NULL DEFAULT 0,
    result_corrected_by_user_id BIGINT NULL,
    result_corrected_at DATETIME(6) NULL,
    result_correction_reason VARCHAR(255) NULL,
    corrected_game_status VARCHAR(50) NULL,
    corrected_game_result VARCHAR(20) NULL,
    corrected_home_score INT NULL,
    corrected_away_score INT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_game_settlements_game_revision
        UNIQUE (game_id, revision),
    CONSTRAINT fk_game_settlements_game
        FOREIGN KEY (game_id) REFERENCES games (id)
) ENGINE = InnoDB;

CREATE INDEX idx_game_settlements_game_state
    ON game_settlements (game_id, state, revision DESC);

ALTER TABLE user_predictions
    ADD COLUMN settlement_id BIGINT NULL AFTER settled_at,
    ADD CONSTRAINT fk_user_predictions_settlement
        FOREIGN KEY (settlement_id) REFERENCES game_settlements (id);

ALTER TABLE point_histories
    DROP INDEX uk_point_histories_prediction_type,
    ADD COLUMN settlement_id BIGINT NULL AFTER user_prediction_id,
    ADD COLUMN reversal_of_id BIGINT NULL AFTER settlement_id,
    ADD COLUMN settlement_revision INT NOT NULL DEFAULT 0 AFTER reversal_of_id,
    ADD CONSTRAINT fk_point_histories_settlement
        FOREIGN KEY (settlement_id) REFERENCES game_settlements (id),
    ADD CONSTRAINT fk_point_histories_reversal_of
        FOREIGN KEY (reversal_of_id) REFERENCES point_histories (id),
    ADD CONSTRAINT uk_point_histories_prediction_type_revision
        UNIQUE (user_prediction_id, type, settlement_revision),
    ADD CONSTRAINT uk_point_histories_reversal_of
        UNIQUE (reversal_of_id);

INSERT INTO game_settlements (
    game_id,
    revision,
    state,
    source,
    game_status,
    game_result,
    home_score,
    away_score,
    prediction_count,
    correct_count,
    incorrect_count,
    refunded_count,
    total_paid_points,
    settled_at,
    created_at,
    updated_at
)
SELECT
    game.id,
    1,
    'SETTLED',
    'LEGACY',
    game.status,
    game.result,
    game.home_score,
    game.away_score,
    COUNT(prediction.id),
    SUM(CASE WHEN prediction.settlement_status = 'WON' THEN 1 ELSE 0 END),
    SUM(CASE WHEN prediction.settlement_status = 'LOST' THEN 1 ELSE 0 END),
    SUM(CASE WHEN prediction.settlement_status = 'REFUNDED' THEN 1 ELSE 0 END),
    COALESCE((
        SELECT SUM(history.point_change)
        FROM point_histories history
        JOIN user_predictions paid_prediction
          ON paid_prediction.id = history.user_prediction_id
        WHERE paid_prediction.game_id = game.id
          AND history.type IN ('PREDICTION_REWARD', 'GAME_CANCEL_REFUND')
    ), 0),
    COALESCE(MAX(prediction.settled_at), CURRENT_TIMESTAMP(6)),
    COALESCE(MAX(prediction.settled_at), CURRENT_TIMESTAMP(6)),
    CURRENT_TIMESTAMP(6)
FROM games game
JOIN user_predictions prediction
  ON prediction.game_id = game.id
 AND prediction.settled = 1
GROUP BY
    game.id,
    game.status,
    game.result,
    game.home_score,
    game.away_score;

UPDATE user_predictions prediction
JOIN game_settlements settlement
  ON settlement.game_id = prediction.game_id
 AND settlement.revision = 1
SET prediction.settlement_id = settlement.id,
    prediction.settled_at = COALESCE(
        prediction.settled_at,
        settlement.settled_at
    )
WHERE prediction.settled = 1;

UPDATE point_histories history
JOIN user_predictions prediction
  ON prediction.id = history.user_prediction_id
SET history.settlement_id = prediction.settlement_id,
    history.settlement_revision = 1
WHERE history.type IN ('PREDICTION_REWARD', 'GAME_CANCEL_REFUND')
  AND prediction.settlement_id IS NOT NULL;
