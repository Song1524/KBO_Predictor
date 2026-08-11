package com.playball.kbopredictor.prediction.entity;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.team.entity.Team;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "system_predictions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SystemPrediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id")
    private Game game;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "predicted_winner_team_id")
    private Team predictedWinnerTeam;

    @Enumerated(EnumType.STRING)
    @Column(name = "predicted_outcome", nullable = false, length = 20)
    private PredictionOutcome predictedOutcome;

    @Column(name = "home_win_probability", precision = 5, scale = 2)
    private BigDecimal homeWinProbability;

    @Column(name = "draw_probability", precision = 5, scale = 2)
    private BigDecimal drawProbability;

    @Column(name = "away_win_probability", precision = 5, scale = 2)
    private BigDecimal awayWinProbability;

    @Column(name = "model_version", nullable = false, length = 50)
    private String modelVersion;

    @Column(name = "feature_coverage", precision = 5, scale = 3)
    private BigDecimal featureCoverage;

    @Column(name = "home_stat_date")
    private java.time.LocalDate homeStatDate;

    @Column(name = "away_stat_date")
    private java.time.LocalDate awayStatDate;

    @Column(name = "home_pitcher_stat_date")
    private java.time.LocalDate homePitcherStatDate;

    @Column(name = "away_pitcher_stat_date")
    private java.time.LocalDate awayPitcherStatDate;

    @Column(name = "home_score_point", precision = 10, scale = 2)
    private BigDecimal homeScorePoint;

    @Column(name = "away_score_point", precision = 10, scale = 2)
    private BigDecimal awayScorePoint;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    public static SystemPrediction create(Game game, LocalDateTime now) {
        SystemPrediction prediction = new SystemPrediction();
        prediction.game = game;
        prediction.createdAt = now;
        return prediction;
    }

    public void update(
            Team predictedWinnerTeam,
            PredictionOutcome predictedOutcome,
            BigDecimal homeWinProbability,
            BigDecimal drawProbability,
            BigDecimal awayWinProbability,
            String modelVersion,
            BigDecimal featureCoverage,
            java.time.LocalDate homeStatDate,
            java.time.LocalDate awayStatDate,
            java.time.LocalDate homePitcherStatDate,
            java.time.LocalDate awayPitcherStatDate,
            String reason,
            LocalDateTime generatedAt
    ) {
        this.predictedWinnerTeam = predictedWinnerTeam;
        this.predictedOutcome = predictedOutcome;
        this.homeWinProbability = homeWinProbability;
        this.drawProbability = drawProbability;
        this.awayWinProbability = awayWinProbability;
        this.modelVersion = modelVersion;
        this.featureCoverage = featureCoverage;
        this.homeStatDate = homeStatDate;
        this.awayStatDate = awayStatDate;
        this.homePitcherStatDate = homePitcherStatDate;
        this.awayPitcherStatDate = awayPitcherStatDate;
        this.reason = reason;
        this.generatedAt = generatedAt;
    }
}
