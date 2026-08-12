package com.playball.kbopredictor.stats.repository;

import com.playball.kbopredictor.stats.entity.TeamStat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.time.LocalDate;
import java.time.LocalDateTime;

public interface TeamStatRepository extends JpaRepository<TeamStat, Long> {

    Optional<TeamStat> findTopByTeamIdOrderByStatDateDesc(Long teamId);

    Optional<TeamStat> findByTeamIdAndSeasonAndStatDate(
            Long teamId,
            Integer season,
            LocalDate statDate
    );

    Optional<TeamStat> findTopByTeamIdAndStatDateBeforeOrderByStatDateDesc(
            Long teamId,
            LocalDate cutoffExclusive
    );

    Optional<TeamStat> findTopByTeamIdAndStatDateLessThanEqualAndCollectedAtBeforeOrderByStatDateDescCollectedAtDesc(
            Long teamId,
            LocalDate cutoffInclusive,
            LocalDateTime availableBefore
    );
}
