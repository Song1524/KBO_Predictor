package com.playball.kbopredictor.prediction.entity;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "user_predictions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_user_prediction_user_game",
                        columnNames = {"user_id", "game_id"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPrediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id")
    private Game game;

    @Enumerated(EnumType.STRING)
    @Column(name = "selected_outcome", nullable = false, length = 20)
    private PredictionOutcome selectedOutcome;

    @Column(name = "point_amount")
    private Integer pointAmount;

    @Column(name = "is_correct")
    private Boolean isCorrect;

    private Boolean settled;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status", nullable = false, length = 20)
    private PredictionSettlementStatus settlementStatus;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_id")
    private GameSettlement settlement;

    public static UserPrediction create(
            User user,
            Game game,
            PredictionOutcome selectedOutcome,
            Integer pointAmount
    ) {
        UserPrediction prediction = new UserPrediction();
        prediction.user = user;
        prediction.game = game;
        prediction.selectedOutcome = selectedOutcome;
        prediction.pointAmount = pointAmount;
        prediction.isCorrect = null;
        prediction.settled = false;
        prediction.settlementStatus = PredictionSettlementStatus.PENDING;
        prediction.createdAt = LocalDateTime.now();
        prediction.updatedAt = LocalDateTime.now();
        prediction.settledAt = null;
        prediction.settlement = null;

        return prediction;
    }

    public void settleWon(LocalDateTime settledAt) {
        settle(PredictionSettlementStatus.WON, true, settledAt, null);
    }

    public void settleWon(
            LocalDateTime settledAt,
            GameSettlement settlement
    ) {
        settle(PredictionSettlementStatus.WON, true, settledAt, settlement);
    }

    public void settleLost(LocalDateTime settledAt) {
        settle(PredictionSettlementStatus.LOST, false, settledAt, null);
    }

    public void settleLost(
            LocalDateTime settledAt,
            GameSettlement settlement
    ) {
        settle(PredictionSettlementStatus.LOST, false, settledAt, settlement);
    }

    public void refund(LocalDateTime settledAt) {
        settle(PredictionSettlementStatus.REFUNDED, null, settledAt, null);
    }

    public void refund(
            LocalDateTime settledAt,
            GameSettlement settlement
    ) {
        settle(PredictionSettlementStatus.REFUNDED, null, settledAt, settlement);
    }

    private void settle(
            PredictionSettlementStatus status,
            Boolean correct,
            LocalDateTime settledAt,
            GameSettlement settlement
    ) {
        if (Boolean.TRUE.equals(this.settled) || this.settledAt != null) {
            throw new IllegalStateException("Prediction is already settled");
        }

        this.isCorrect = correct;
        this.settled = true;
        this.settlementStatus = status;
        this.settledAt = Objects.requireNonNull(settledAt, "settledAt");
        this.settlement = settlement;
        this.updatedAt = settledAt;
    }

    public void rollbackSettlement(LocalDateTime rolledBackAt) {
        if (!Boolean.TRUE.equals(settled) || settledAt == null) {
            throw new IllegalStateException("Prediction is not settled");
        }
        this.isCorrect = null;
        this.settled = false;
        this.settlementStatus = PredictionSettlementStatus.PENDING;
        this.settledAt = null;
        this.settlement = null;
        this.updatedAt = Objects.requireNonNull(rolledBackAt, "rolledBackAt");
    }
}
