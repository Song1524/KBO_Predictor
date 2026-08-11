package com.playball.kbopredictor.admin.service;

import com.playball.kbopredictor.admin.dto.AdminDashboardSummaryResponse;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.engine.ActivePredictionModelProperties;
import com.playball.kbopredictor.prediction.engine.LogisticModelArtifactLoader;
import com.playball.kbopredictor.prediction.history.PredictionSource;
import com.playball.kbopredictor.prediction.history.SystemPredictionHistoryRepository;
import com.playball.kbopredictor.prediction.repository.SystemPredictionRepository;
import com.playball.kbopredictor.prediction.repository.UserPredictionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminDashboardService {

    private final GameRepository gameRepository;
    private final SystemPredictionRepository systemPredictionRepository;
    private final SystemPredictionHistoryRepository historyRepository;
    private final UserPredictionRepository userPredictionRepository;
    private final ActivePredictionModelProperties modelProperties;
    private final LogisticModelArtifactLoader logisticArtifactLoader;
    private final Clock clock;

    public AdminDashboardSummaryResponse getSummary() {
        LocalDate today = LocalDate.now(clock);
        EnumMap<GameStatus, Long> gameCounts = new EnumMap<>(GameStatus.class);
        for (GameRepository.StatusCount count
                : gameRepository.countStatusesByGameDate(today)) {
            gameCounts.put(count.getStatus(), count.getCount());
        }
        long scheduled = gameCounts.getOrDefault(GameStatus.SCHEDULED, 0L);
        long inProgress = gameCounts.getOrDefault(GameStatus.IN_PROGRESS, 0L);
        long finished = gameCounts.getOrDefault(GameStatus.FINISHED, 0L);
        long cancelled = gameCounts.getOrDefault(GameStatus.CANCELLED, 0L);
        return new AdminDashboardSummaryResponse(
                today,
                scheduled + inProgress + finished + cancelled,
                scheduled,
                inProgress,
                finished,
                cancelled,
                systemPredictionRepository.countByGameGameDate(today),
                historyRepository.countDistinctGamesBySourceAndGameDate(
                        PredictionSource.SHADOW, today
                ),
                userPredictionRepository.countBySettledFalse(),
                modelProperties.getActiveModel(),
                logisticArtifactLoader.artifact().modelVersion(),
                logisticArtifactLoader.artifactSha256()
        );
    }
}
