package com.playball.kbopredictor.prediction.entity;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "game_settlements",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_game_settlements_game_revision",
                columnNames = {"game_id", "revision"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GameSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @Column(nullable = false)
    private int revision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GameSettlementState state;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GameSettlementSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "game_status", nullable = false, length = 50)
    private GameStatus gameStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "game_result", length = 20)
    private GameResult gameResult;

    @Column(name = "home_score")
    private Integer homeScore;

    @Column(name = "away_score")
    private Integer awayScore;

    @Column(name = "prediction_count", nullable = false)
    private int predictionCount;

    @Column(name = "correct_count", nullable = false)
    private int correctCount;

    @Column(name = "incorrect_count", nullable = false)
    private int incorrectCount;

    @Column(name = "refunded_count", nullable = false)
    private int refundedCount;

    @Column(name = "total_paid_points", nullable = false)
    private long totalPaidPoints;

    @Column(name = "settled_by_user_id")
    private Long settledByUserId;

    @Column(name = "settled_at", nullable = false)
    private LocalDateTime settledAt;

    @Column(name = "rolled_back_by_user_id")
    private Long rolledBackByUserId;

    @Column(name = "rolled_back_at")
    private LocalDateTime rolledBackAt;

    @Column(name = "rollback_reason", length = 255)
    private String rollbackReason;

    @Column(name = "reversed_point_total", nullable = false)
    private long reversedPointTotal;

    @Column(name = "result_corrected_by_user_id")
    private Long resultCorrectedByUserId;

    @Column(name = "result_corrected_at")
    private LocalDateTime resultCorrectedAt;

    @Column(name = "result_correction_reason", length = 255)
    private String resultCorrectionReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "corrected_game_status", length = 50)
    private GameStatus correctedGameStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "corrected_game_result", length = 20)
    private GameResult correctedGameResult;

    @Column(name = "corrected_home_score")
    private Integer correctedHomeScore;

    @Column(name = "corrected_away_score")
    private Integer correctedAwayScore;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static GameSettlement start(
            Game game,
            int revision,
            GameSettlementSource source,
            Long settledByUserId,
            LocalDateTime settledAt
    ) {
        if (revision < 1) {
            throw new IllegalArgumentException("Settlement revision must be positive");
        }
        GameSettlement settlement = new GameSettlement();
        settlement.game = Objects.requireNonNull(game, "game");
        settlement.revision = revision;
        settlement.state = GameSettlementState.SETTLED;
        settlement.source = Objects.requireNonNull(source, "source");
        settlement.gameStatus = game.getStatus();
        settlement.gameResult = game.getResult();
        settlement.homeScore = game.getHomeScore();
        settlement.awayScore = game.getAwayScore();
        settlement.settledByUserId = settledByUserId;
        settlement.settledAt = Objects.requireNonNull(settledAt, "settledAt");
        settlement.createdAt = settledAt;
        settlement.updatedAt = settledAt;
        return settlement;
    }

    public void complete(
            int predictionCount,
            int correctCount,
            int incorrectCount,
            int refundedCount,
            long totalPaidPoints
    ) {
        this.predictionCount = predictionCount;
        this.correctCount = correctCount;
        this.incorrectCount = incorrectCount;
        this.refundedCount = refundedCount;
        this.totalPaidPoints = totalPaidPoints;
    }

    public void rollback(
            Long adminUserId,
            String reason,
            long reversedPointTotal,
            LocalDateTime rolledBackAt
    ) {
        if (state == GameSettlementState.ROLLED_BACK) {
            return;
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Rollback reason is required");
        }
        this.state = GameSettlementState.ROLLED_BACK;
        this.rolledBackByUserId = Objects.requireNonNull(
                adminUserId,
                "adminUserId"
        );
        this.rollbackReason = reason.trim();
        this.reversedPointTotal = reversedPointTotal;
        this.rolledBackAt = Objects.requireNonNull(rolledBackAt, "rolledBackAt");
        this.updatedAt = rolledBackAt;
    }

    public void recordResultCorrection(
            Long adminUserId,
            String reason,
            Game correctedGame,
            LocalDateTime correctedAt
    ) {
        if (state != GameSettlementState.ROLLED_BACK) {
            throw new IllegalStateException(
                    "Only rolled back settlements can record a correction"
            );
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Correction reason is required");
        }
        this.resultCorrectedByUserId = Objects.requireNonNull(
                adminUserId,
                "adminUserId"
        );
        this.resultCorrectionReason = reason.trim();
        Game requiredGame = Objects.requireNonNull(
                correctedGame,
                "correctedGame"
        );
        this.correctedGameStatus = requiredGame.getStatus();
        this.correctedGameResult = requiredGame.getResult();
        this.correctedHomeScore = requiredGame.getHomeScore();
        this.correctedAwayScore = requiredGame.getAwayScore();
        this.resultCorrectedAt = Objects.requireNonNull(
                correctedAt,
                "correctedAt"
        );
        this.updatedAt = correctedAt;
    }
}
