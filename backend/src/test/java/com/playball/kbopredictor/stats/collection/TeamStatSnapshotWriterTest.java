package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.stats.entity.TeamRecentFormValues;
import com.playball.kbopredictor.stats.entity.TeamStat;
import com.playball.kbopredictor.stats.repository.TeamStatRepository;
import com.playball.kbopredictor.stats.service.TeamRecentForm;
import com.playball.kbopredictor.stats.service.TeamRecentFormCalculator;
import com.playball.kbopredictor.team.entity.Team;
import com.playball.kbopredictor.team.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamStatSnapshotWriterTest {

    private static final LocalDate STAT_DATE = LocalDate.of(2026, 8, 13);
    private static final LocalDateTime COLLECTED_AT =
            LocalDateTime.of(2026, 8, 14, 6, 20);

    @Mock
    private TeamRepository teamRepository;
    @Mock
    private TeamStatRepository teamStatRepository;
    @Mock
    private TeamRecentFormCalculator recentFormCalculator;

    private TeamStatSnapshotWriter writer;
    private Team kt;

    @BeforeEach
    void setUp() {
        writer = new TeamStatSnapshotWriter(
                teamRepository,
                teamStatRepository,
                recentFormCalculator
        );
        kt = team(1L, "KT", "KT 위즈");
        when(teamRepository.findByKboTeamCodeForUpdate("KT"))
                .thenReturn(Optional.of(kt));
        when(recentFormCalculator.calculate(kt.getId(), STAT_DATE))
                .thenReturn(new TeamRecentForm(
                        0,
                        0,
                        0,
                        new TeamRecentFormValues(null, null, null, null, null, null)
                ));
        when(teamStatRepository.save(any(TeamStat.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void sameDateRecollectionUpdatesOfficialStandingSnapshot() {
        CollectedTeamStat first = collected(1, 100, 60, 38, 2, "0.612", "0.0", "2패");
        when(teamStatRepository.findByTeamIdAndSeasonAndStatDate(
                kt.getId(), 2026, STAT_DATE
        )).thenReturn(Optional.empty());

        TeamStatWriteResult inserted = writer.upsert(
                first, 2026, STAT_DATE, COLLECTED_AT
        );
        assertThat(inserted.inserted()).isTrue();

        org.mockito.ArgumentCaptor<TeamStat> captor =
                org.mockito.ArgumentCaptor.forClass(TeamStat.class);
        org.mockito.Mockito.verify(teamStatRepository).save(captor.capture());
        TeamStat saved = captor.getValue();

        CollectedTeamStat updated = collected(2, 101, 60, 39, 2, "0.606", "1.0", "1승");
        when(teamStatRepository.findByTeamIdAndSeasonAndStatDate(
                kt.getId(), 2026, STAT_DATE
        )).thenReturn(Optional.of(saved));

        TeamStatWriteResult result = writer.upsert(
                updated, 2026, STAT_DATE, COLLECTED_AT.plusHours(1)
        );

        assertThat(result.inserted()).isFalse();
        assertThat(saved.getOfficialRank()).isEqualTo(2);
        assertThat(saved.getGamesPlayed()).isEqualTo(101);
        assertThat(saved.getWins()).isEqualTo(60);
        assertThat(saved.getLosses()).isEqualTo(39);
        assertThat(saved.getDraws()).isEqualTo(2);
        assertThat(saved.getWinRate()).isEqualByComparingTo("0.606");
        assertThat(saved.getGamesBehind()).isEqualByComparingTo("1.0");
        assertThat(saved.getStreak()).isEqualTo("1승");
    }

    private CollectedTeamStat collected(
            int rank,
            int games,
            int wins,
            int losses,
            int draws,
            String winRate,
            String gamesBehind,
            String streak
    ) {
        return new CollectedTeamStat(
                new OfficialTeamStanding(
                        rank,
                        "KT",
                        games,
                        wins,
                        losses,
                        draws,
                        new BigDecimal(winRate),
                        new BigDecimal(gamesBehind),
                        streak,
                        7, 3, 0,
                        29, 19, 1,
                        31, 19, 1
                ),
                new BigDecimal("0.282"),
                new BigDecimal("4.52")
        );
    }

    private Team team(Long id, String code, String name) {
        Team team = instantiate(Team.class);
        ReflectionTestUtils.setField(team, "id", id);
        ReflectionTestUtils.setField(team, "kboTeamCode", code);
        ReflectionTestUtils.setField(team, "name", name);
        return team;
    }

    private <T> T instantiate(Class<T> type) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
