ALTER TABLE system_predictions
    ADD COLUMN home_starting_pitcher_kbo_player_id VARCHAR(20) NULL
        AFTER away_pitcher_stat_date,
    ADD COLUMN away_starting_pitcher_kbo_player_id VARCHAR(20) NULL
        AFTER home_starting_pitcher_kbo_player_id;

ALTER TABLE prediction_feature_snapshots
    ADD COLUMN home_starting_pitcher_kbo_player_id VARCHAR(20) NULL
        AFTER away_venue_win_rate,
    ADD COLUMN away_starting_pitcher_kbo_player_id VARCHAR(20) NULL
        AFTER home_starting_pitcher_kbo_player_id;
