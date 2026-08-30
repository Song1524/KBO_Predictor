-- 아직 시작하지 않은 예정 경기의 예측 마감을 경기 시작 10분 전으로 보정한다.
-- 종료·취소·진행 중 경기와 이미 시작 시각이 지난 데이터는 변경하지 않는다.
UPDATE games
SET prediction_close_at = TIMESTAMP(game_date, game_time) - INTERVAL 10 MINUTE
WHERE status = 'SCHEDULED'
  AND game_date IS NOT NULL
  AND game_time IS NOT NULL
  AND TIMESTAMP(game_date, game_time) > CURRENT_TIMESTAMP(6);

-- 기존 30분 정책으로 조기 확정됐지만 새 10분 마감에는 도달하지 않은 배당만 다시 연다.
UPDATE game_odds odds
JOIN games game ON game.id = odds.game_id
SET odds.final_home_win_odds = NULL,
    odds.final_draw_odds = NULL,
    odds.final_away_win_odds = NULL,
    odds.finalized = 0,
    odds.finalized_at = NULL,
    odds.updated_at = CURRENT_TIMESTAMP(6)
WHERE game.status = 'SCHEDULED'
  AND game.prediction_close_at > CURRENT_TIMESTAMP(6)
  AND odds.finalized = 1;
