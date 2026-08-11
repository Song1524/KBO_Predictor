package com.playball.kbopredictor.prediction.history;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PredictionFeatureSnapshotRepository
        extends JpaRepository<PredictionFeatureSnapshot, Long> {

    Optional<PredictionFeatureSnapshot> findByGameIdAndFeatureAsOfAndGenerationMethod(
            Long gameId,
            LocalDateTime featureAsOf,
            PredictionGenerationMethod generationMethod
    );

    Optional<PredictionFeatureSnapshot>
    findTopByGameIdAndGenerationMethodOrderByFeatureAsOfDescIdDesc(
            Long gameId,
            PredictionGenerationMethod generationMethod
    );

    @Query("""
            select snapshot
            from PredictionFeatureSnapshot snapshot
            join fetch snapshot.game game
            join fetch game.homeTeam
            join fetch game.awayTeam
            where game.status = com.playball.kbopredictor.game.entity.GameStatus.FINISHED
              and game.result is not null
              and snapshot.generationMethod = com.playball.kbopredictor.prediction.history.PredictionGenerationMethod.HISTORICAL_INTERNAL_GAMES
              and game.gameDate between :from and :to
            order by game.gameDate asc, game.gameTime asc, game.id asc
            """)
    List<PredictionFeatureSnapshot> findEvaluationSnapshots(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );
}
