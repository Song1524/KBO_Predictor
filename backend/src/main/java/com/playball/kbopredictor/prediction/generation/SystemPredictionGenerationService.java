package com.playball.kbopredictor.prediction.generation;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.engine.PredictionEngine;
import com.playball.kbopredictor.prediction.engine.PredictionEngineResult;
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
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "경기를 찾을 수 없습니다."
                ));
        SystemPredictionGenerationResponse precheck = precheck(game);
        if (precheck != null) {
            return precheck;
        }

        PredictionFeatures features = featureService.build(gameId);
        PredictionEngineResult prediction = predictionEngine.predict(features);
        SystemPredictionWriteResult write = writer.write(features, prediction);
        if (write.written() && !"logistic-v1".equals(prediction.modelVersion())) {
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
        List<SystemPredictionGenerationResponse> results = new ArrayList<>();
        for (Game game : gameRepository.findByGameDateOrderByGameTimeAsc(date)) {
            try {
                results.add(generate(game.getId()));
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

    private SystemPredictionGenerationResponse precheck(Game game) {
        if (game.getStatus() != GameStatus.SCHEDULED) {
            return SystemPredictionGenerationResponse.skipped(
                    game.getId(),
                    SystemPredictionGenerationStatus.SKIPPED_NOT_SCHEDULED,
                    "예정 경기가 아닙니다."
            );
        }
        LocalDateTime closeAt = game.getPredictionCloseAt();
        if (closeAt != null && !LocalDateTime.now(clock).isBefore(closeAt)) {
            return SystemPredictionGenerationResponse.skipped(
                    game.getId(),
                    SystemPredictionGenerationStatus.SKIPPED_CLOSED,
                    "예측 마감 이후에는 시스템 예측을 변경할 수 없습니다."
            );
        }
        return null;
    }
}
