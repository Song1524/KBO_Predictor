-- 주기적인 배당 마감 대상 조회를 위한 인덱스
CREATE INDEX idx_games_prediction_close_at
    ON games (prediction_close_at);
