-- 기존 team_stats 날짜별 snapshot에 KBO 공식 순위표 원본 필드를 보존한다.
-- 과거 row는 임의 보정하지 않고, 다음 정상 수집부터 공식값이 채워진다.

ALTER TABLE team_stats
    ADD COLUMN official_rank INT NULL AFTER stat_date,
    ADD COLUMN games_played INT NULL AFTER official_rank,
    ADD COLUMN games_behind DECIMAL(6, 1) NULL AFTER win_rate,
    ADD COLUMN streak VARCHAR(20) NULL AFTER games_behind;

CREATE INDEX idx_team_stats_standings_snapshot
    ON team_stats (stat_date, official_rank);
