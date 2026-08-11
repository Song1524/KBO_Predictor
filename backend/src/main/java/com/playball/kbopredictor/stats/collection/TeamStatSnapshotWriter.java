package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.stats.entity.TeamStat;
import com.playball.kbopredictor.stats.repository.TeamStatRepository;
import com.playball.kbopredictor.stats.service.TeamRecentForm;
import com.playball.kbopredictor.stats.service.TeamRecentFormCalculator;
import com.playball.kbopredictor.team.entity.Team;
import com.playball.kbopredictor.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TeamStatSnapshotWriter {

    private final TeamRepository teamRepository;
    private final TeamStatRepository teamStatRepository;
    private final TeamRecentFormCalculator recentFormCalculator;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TeamStatWriteResult upsert(
            CollectedTeamStat collected,
            Integer season,
            LocalDate statDate,
            LocalDateTime collectedAt
    ) {
        Team team = teamRepository
                .findByKboTeamCodeForUpdate(collected.teamCode())
                .orElseThrow(() -> new PregameDataCollectionException(
                        "DB에서 KBO 팀 코드를 찾을 수 없습니다: "
                                + collected.teamCode()
                ));
        TeamRecentForm form = recentFormCalculator.calculate(
                team.getId(),
                statDate
        );

        TeamStat teamStat = teamStatRepository
                .findByTeamIdAndSeasonAndStatDate(
                        team.getId(),
                        season,
                        statDate
                )
                .orElse(null);
        boolean inserted = teamStat == null;
        if (inserted) {
            teamStat = TeamStat.create(team, season, statDate);
        }

        OfficialTeamStanding standing = collected.standing();
        teamStat.update(
                standing.wins(),
                standing.losses(),
                standing.draws(),
                standing.winRate(),
                standing.recent10Wins(),
                standing.recent10Losses(),
                standing.recent10Draws(),
                standing.homeWins(),
                standing.homeLosses(),
                standing.homeDraws(),
                standing.awayWins(),
                standing.awayLosses(),
                standing.awayDraws(),
                collected.battingAverage(),
                collected.era(),
                form.values(),
                collectedAt
        );
        teamStatRepository.save(teamStat);
        return new TeamStatWriteResult(inserted);
    }
}
