ALTER TABLE point_histories
    DROP CHECK chk_point_histories_balance_nonnegative,
    ADD CONSTRAINT chk_point_histories_negative_balance_policy
        CHECK (
            balance_after >= 0
            OR point_change > 0
            OR (
                point_change < 0
                AND type IN (
                    'PREDICTION_REWARD_ROLLBACK',
                    'GAME_CANCEL_REFUND_ROLLBACK'
                )
            )
        );
