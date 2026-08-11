package com.playball.kbopredictor.prediction.history;

import com.playball.kbopredictor.prediction.entity.SystemPrediction;
import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.prediction.engine.PredictionEngineResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SystemPredictionHistoryRecorder {

    private final SystemPredictionHistoryRepository historyRepository;
    private final Clock clock;

    public boolean recordOperational(
            SystemPrediction prediction,
            PredictionFeatureSnapshot featureSnapshot,
            PredictionStage stage
    ) {
        String key = stage == PredictionStage.FINAL
                ? finalKey(prediction)
                : operationalKey(prediction, featureSnapshot, stage);
        if (historyRepository.findByDeduplicationKey(key).isPresent()) {
            return false;
        }
        historyRepository.saveAndFlush(SystemPredictionHistory.fromOperational(
                prediction,
                featureSnapshot,
                stage,
                key,
                LocalDateTime.now(clock)
        ));
        return true;
    }

    public boolean recordShadow(
            Game game,
            PredictionFeatureSnapshot featureSnapshot,
            PredictionEngineResult result,
            PredictionStage stage,
            String artifactHash,
            LocalDateTime generatedAt
    ) {
        String key = stage == PredictionStage.FINAL
                ? finalKey(game.getId(), result.modelVersion(), PredictionSource.SHADOW)
                : "SHADOW:%d:%s:%s:SNAPSHOT:%d".formatted(
                        game.getId(),
                        result.modelVersion(),
                        stage,
                        featureSnapshot.getId()
                );
        if (historyRepository.findByDeduplicationKey(key).isPresent()) {
            return false;
        }
        historyRepository.saveAndFlush(SystemPredictionHistory.fromShadow(
                game,
                featureSnapshot,
                result,
                stage,
                artifactHash,
                generatedAt,
                key,
                LocalDateTime.now(clock)
        ));
        return true;
    }

    public boolean finalizeHistory(SystemPredictionHistory source) {
        String key = finalKey(
                source.getGame().getId(),
                source.getModelVersion(),
                source.getPredictionSource()
        );
        if (historyRepository.findByDeduplicationKey(key).isPresent()) {
            return false;
        }
        historyRepository.saveAndFlush(SystemPredictionHistory.finalCopy(
                source,
                key,
                LocalDateTime.now(clock)
        ));
        return true;
    }

    private String operationalKey(
            SystemPrediction prediction,
            PredictionFeatureSnapshot featureSnapshot,
            PredictionStage stage
    ) {
        return "OPERATIONAL:%d:%s:%s:SNAPSHOT:%d".formatted(
                prediction.getGame().getId(),
                prediction.getModelVersion(),
                stage,
                featureSnapshot.getId()
        );
    }

    private String finalKey(SystemPrediction prediction) {
        return finalKey(
                prediction.getGame().getId(),
                prediction.getModelVersion(),
                PredictionSource.OPERATIONAL
        );
    }

    private String finalKey(
            Long gameId,
            String modelVersion,
            PredictionSource source
    ) {
        return "%s:%d:%s:FINAL".formatted(source, gameId, modelVersion);
    }
}
