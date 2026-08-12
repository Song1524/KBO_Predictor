package com.playball.kbopredictor.game.repository;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface GameRepository extends JpaRepository<Game, Long> {

    interface StatusCount {
        GameStatus getStatus();
        long getCount();
    }

    List<Game> findByGameDateOrderByGameTimeAsc(LocalDate gameDate);

    @Query("""
            select game.status as status, count(game) as count
            from Game game
            where game.gameDate = :gameDate
            group by game.status
            """)
    List<StatusCount> countStatusesByGameDate(
            @Param("gameDate") LocalDate gameDate
    );

    List<Game> findByStatusOrderByGameDateAscGameTimeAsc(GameStatus status);

    @Query("""
            select game
            from Game game
            join fetch game.homeTeam
            join fetch game.awayTeam
            where game.status = :status
              and game.gameDate between :from and :to
            order by game.gameDate asc, game.gameTime asc, game.id asc
            """)
    List<Game> findByStatusAndGameDateBetweenWithTeams(
            @Param("status") GameStatus status,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    @Query("""
            select game
            from Game game
            join fetch game.homeTeam
            join fetch game.awayTeam
            where game.gameDate between :from and :to
            order by game.gameDate asc, game.gameTime asc, game.id asc
            """)
    List<Game> findByGameDateBetweenWithTeams(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    @Query("""
            select game
            from Game game
            join fetch game.homeTeam homeTeam
            join fetch game.awayTeam awayTeam
            where game.status = :status
              and game.gameDate < :cutoffExclusive
              and (homeTeam.id = :teamId or awayTeam.id = :teamId)
            order by game.gameDate desc, game.gameTime desc, game.id desc
            """)
    List<Game> findTeamGamesBefore(
            @Param("teamId") Long teamId,
            @Param("status") GameStatus status,
            @Param("cutoffExclusive") LocalDate cutoffExclusive,
            Pageable pageable
    );

    @Query("""
            select game
            from Game game
            join fetch game.homeTeam homeTeam
            join fetch game.awayTeam awayTeam
            where game.status = :status
              and game.season = :season
              and game.gameDate < :cutoffExclusive
              and (homeTeam.id = :teamId or awayTeam.id = :teamId)
            order by game.gameDate desc, game.gameTime desc, game.id desc
            """)
    List<Game> findTeamSeasonGamesBefore(
            @Param("teamId") Long teamId,
            @Param("season") Integer season,
            @Param("status") GameStatus status,
            @Param("cutoffExclusive") LocalDate cutoffExclusive
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Game> findByExternalGameId(String externalGameId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Game> findByGameDateAndGameTimeAndHomeTeamIdAndAwayTeamId(
            LocalDate gameDate,
            java.time.LocalTime gameTime,
            Long homeTeamId,
            Long awayTeamId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Game> findByGameDateAndHomeTeamIdAndAwayTeamIdOrderByGameTimeAsc(
            LocalDate gameDate,
            Long homeTeamId,
            Long awayTeamId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select game from Game game where game.id = :gameId")
    Optional<Game> findByIdForUpdate(@Param("gameId") Long gameId);

    @Query("""
            select game.id
            from Game game
            where game.predictionCloseAt is not null
              and game.predictionCloseAt <= :now
              and not exists (
                  select odds.id
                  from GameOdds odds
                  where odds.game = game
                    and odds.finalized = true
              )
            order by game.predictionCloseAt asc, game.id asc
            """)
    List<Long> findIdsPendingOddsFinalization(
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Query("""
            select game.id
            from Game game
            where game.predictionCloseAt is not null
              and game.predictionCloseAt <= :now
              and exists (
                  select prediction.id
                  from SystemPrediction prediction
                  where prediction.game = game
              )
              and (
                  not exists (
                      select history.id
                      from SystemPredictionHistory history
                      where history.game = game
                        and history.predictionSource = com.playball.kbopredictor.prediction.history.PredictionSource.OPERATIONAL
                        and history.predictionStage = com.playball.kbopredictor.prediction.history.PredictionStage.FINAL
                  )
                  or (
                      exists (
                          select shadow.id
                          from SystemPredictionHistory shadow
                          where shadow.game = game
                            and shadow.predictionSource = com.playball.kbopredictor.prediction.history.PredictionSource.SHADOW
                      )
                      and not exists (
                          select shadowFinal.id
                          from SystemPredictionHistory shadowFinal
                          where shadowFinal.game = game
                            and shadowFinal.predictionSource = com.playball.kbopredictor.prediction.history.PredictionSource.SHADOW
                            and shadowFinal.predictionStage = com.playball.kbopredictor.prediction.history.PredictionStage.FINAL
                      )
                  )
              )
            order by game.predictionCloseAt asc, game.id asc
            """)
    List<Long> findIdsPendingSystemPredictionFinalization(
            @Param("now") LocalDateTime now,
            Pageable pageable
    );
}
