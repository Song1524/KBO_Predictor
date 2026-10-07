package com.playball.kbopredictor.prediction.generation;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.engine.PredictionEngine;
import com.playball.kbopredictor.prediction.feature.PredictionFeatureService;
import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SystemPredictionGenerationService {

    private final GameRepository gameRepository;
    private final PredictionFeatureService featureService;
    private final PredictionEngine predictionEngine;
    private final SystemPredictionWriter writer;
    private final ShadowPredictionService shadowPredictionService;
    private final Clock clock;

    public SystemPredictionGenerationResponse generate(Long gameId) {
        return generate(gameId, PredictionRefreshReason.DATA_REFRESH);
    }

    public SystemPredictionGenerationResponse refreshStale(Long gameId) {
        return refreshStale(gameId, PredictionRefreshReason.DATA_REFRESH);
    }

    public SystemPredictionGenerationResponse refreshStale(
            Long gameId,
            PredictionRefreshReason refreshReason
    ) {
        return generate(gameId, refreshReason);
    }

    private SystemPredictionGenerationResponse generate(
            Long gameId,
            PredictionRefreshReason refreshReason
    ) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "경기를 찾을 수 없습니다."
                ));
        SystemPredictionGenerationResponse precheck = precheck(game);
        if (precheck != null) {
            return precheck;
        }

        SystemPredictionReconciliationResult reconciled = writer.reconcile(
                gameId, () -> featureService.build(gameId), predictionEngine, refreshReason);
        PredictionFeatures features = reconciled.features();
        SystemPredictionWriteResult write = reconciled.write();
        if (write.written() && !"logistic-v1".equals(write.response().modelVersion())) {
            try {
                shadowPredictionService.generate(features, write);
            } catch (RuntimeException exception) {
                log.error(
                        "Shadow prediction failed without affecting operational prediction: gameId={}, error={}",
                        gameId,
                        exception.getMessage(),
                        exception
                );
            }
        }
        return write.response();
    }

    public SystemPredictionGenerationBatchResponse generateForDate(
            LocalDate date
    ) {
        return reconcileForDate(date);
    }

    public SystemPredictionGenerationBatchResponse refreshStaleForDate(
            LocalDate date
    ) {
        return reconcileForDate(date);
    }

    private SystemPredictionGenerationBatchResponse reconcileForDate(
            LocalDate date
    ) {
        List<SystemPredictionGenerationResponse> results = new ArrayList<>();
        for (Game game : gameRepository.findByGameDateOrderByGameTimeAsc(date)) {
            try {
                results.add(generate(
                        game.getId(),
                        PredictionRefreshReason.DATA_REFRESH
                ));
            } catch (RuntimeException exception) {
                String message = exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage();
                results.add(SystemPredictionGenerationResponse.failed(
                        game.getId(),
                        message
                ));
                log.warn(
                        "시스템 예측 단건 생성 실패: date={}, gameId={}, error={}",
                        date,
                        game.getId(),
                        message,
                        exception
                );
            }
        }
        SystemPredictionGenerationBatchResponse response =
                SystemPredictionGenerationBatchResponse.from(date, results);
        log.info(
                "시스템 예측 일괄 생성 완료: date={}, target={}, created={}, updated={}, skipped={}, failed={}",
                date,
                response.targetCount(),
                response.createdCount(),
                response.updatedCount(),
                response.skippedCount(),
                response.failedCount()
        );
        return response;
    }

    private SystemPredictionGenerationResponse precheck(
            Game game
    ) {
        if (game.getStatus() != GameStatus.SCHEDULED) {
            return SystemPredictionGenerationResponse.skipped(
                    game.getId(),
                    SystemPredictionGenerationStatus.SKIPPED_NOT_SCHEDULED,
                    "예정 경기가 아닙니다."
            );
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (game.getGameDate() == null
                || game.getGameTime() == null
                || !now.isBefore(LocalDateTime.of(
                game.getGameDate(), game.getGameTime()
        ))) {
            return SystemPredictionGenerationResponse.skipped(
                    game.getId(),
                    SystemPredictionGenerationStatus.SKIPPED_CLOSED,
                    "System predictions are reconciled only before game start."
            );
        }
        LocalDateTime closeAt = game.getPredictionCloseAt();
        if (closeAt != null && !now.isBefore(closeAt)) {
            return SystemPredictionGenerationResponse.skipped(
                    game.getId(),
                    SystemPredictionGenerationStatus.SKIPPED_CLOSED,
                    "예측 마감 이후에는 시스템 예측을 변경할 수 없습니다."
            );
        }
        return null;
    }
}
