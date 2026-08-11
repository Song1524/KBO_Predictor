package com.playball.kbopredictor.prediction.history;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SystemPredictionHistoryRepository
        extends JpaRepository<SystemPredictionHistory, Long> {

    Optional<SystemPredictionHistory> findByDeduplicationKey(String key);

    Optional<SystemPredictionHistory>
    findTopByGameIdAndModelVersionAndPredictionSourceOrderByGeneratedAtDescIdDesc(
            Long gameId,
            String modelVersion,
            PredictionSource predictionSource
    );

    Optional<SystemPredictionHistory>
    findByGameIdAndModelVersionAndPredictionSourceAndPredictionStage(
            Long gameId,
            String modelVersion,
            PredictionSource predictionSource,
            PredictionStage predictionStage
    );

    boolean existsByGameIdAndPredictionSourceAndPredictionStage(
            Long gameId,
            PredictionSource source,
            PredictionStage stage
    );

    boolean existsByGameIdAndModelVersionAndPredictionSourceAndPredictionStage(
            Long gameId,
            String modelVersion,
            PredictionSource source,
            PredictionStage stage
    );

    @Query("""
            select history
            from SystemPredictionHistory history
            join fetch history.game game
            join fetch game.homeTeam
            join fetch game.awayTeam
            left join fetch history.featureSnapshot
            where history.modelVersion = :modelVersion
              and history.predictionSource = :source
              and history.predictionStage = :stage
              and game.gameDate between :from and :to
            order by game.gameDate asc, game.gameTime asc, game.id asc
            """)
    List<SystemPredictionHistory> findForEvaluation(
            @Param("modelVersion") String modelVersion,
            @Param("source") PredictionSource source,
            @Param("stage") PredictionStage stage,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    @Query("""
            select count(distinct history.game.id)
            from SystemPredictionHistory history
            where history.predictionSource = :source
              and history.game.gameDate = :gameDate
            """)
    long countDistinctGamesBySourceAndGameDate(
            @Param("source") PredictionSource source,
            @Param("gameDate") LocalDate gameDate
    );
}
