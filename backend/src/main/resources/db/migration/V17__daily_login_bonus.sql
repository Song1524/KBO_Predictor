ALTER TABLE point_histories
    ADD COLUMN bonus_date DATE NULL AFTER settlement_revision,
    ADD CONSTRAINT uk_point_histories_user_bonus_date_type
        UNIQUE (user_id, bonus_date, type);
