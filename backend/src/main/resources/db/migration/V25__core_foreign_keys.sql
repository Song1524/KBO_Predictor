CREATE INDEX idx_users_favorite_team_id
    ON users (favorite_team_id);

CREATE INDEX idx_games_home_team_id
    ON games (home_team_id);

CREATE INDEX idx_games_away_team_id
    ON games (away_team_id);

CREATE INDEX idx_games_winner_team_id
    ON games (winner_team_id);

CREATE INDEX idx_system_predictions_predicted_winner_team_id
    ON system_predictions (predicted_winner_team_id);

ALTER TABLE users
    ADD CONSTRAINT fk_users_favorite_team
        FOREIGN KEY (favorite_team_id) REFERENCES teams (id)
        ON DELETE SET NULL;

ALTER TABLE games
    ADD CONSTRAINT fk_games_home_team
        FOREIGN KEY (home_team_id) REFERENCES teams (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_games_away_team
        FOREIGN KEY (away_team_id) REFERENCES teams (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_games_winner_team
        FOREIGN KEY (winner_team_id) REFERENCES teams (id)
        ON DELETE RESTRICT;

ALTER TABLE user_predictions
    ADD CONSTRAINT fk_user_predictions_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_user_predictions_game
        FOREIGN KEY (game_id) REFERENCES games (id)
        ON DELETE RESTRICT;

ALTER TABLE system_predictions
    ADD CONSTRAINT fk_system_predictions_game
        FOREIGN KEY (game_id) REFERENCES games (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_system_predictions_predicted_winner_team
        FOREIGN KEY (predicted_winner_team_id) REFERENCES teams (id)
        ON DELETE RESTRICT;

ALTER TABLE team_stats
    ADD CONSTRAINT fk_team_stats_team
        FOREIGN KEY (team_id) REFERENCES teams (id)
        ON DELETE RESTRICT;

ALTER TABLE players
    ADD CONSTRAINT fk_players_team
        FOREIGN KEY (team_id) REFERENCES teams (id)
        ON DELETE SET NULL;

ALTER TABLE starting_pitchers
    ADD CONSTRAINT fk_starting_pitchers_game
        FOREIGN KEY (game_id) REFERENCES games (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_starting_pitchers_team
        FOREIGN KEY (team_id) REFERENCES teams (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_starting_pitchers_player
        FOREIGN KEY (player_id) REFERENCES players (id)
        ON DELETE RESTRICT;

ALTER TABLE pitcher_stats
    ADD CONSTRAINT fk_pitcher_stats_player
        FOREIGN KEY (player_id) REFERENCES players (id)
        ON DELETE RESTRICT;
