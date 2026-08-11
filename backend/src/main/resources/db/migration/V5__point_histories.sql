CREATE TABLE point_histories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    game_id BIGINT NULL,
    user_prediction_id BIGINT NULL,
    point_change INT NOT NULL,
    balance_after INT NOT NULL,
    type VARCHAR(30) NOT NULL,
    description VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_point_histories_prediction_type
        UNIQUE (user_prediction_id, type),
    CONSTRAINT chk_point_histories_change_nonzero
        CHECK (point_change <> 0),
    CONSTRAINT chk_point_histories_balance_nonnegative
        CHECK (balance_after >= 0),
    CONSTRAINT fk_point_histories_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_point_histories_game
        FOREIGN KEY (game_id) REFERENCES games (id),
    CONSTRAINT fk_point_histories_user_prediction
        FOREIGN KEY (user_prediction_id) REFERENCES user_predictions (id)
) ENGINE = InnoDB;

CREATE INDEX idx_point_histories_user_created
    ON point_histories (user_id, created_at DESC, id DESC);

CREATE INDEX idx_point_histories_game
    ON point_histories (game_id);
