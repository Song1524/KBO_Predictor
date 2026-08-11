package com.playball.kbopredictor.prediction.repository;

import com.playball.kbopredictor.prediction.entity.GameOdds;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface GameOddsRepository extends JpaRepository<GameOdds, Long> {

    Optional<GameOdds> findByGameId(Long gameId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select odds from GameOdds odds where odds.game.id = :gameId")
    Optional<GameOdds> findByGameIdForUpdate(@Param("gameId") Long gameId);
}
