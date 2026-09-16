ALTER TABLE games
    ADD CONSTRAINT chk_games_distinct_teams
        CHECK (home_team_id <> away_team_id),
    ADD CONSTRAINT chk_games_scores_nonnegative
        CHECK (
            (home_score IS NULL OR home_score >= 0)
            AND (away_score IS NULL OR away_score >= 0)
        );

ALTER TABLE user_predictions
    ADD CONSTRAINT chk_user_predictions_point_minimum
        CHECK (point_amount >= 100),
    ADD CONSTRAINT chk_user_predictions_point_unit
        CHECK (MOD(point_amount, 100) = 0);

ALTER TABLE game_odds
    ADD CONSTRAINT chk_game_odds_points_nonnegative
        CHECK (
            home_win_points >= 0
            AND draw_points >= 0
            AND away_win_points >= 0
        ),
    ADD CONSTRAINT chk_game_odds_final_odds_positive
        CHECK (
            (final_home_win_odds IS NULL OR final_home_win_odds > 0)
            AND (final_draw_odds IS NULL OR final_draw_odds > 0)
            AND (final_away_win_odds IS NULL OR final_away_win_odds > 0)
        );

ALTER TABLE starting_pitchers
    ADD CONSTRAINT uk_starting_pitchers_game_team
        UNIQUE (game_id, team_id);
