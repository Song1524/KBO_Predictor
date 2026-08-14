-- 월간/주간 사용자 랭킹은 정산 시각 범위로 user_predictions를 집계한다.
-- 기존 prediction/point 값은 변경하지 않고 nullable 정산 시각과 조회 인덱스만 추가한다.
-- 기존 정산 행은 검증된 backfill 전까지 의도적으로 NULL로 유지한다.
ALTER TABLE user_predictions
    ADD COLUMN settled_at DATETIME(6) NULL AFTER settlement_status;

CREATE INDEX idx_user_predictions_ranking_period
    ON user_predictions (settled, settlement_status, settled_at, user_id);
