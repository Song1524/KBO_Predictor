package com.playball.kbopredictor.stats.service;

import com.playball.kbopredictor.stats.dto.TeamStandingResponse;
import com.playball.kbopredictor.stats.entity.TeamRecentFormValues;
import com.playball.kbopredictor.stats.entity.TeamStat;
import com.playball.kbopredictor.stats.repository.TeamStatRepository;
import com.playball.kbopredictor.team.entity.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StandingServiceTest {

    private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 8, 13);

    @Mock
    private TeamStatRepository teamStatRepository;

    @Test
    void returnsLatestCompleteTenTeamSnapshotInOfficialRankOrder() {
        StandingService service = new StandingService(teamStatRepository);
        List<TeamStat> snapshot = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(this::standing)
                .toList();
        when(teamStatRepository.findCompleteStandingSnapshotDates(
                eq(10L), isA(Pageable.class)
        )).thenReturn(List.of(SNAPSHOT_DATE));
        when(teamStatRepository
                .findByStatDateAndOfficialRankIsNotNullOrderByOfficialRankAsc(
                        SNAPSHOT_DATE
                )).thenReturn(snapshot);

        List<TeamStandingResponse> response = service.getCurrentStandings();

        assertThat(response).hasSize(10);
        assertThat(response).extracting(TeamStandingResponse::rank)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(response).allSatisfy(standing ->
                assertThat(standing.wins() + standing.losses() + standing.draws())
                        .isEqualTo(standing.games())
        );
        assertThat(response.getFirst().teamName()).isEqualTo("팀 1");
        assertThat(response.getFirst().gamesBehind()).isEqualByComparingTo("0.0");
    }

    @Test
    void returnsEmptyWhenNoCompleteSnapshotExists() {
        StandingService service = new StandingService(teamStatRepository);
        when(teamStatRepository.findCompleteStandingSnapshotDates(
                eq(10L), isA(Pageable.class)
        )).thenReturn(List.of());

        assertThat(service.getCurrentStandings()).isEmpty();
    }

    @Test
    void preservesAnOfficialTiedRankInsteadOfRecalculatingRank() {
        StandingService service = new StandingService(teamStatRepository);
        List<TeamStat> snapshot = new java.util.ArrayList<>(
                java.util.stream.IntStream.rangeClosed(1, 10)
                        .mapToObj(this::standing)
                        .toList()
        );
        snapshot.get(2).updateOfficialStanding(
                2,
                100,
                new BigDecimal("1.0"),
                "1승"
        );
        when(teamStatRepository.findCompleteStandingSnapshotDates(
                eq(10L), isA(Pageable.class)
        )).thenReturn(List.of(SNAPSHOT_DATE));
        when(teamStatRepository
                .findByStatDateAndOfficialRankIsNotNullOrderByOfficialRankAsc(
                        SNAPSHOT_DATE
                )).thenReturn(snapshot);

        assertThat(service.getCurrentStandings())
                .extracting(TeamStandingResponse::rank)
                .containsExactly(1, 2, 2, 4, 5, 6, 7, 8, 9, 10);
    }

    private TeamStat standing(int rank) {
        Team team = instantiate(Team.class);
        ReflectionTestUtils.setField(team, "id", (long) rank);
        ReflectionTestUtils.setField(team, "name", "팀 " + rank);
        TeamStat stat = TeamStat.create(team, 2026, SNAPSHOT_DATE);
        stat.updateOfficialStanding(
                rank,
                100,
                BigDecimal.valueOf(rank - 1L).setScale(1),
                rank % 2 == 0 ? "1패" : "1승"
        );
        stat.update(
                50,
                48,
                2,
                new BigDecimal("0.510"),
                5, 5, 0,
                25, 24, 1,
                25, 24, 1,
                null,
                null,
                new TeamRecentFormValues(null, null, null, null, null, null),
                LocalDateTime.of(2026, 8, 14, 6, 20)
        );
        return stat;
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
