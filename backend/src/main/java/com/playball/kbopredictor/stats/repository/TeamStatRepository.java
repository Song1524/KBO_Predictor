package com.playball.kbopredictor.stats.repository;

import com.playball.kbopredictor.stats.entity.TeamStat;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

    @Query("""
            select stat.statDate
            from TeamStat stat
            where stat.officialRank is not null
            group by stat.statDate
            having count(stat.id) = :teamCount
            order by stat.statDate desc
            """)
    List<LocalDate> findCompleteStandingSnapshotDates(
            @Param("teamCount") long teamCount,
            Pageable pageable
    );

    @EntityGraph(attributePaths = "team")
    List<TeamStat> findByStatDateAndOfficialRankIsNotNullOrderByOfficialRankAsc(
            LocalDate statDate
    );
}
