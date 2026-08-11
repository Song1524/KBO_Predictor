package com.playball.kbopredictor.stats.repository;

import com.playball.kbopredictor.stats.entity.PitcherStat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

public interface PitcherStatRepository extends JpaRepository<PitcherStat, Long> {

    Optional<PitcherStat> findByPlayerIdAndSeasonAndStatDate(
            Long playerId,
            Integer season,
            LocalDate statDate
    );

    Optional<PitcherStat> findTopByPlayerIdAndStatDateBeforeOrderByStatDateDesc(
            Long playerId,
            LocalDate cutoffExclusive
    );

    Optional<PitcherStat> findTopByPlayerIdAndStatDateBeforeAndCollectedAtBeforeOrderByStatDateDesc(
            Long playerId,
            LocalDate cutoffExclusive,
            LocalDateTime availableBefore
    );
}
