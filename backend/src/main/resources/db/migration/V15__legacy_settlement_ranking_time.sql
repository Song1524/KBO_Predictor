-- V13 이전 정산은 settled_at이 없었지만 정산 시 updated_at이 갱신됐다.
-- V14가 만든 LEGACY 회차의 기간 랭킹 시각을 기존 실제 정산 시각으로 복구한다.
UPDATE user_predictions prediction
JOIN game_settlements settlement
  ON settlement.id = prediction.settlement_id
 AND settlement.source = 'LEGACY'
SET prediction.settled_at = prediction.updated_at
WHERE prediction.settled = 1
  AND prediction.updated_at IS NOT NULL;

UPDATE game_settlements settlement
JOIN (
    SELECT
        prediction.settlement_id,
        MAX(prediction.settled_at) AS settled_at
    FROM user_predictions prediction
    WHERE prediction.settlement_id IS NOT NULL
      AND prediction.settled = 1
    GROUP BY prediction.settlement_id
) settlement_time
  ON settlement_time.settlement_id = settlement.id
SET settlement.settled_at = settlement_time.settled_at
WHERE settlement.source = 'LEGACY';
