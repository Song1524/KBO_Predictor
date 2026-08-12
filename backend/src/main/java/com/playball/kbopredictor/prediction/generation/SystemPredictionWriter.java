package com.playball.kbopredictor.prediction.generation;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.engine.PredictionEngineResult;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.entity.SystemPrediction;
import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import com.playball.kbopredictor.prediction.history.PredictionFeatureSnapshot;
import com.playball.kbopredictor.prediction.history.PredictionFeatureSnapshotRepository;
import com.playball.kbopredictor.prediction.history.PredictionGenerationMethod;
import com.playball.kbopredictor.prediction.history.PredictionStage;
import com.playball.kbopredictor.prediction.history.SystemPredictionHistoryRecorder;
import com.playball.kbopredictor.prediction.repository.SystemPredictionRepository;
import com.playball.kbopredictor.team.entity.Team;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SystemPredictionWriter {

    private final GameRepository gameRepository;
    private final SystemPredictionRepository systemPredictionRepository;
    private final PredictionFeatureSnapshotRepository snapshotRepository;
    private final SystemPredictionHistoryRecorder historyRecorder;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SystemPredictionGenerationResponse upsert(
            PredictionFeatures features,
            PredictionEngineResult result
    ) {
        return writeLocked(features, result, false).response();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SystemPredictionWriteResult write(
            PredictionFeatures features,
            PredictionEngineResult result
    ) {
        return writeLocked(features, result, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SystemPredictionWriteResult writeIfStale(
            PredictionFeatures features,
            PredictionEngineResult result
    ) {
        return writeLocked(features, result, true);
    }

    private SystemPredictionWriteResult writeLocked(
            PredictionFeatures features,
            PredictionEngineResult result,
            boolean staleOnly
    ) {
        Game game = gameRepository.findByIdForUpdate(features.gameId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Game not found."
                ));
        LocalDateTime now = LocalDateTime.now(clock);
        if (game.getStatus() != GameStatus.SCHEDULED) {
            return skipped(game, SystemPredictionGenerationStatus.SKIPPED_NOT_SCHEDULED,
                    "Scheduled games only.");
        }
        LocalDateTime closeAt = game.getPredictionCloseAt();
        if (closeAt != null && !now.isBefore(closeAt)) {
            return skipped(game, SystemPredictionGenerationStatus.SKIPPED_CLOSED,
                    "Predictions cannot change after close.");
        }

        SystemPrediction prediction = systemPredictionRepository
                .findByGameId(game.getId()).orElse(null);
        if (staleOnly && !requiresRefresh(prediction, features, result)) {
            return skipped(
                    game,
                    SystemPredictionGenerationStatus.SKIPPED_UP_TO_DATE,
                    prediction == null
                            ? "No existing system prediction to refresh."
                            : "System prediction already uses current feature coverage and model."
            );
        }
        boolean created = prediction == null;
        if (created) {
            prediction = SystemPrediction.create(game, now);
        }
        prediction.update(
                predictedWinner(game, result.predictedOutcome()),
                result.predictedOutcome(),
                result.homeWinProbability(),
                result.drawProbability(),
                result.awayWinProbability(),
                result.modelVersion(),
                result.featureCoverage(),
                features.home().teamStatDate(),
                features.away().teamStatDate(),
                pitcherStatDate(features, true),
                pitcherStatDate(features, false),
                String.join("\n", result.reasons()),
                now
        );
        systemPredictionRepository.saveAndFlush(prediction);

        PredictionFeatureSnapshot snapshot = saveSnapshot(game, features, now);
        PredictionStage stage = created
                ? PredictionStage.INITIAL
                : stageFor(features);
        historyRecorder.recordOperational(prediction, snapshot, stage);

        SystemPredictionGenerationResponse response =
                new SystemPredictionGenerationResponse(
                        game.getId(),
                        created ? SystemPredictionGenerationStatus.CREATED
                                : SystemPredictionGenerationStatus.UPDATED,
                        result.predictedOutcome(),
                        result.homeWinProbability(),
                        result.drawProbability(),
                        result.awayWinProbability(),
                        result.modelVersion(),
                        result.featureCoverage(),
                        now,
                        created ? "System prediction created."
                                : "System prediction updated."
                );
        return new SystemPredictionWriteResult(response, snapshot.getId(), stage);
    }

    private boolean requiresRefresh(
            SystemPrediction current,
            PredictionFeatures features,
            PredictionEngineResult candidate
    ) {
        if (current == null) {
            return false;
        }
        if (!Objects.equals(current.getModelVersion(), candidate.modelVersion())) {
            return true;
        }
        if (isGreater(candidate.featureCoverage(), current.getFeatureCoverage())) {
            return true;
        }
        return isNewer(features.home().teamStatDate(), current.getHomeStatDate())
                || isNewer(features.away().teamStatDate(), current.getAwayStatDate())
                || isNewer(
                pitcherStatDate(features, true),
                current.getHomePitcherStatDate()
        )
                || isNewer(
                pitcherStatDate(features, false),
                current.getAwayPitcherStatDate()
        );
    }

    private boolean isGreater(BigDecimal candidate, BigDecimal current) {
        return candidate != null
                && (current == null || candidate.compareTo(current) > 0);
    }

    private boolean isNewer(LocalDate candidate, LocalDate current) {
        return candidate != null
                && (current == null || candidate.isAfter(current));
    }

    private SystemPredictionWriteResult skipped(
            Game game,
            SystemPredictionGenerationStatus status,
            String message
    ) {
        return new SystemPredictionWriteResult(
                SystemPredictionGenerationResponse.skipped(
                        game.getId(), status, message
                ),
                null,
                null
        );
    }

    private PredictionFeatureSnapshot saveSnapshot(
            Game game,
            PredictionFeatures features,
            LocalDateTime now
    ) {
        LocalDateTime featureAsOf = now.withNano(0);
        while (snapshotRepository
                .findByGameIdAndFeatureAsOfAndGenerationMethod(
                        game.getId(), featureAsOf,
                        PredictionGenerationMethod.OPERATIONAL_PREGAME
                )
                .isPresent()) {
            featureAsOf = featureAsOf.plusSeconds(1);
        }
        return snapshotRepository.saveAndFlush(
                PredictionFeatureSnapshot.createOperational(
                        game, features, featureAsOf, now
                )
        );
    }

    private PredictionStage stageFor(PredictionFeatures features) {
        return starterDataAvailable(features.home())
                || starterDataAvailable(features.away())
                ? PredictionStage.STARTER_UPDATED
                : PredictionStage.INITIAL;
    }

    private boolean starterDataAvailable(
            com.playball.kbopredictor.prediction.feature.TeamPredictionFeatures team
    ) {
        return team.startingPitcher() != null
                && team.startingPitcher().statsAvailable();
    }

    private Team predictedWinner(Game game, PredictionOutcome outcome) {
        return switch (outcome) {
            case HOME_WIN -> game.getHomeTeam();
            case AWAY_WIN -> game.getAwayTeam();
            case DRAW -> null;
        };
    }

    private LocalDate pitcherStatDate(
            PredictionFeatures features,
            boolean home
    ) {
        var pitcher = home ? features.home().startingPitcher()
                : features.away().startingPitcher();
        return pitcher == null ? null : pitcher.statDate();
    }
}
