package com.playball.kbopredictor.point.entity;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.prediction.entity.GameSettlement;
import com.playball.kbopredictor.prediction.entity.UserPrediction;
import com.playball.kbopredictor.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "point_histories",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_point_histories_prediction_type_revision",
                        columnNames = {
                                "user_prediction_id",
                                "type",
                                "settlement_revision"
                        }
                ),
                @UniqueConstraint(
                        name = "uk_point_histories_reversal_of",
                        columnNames = "reversal_of_id"
                ),
                @UniqueConstraint(
                        name = "uk_point_histories_user_bonus_date_type",
                        columnNames = {"user_id", "bonus_date", "type"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id")
    private Game game;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_prediction_id")
    private UserPrediction userPrediction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_id")
    private GameSettlement settlement;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversal_of_id", unique = true)
    private PointHistory reversalOf;

    @Column(name = "settlement_revision", nullable = false)
    private int settlementRevision;

    @Column(name = "bonus_date")
    private LocalDate bonusDate;

    @Column(name = "point_change", nullable = false)
    private int pointChange;

    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PointHistoryType type;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static PointHistory create(
            User user,
            Game game,
            UserPrediction userPrediction,
            int pointChange,
            int balanceAfter,
            PointHistoryType type,
            String description,
            LocalDateTime createdAt
    ) {
        return create(
                user,
                game,
                userPrediction,
                null,
                null,
                pointChange,
                balanceAfter,
                type,
                description,
                createdAt,
                null
        );
    }

    public static PointHistory create(
            User user,
            Game game,
            UserPrediction userPrediction,
            GameSettlement settlement,
            PointHistory reversalOf,
            int pointChange,
            int balanceAfter,
            PointHistoryType type,
            String description,
            LocalDateTime createdAt
    ) {
        return create(
                user,
                game,
                userPrediction,
                settlement,
                reversalOf,
                pointChange,
                balanceAfter,
                type,
                description,
                createdAt,
                null
        );
    }

    public static PointHistory createDailyLoginBonus(
            User user,
            int pointChange,
            int balanceAfter,
            LocalDate bonusDate,
            LocalDateTime createdAt
    ) {
        if (bonusDate == null) {
            throw new IllegalArgumentException("Bonus date is required.");
        }
        return create(
                user,
                null,
                null,
                null,
                null,
                pointChange,
                balanceAfter,
                PointHistoryType.DAILY_LOGIN_BONUS,
                "일일 로그인 보너스",
                createdAt,
                bonusDate
        );
    }

    private static PointHistory create(
            User user,
            Game game,
            UserPrediction userPrediction,
            GameSettlement settlement,
            PointHistory reversalOf,
            int pointChange,
            int balanceAfter,
            PointHistoryType type,
            String description,
            LocalDateTime createdAt,
            LocalDate bonusDate
    ) {
        PointHistory history = new PointHistory();
        history.user = user;
        history.game = game;
        history.userPrediction = userPrediction;
        history.settlement = settlement;
        history.reversalOf = reversalOf;
        history.settlementRevision = settlement == null
                ? 0
                : settlement.getRevision();
        history.bonusDate = bonusDate;
        history.pointChange = pointChange;
        history.balanceAfter = balanceAfter;
        history.type = type;
        history.description = description;
        history.createdAt = createdAt;
        return history;
    }
}
