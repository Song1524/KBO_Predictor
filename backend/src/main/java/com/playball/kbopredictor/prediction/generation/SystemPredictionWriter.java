package com.playball.kbopredictor.prediction.generation;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.engine.PredictionEngineResult;
import com.playball.kbopredictor.prediction.engine.PredictionEngine;
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

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class SystemPredictionWriter {

    private final GameRepository gameRepository;
    private final SystemPredictionRepository systemPredictionRepository;
    private final PredictionFeatureSnapshotRepository snapshotRepository;
    private final SystemPredictionHistoryRecorder historyRecorder;
    private final Clock clock;

    private enum Action { CREATE, REFRESH, SKIP }

    /** Both the input read and write decision occur under the same game lock. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SystemPredictionReconciliationResult reconcile(
            Long gameId, Supplier<PredictionFeatures> featureSupplier,
            PredictionEngine engine, PredictionRefreshReason reason
    ) {
        Game game = gameRepository.findByIdForUpdate(gameId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found."));
        SystemPredictionWriteResult blocked = writable(game);
        if (blocked != null) return new SystemPredictionReconciliationResult(null, blocked);
        PredictionFeatures features = featureSupplier.get();
        if (!gameId.equals(features.gameId())) throw new IllegalArgumentException("Prediction input game mismatch");
        SystemPrediction current = systemPredictionRepository.findByGameId(gameId).orElse(null);
        if (action(current, features, engine.modelVersion()) == Action.SKIP) {
            return new SystemPredictionReconciliationResult(features, skipped(game,
                    SystemPredictionGenerationStatus.SKIPPED_UP_TO_DATE, "System prediction input is unchanged."));
        }
        PredictionEngineResult result = engine.predict(features);
        return new SystemPredictionReconciliationResult(features,
                writeLocked(features, result, true, reason));
    }

    private SystemPredictionWriteResult writable(Game game) {
        if (game.getStatus() != GameStatus.SCHEDULED) {
            return skipped(game, SystemPredictionGenerationStatus.SKIPPED_NOT_SCHEDULED, "Scheduled games only.");
        }
        LocalDateTime closeAt = game.getPredictionCloseAt();
        LocalDateTime now = LocalDateTime.now(clock);
        if (game.getGameDate() == null || game.getGameTime() == null
                || !now.isBefore(LocalDateTime.of(game.getGameDate(), game.getGameTime()))
                || closeAt == null || !now.isBefore(closeAt)
                || historyRecorder.hasOperationalFinal(game.getId())) {
            return skipped(game, SystemPredictionGenerationStatus.SKIPPED_CLOSED,
                    "Predictions cannot change after close or FINAL.");
        }
        return null;
    }

    private Action action(SystemPrediction current, PredictionFeatures features, String modelVersion) {
        if (current == null) return Action.CREATE;
        if (modelVersion == null || !Objects.equals(current.getModelVersion(), modelVersion)) return Action.REFRESH;
        boolean sameInput = snapshotRepository
                .findTopByGameIdAndGenerationMethodOrderByFeatureAsOfDescIdDesc(
                        features.gameId(), PredictionGenerationMethod.OPERATIONAL_PREGAME)
                .filter(snapshot -> snapshot.usesOperationalInput(features)).isPresent();
        // Legacy snapshots without a fingerprint refresh once before close.
        return sameInput ? Action.SKIP : Action.REFRESH;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SystemPredictionGenerationResponse upsert(
            PredictionFeatures features,
            PredictionEngineResult result
    ) {
        return writeLocked(
                features, result, false, PredictionRefreshReason.DATA_REFRESH
        ).response();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SystemPredictionWriteResult write(
            PredictionFeatures features,
            PredictionEngineResult result
    ) {
        return writeLocked(
                features, result, false, PredictionRefreshReason.DATA_REFRESH
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SystemPredictionWriteResult writeIfStale(
            PredictionFeatures features,
            PredictionEngineResult result
    ) {
        return writeIfStale(
                features, result, PredictionRefreshReason.DATA_REFRESH
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SystemPredictionWriteResult writeIfStale(
            PredictionFeatures features,
            PredictionEngineResult result,
            PredictionRefreshReason refreshReason
    ) {
        return writeLocked(features, result, true, refreshReason);
    }

    private SystemPredictionWriteResult writeLocked(
            PredictionFeatures features,
            PredictionEngineResult result,
            boolean staleOnly,
            PredictionRefreshReason refreshReason
    ) {
        Game game = gameRepository.findByIdForUpdate(features.gameId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Game not found."
                ));
        LocalDateTime now = LocalDateTime.now(clock);
        SystemPredictionWriteResult blocked = writable(game);
        if (blocked != null) return blocked;

        SystemPrediction prediction = systemPredictionRepository
                .findByGameId(game.getId()).orElse(null);
        if (staleOnly && action(prediction, features, result.modelVersion()) == Action.SKIP) {
            return skipped(
                    game,
                    SystemPredictionGenerationStatus.SKIPPED_UP_TO_DATE,
                    "System prediction already uses current input and model."
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
                pitcherPlayerId(features, true),
                pitcherPlayerId(features, false),
                predictionReason(result, staleOnly ? refreshReason : null),
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

    private String pitcherPlayerId(
            PredictionFeatures features,
            boolean home
    ) {
        var pitcher = home ? features.home().startingPitcher()
                : features.away().startingPitcher();
        return pitcher == null ? null : pitcher.kboPlayerId();
    }

    private String predictionReason(
            PredictionEngineResult result,
            PredictionRefreshReason refreshReason
    ) {
        String engineReason = result.reasons() == null
                ? ""
                : String.join("\n", result.reasons());
        if (refreshReason == null || refreshReason.historyReason() == null) {
            return engineReason;
        }
        return engineReason.isBlank()
                ? refreshReason.historyReason()
                : refreshReason.historyReason() + "\n" + engineReason;
    }
}
