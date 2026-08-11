-- KBO Predictor: 승/무/패 결과 및 경기별 실시간/최종 배당 도입
-- 대상: 기존 kbo_predictor 스키마
-- 주의: selected_team_id를 제거하므로 실행 전 DB 백업을 권장한다.
-- 이 파일은 현재 프로젝트에 Flyway가 연결되어 있지 않아 수동으로 한 번 실행한다.

-- 1. 기존 팀 선택을 홈 승/원정 승 결과로 보존한다.
ALTER TABLE user_predictions
    ADD COLUMN selected_outcome VARCHAR(20) NULL AFTER game_id;

UPDATE user_predictions up
JOIN games g ON g.id = up.game_id
SET up.selected_outcome = CASE
    WHEN up.selected_team_id = g.home_team_id THEN 'HOME_WIN'
    WHEN up.selected_team_id = g.away_team_id THEN 'AWAY_WIN'
    ELSE NULL
END
WHERE up.selected_outcome IS NULL;

-- 매핑할 수 없는 과거 데이터가 있으면 이 NOT NULL 변경이 실패하여 손실을 막는다.
ALTER TABLE user_predictions
    MODIFY COLUMN selected_outcome VARCHAR(20) NOT NULL;

ALTER TABLE user_predictions
    ADD COLUMN settlement_status VARCHAR(20) NULL AFTER settled;

UPDATE user_predictions
SET settlement_status = CASE
    WHEN settled = 0 THEN 'PENDING'
    WHEN is_correct = 1 THEN 'WON'
    WHEN is_correct = 0 THEN 'LOST'
    ELSE 'REFUNDED'
END;

ALTER TABLE user_predictions
    MODIFY COLUMN settlement_status VARCHAR(20) NOT NULL;

CREATE INDEX idx_user_predictions_game_outcome
    ON user_predictions (game_id, selected_outcome);

ALTER TABLE user_predictions
    DROP COLUMN selected_team_id;

-- 2. 취소 여부(status)와 별개인 실제 경기 결과를 저장한다.
ALTER TABLE games
    ADD COLUMN result VARCHAR(20) NULL AFTER winner_team_id;

UPDATE games
SET result = CASE
    WHEN status = 'FINISHED' AND winner_team_id = home_team_id THEN 'HOME_WIN'
    WHEN status = 'FINISHED' AND winner_team_id = away_team_id THEN 'AWAY_WIN'
    WHEN status = 'FINISHED' AND winner_team_id IS NULL THEN 'DRAW'
    ELSE NULL
END;

CREATE INDEX idx_games_result ON games (result);

-- 모든 경기의 참여 마감 시각을 경기 시작 30분 전으로 맞춘다.
UPDATE games
SET prediction_close_at = TIMESTAMP(game_date, game_time) - INTERVAL 30 MINUTE
WHERE game_date IS NOT NULL
  AND game_time IS NOT NULL;

-- 3. AI 확률은 사용자 배당과 분리하여 홈/무/원정 세 값으로 저장한다.
ALTER TABLE system_predictions
    ADD COLUMN draw_probability DECIMAL(5, 2) NULL
        AFTER home_win_probability;

UPDATE system_predictions
SET draw_probability = CASE
    WHEN home_win_probability IS NOT NULL
         AND away_win_probability IS NOT NULL
        THEN GREATEST(0, 100 - home_win_probability - away_win_probability)
    ELSE NULL
END;

-- 4. 사용자 포인트 집계와 경기별 최종 배당을 저장한다.
CREATE TABLE game_odds (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    home_win_points BIGINT NOT NULL DEFAULT 0,
    draw_points BIGINT NOT NULL DEFAULT 0,
    away_win_points BIGINT NOT NULL DEFAULT 0,
    final_home_win_odds DECIMAL(8, 2) NULL,
    final_draw_odds DECIMAL(8, 2) NULL,
    final_away_win_odds DECIMAL(8, 2) NULL,
    finalized TINYINT(1) NOT NULL DEFAULT 0,
    finalized_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_game_odds_game UNIQUE (game_id),
    CONSTRAINT fk_game_odds_game
        FOREIGN KEY (game_id) REFERENCES games (id)
) ENGINE = InnoDB;

CREATE INDEX idx_game_odds_finalized ON game_odds (finalized);

INSERT INTO game_odds (
    game_id,
    home_win_points,
    draw_points,
    away_win_points,
    created_at,
    updated_at
)
SELECT
    g.id,
    COALESCE(SUM(CASE
        WHEN up.selected_outcome = 'HOME_WIN' THEN up.point_amount ELSE 0
    END), 0),
    COALESCE(SUM(CASE
        WHEN up.selected_outcome = 'DRAW' THEN up.point_amount ELSE 0
    END), 0),
    COALESCE(SUM(CASE
        WHEN up.selected_outcome = 'AWAY_WIN' THEN up.point_amount ELSE 0
    END), 0),
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6)
FROM games g
LEFT JOIN user_predictions up ON up.game_id = g.id
GROUP BY g.id;

-- 이미 마감된 경기의 최종 배당을 기존 참여 포인트 기준으로 확정한다.
UPDATE game_odds odds
JOIN games g ON g.id = odds.game_id
SET
    odds.final_home_win_odds = CASE
        WHEN odds.home_win_points = 0 THEN 10.00
        ELSE LEAST(
            10.00,
            ROUND(
                (odds.home_win_points + odds.draw_points + odds.away_win_points)
                / odds.home_win_points,
                2
            )
        )
    END,
    odds.final_draw_odds = CASE
        WHEN odds.draw_points = 0 THEN 10.00
        ELSE LEAST(
            10.00,
            ROUND(
                (odds.home_win_points + odds.draw_points + odds.away_win_points)
                / odds.draw_points,
                2
            )
        )
    END,
    odds.final_away_win_odds = CASE
        WHEN odds.away_win_points = 0 THEN 10.00
        ELSE LEAST(
            10.00,
            ROUND(
                (odds.home_win_points + odds.draw_points + odds.away_win_points)
                / odds.away_win_points,
                2
            )
        )
    END,
    odds.finalized = 1,
    odds.finalized_at = COALESCE(g.prediction_close_at, CURRENT_TIMESTAMP(6)),
    odds.updated_at = CURRENT_TIMESTAMP(6)
WHERE g.status <> 'SCHEDULED'
   OR g.prediction_close_at <= CURRENT_TIMESTAMP(6);
