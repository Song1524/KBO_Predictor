package com.playball.kbopredictor.prediction.history;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.prediction.engine.PredictionEngineResult;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.entity.SystemPrediction;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "system_prediction_histories",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_system_prediction_histories_dedup",
                columnNames = "deduplication_key"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SystemPredictionHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feature_snapshot_id")
    private PredictionFeatureSnapshot featureSnapshot;

    @Column(name = "home_win_probability", nullable = false, precision = 5, scale = 2)
    private BigDecimal homeWinProbability;

    @Column(name = "draw_probability", nullable = false, precision = 5, scale = 2)
    private BigDecimal drawProbability;

    @Column(name = "away_win_probability", nullable = false, precision = 5, scale = 2)
    private BigDecimal awayWinProbability;

    @Enumerated(EnumType.STRING)
    @Column(name = "predicted_outcome", nullable = false, length = 20)
    private PredictionOutcome predictedOutcome;

    @Column(name = "model_version", nullable = false, length = 50)
    private String modelVersion;

    @Column(name = "model_artifact_hash", length = 64)
    private String modelArtifactHash;

    @Column(name = "feature_coverage", nullable = false, precision = 5, scale = 3)
    private BigDecimal featureCoverage;

    @Lob
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "prediction_stage", nullable = false, length = 30)
    private PredictionStage predictionStage;

    @Enumerated(EnumType.STRING)
    @Column(name = "prediction_source", nullable = false, length = 30)
    private PredictionSource predictionSource;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Column(name = "deduplication_key", nullable = false, length = 191)
    private String deduplicationKey;

    public static SystemPredictionHistory fromOperational(
            SystemPrediction prediction,
            PredictionFeatureSnapshot featureSnapshot,
            PredictionStage stage,
            String deduplicationKey,
            LocalDateTime recordedAt
    ) {
        SystemPredictionHistory history = new SystemPredictionHistory();
        history.game = prediction.getGame();
        history.featureSnapshot = featureSnapshot;
        history.homeWinProbability = prediction.getHomeWinProbability();
        history.drawProbability = prediction.getDrawProbability();
        history.awayWinProbability = prediction.getAwayWinProbability();
        history.predictedOutcome = prediction.getPredictedOutcome();
        history.modelVersion = prediction.getModelVersion();
        history.featureCoverage = prediction.getFeatureCoverage() == null
                ? BigDecimal.ZERO
                : prediction.getFeatureCoverage();
        history.reason = prediction.getReason();
        history.predictionStage = stage;
        history.predictionSource = PredictionSource.OPERATIONAL;
        history.generatedAt = prediction.getGeneratedAt();
        history.recordedAt = recordedAt;
        history.deduplicationKey = deduplicationKey;
        return history;
    }

    public static SystemPredictionHistory fromShadow(
            Game game,
            PredictionFeatureSnapshot featureSnapshot,
            PredictionEngineResult result,
            PredictionStage stage,
            String artifactHash,
            LocalDateTime generatedAt,
            String deduplicationKey,
            LocalDateTime recordedAt
    ) {
        SystemPredictionHistory history = new SystemPredictionHistory();
        history.game = game;
        history.featureSnapshot = featureSnapshot;
        history.homeWinProbability = result.homeWinProbability();
        history.drawProbability = result.drawProbability();
        history.awayWinProbability = result.awayWinProbability();
        history.predictedOutcome = result.predictedOutcome();
        history.modelVersion = result.modelVersion();
        history.modelArtifactHash = artifactHash;
        history.featureCoverage = result.featureCoverage();
        history.reason = String.join("\n", result.reasons());
        history.predictionStage = stage;
        history.predictionSource = PredictionSource.SHADOW;
        history.generatedAt = generatedAt;
        history.recordedAt = recordedAt;
        history.deduplicationKey = deduplicationKey;
        return history;
    }

    public static SystemPredictionHistory finalCopy(
            SystemPredictionHistory source,
            String deduplicationKey,
            LocalDateTime recordedAt
    ) {
        SystemPredictionHistory history = new SystemPredictionHistory();
        history.game = source.game;
        history.featureSnapshot = source.featureSnapshot;
        history.homeWinProbability = source.homeWinProbability;
        history.drawProbability = source.drawProbability;
        history.awayWinProbability = source.awayWinProbability;
        history.predictedOutcome = source.predictedOutcome;
        history.modelVersion = source.modelVersion;
        history.modelArtifactHash = source.modelArtifactHash;
        history.featureCoverage = source.featureCoverage;
        history.reason = source.reason;
        history.predictionStage = PredictionStage.FINAL;
        history.predictionSource = source.predictionSource;
        history.generatedAt = source.generatedAt;
        history.recordedAt = recordedAt;
        history.deduplicationKey = deduplicationKey;
        return history;
    }

    public static SystemPredictionHistory fromBacktest(
            Game game,
            PredictionFeatureSnapshot snapshot,
            PredictionEngineResult result,
            String deduplicationKey,
            LocalDateTime recordedAt
    ) {
        SystemPredictionHistory history = new SystemPredictionHistory();
        history.game = game;
        history.featureSnapshot = snapshot;
        history.homeWinProbability = result.homeWinProbability();
        history.drawProbability = result.drawProbability();
        history.awayWinProbability = result.awayWinProbability();
        history.predictedOutcome = result.predictedOutcome();
        history.modelVersion = result.modelVersion();
        history.featureCoverage = result.featureCoverage();
        history.reason = String.join("\n", result.reasons());
        history.predictionStage = PredictionStage.FINAL;
        history.predictionSource = PredictionSource.BACKTEST;
        history.generatedAt = snapshot.getFeatureAsOf();
        history.recordedAt = recordedAt;
        history.deduplicationKey = deduplicationKey;
        return history;
    }
}
