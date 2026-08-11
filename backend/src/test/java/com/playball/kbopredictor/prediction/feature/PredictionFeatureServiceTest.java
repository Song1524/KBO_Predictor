package com.playball.kbopredictor.prediction.feature;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.stats.entity.StartingPitcher;
import com.playball.kbopredictor.stats.entity.StartingPitcherSide;
import com.playball.kbopredictor.stats.entity.TeamRecentFormValues;
import com.playball.kbopredictor.stats.entity.TeamStat;
import com.playball.kbopredictor.stats.repository.PitcherStatRepository;
import com.playball.kbopredictor.stats.repository.StartingPitcherRepository;
import com.playball.kbopredictor.stats.repository.TeamStatRepository;
import com.playball.kbopredictor.team.entity.Team;
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
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PredictionFeatureServiceTest {

    private static final LocalDate GAME_DATE = LocalDate.of(2026, 8, 12);
    private static final LocalDateTime GAME_START = LocalDateTime.of(
            GAME_DATE,
            LocalTime.of(18, 30)
    );

    @Mock
    private GameRepository gameRepository;

    @Mock
    private TeamStatRepository teamStatRepository;

    @Mock
    private StartingPitcherRepository startingPitcherRepository;

    @Mock
    private PitcherStatRepository pitcherStatRepository;

    private PredictionFeatureService service;
    private Team home;
    private Team away;
    private Game game;

    @BeforeEach
    void setUp() {
        service = new PredictionFeatureService(
                gameRepository,
                teamStatRepository,
                startingPitcherRepository,
                pitcherStatRepository
        );
        home = team(1L, "LG", "LG 트윈스");
        away = team(2L, "HH", "한화 이글스");
        game = Game.createCollected(
                "20260812HHLG0",
                2026,
                GAME_DATE,
                LocalTime.of(18, 30),
                home,
                away,
                "잠실",
                GameStatus.SCHEDULED,
                null,
                null,
                null,
                null,
                null,
                GAME_START.minusDays(2)
        );
        ReflectionTestUtils.setField(game, "id", 10L);
        when(gameRepository.findById(10L)).thenReturn(Optional.of(game));
    }

    @Test
    void usesOnlySnapshotsDatedAndCollectedBeforeGameStart() {
        TeamStat homeStat = stat(home, GAME_DATE.minusDays(1), GAME_START.minusHours(10));
        TeamStat awayStat = stat(away, GAME_DATE.minusDays(1), GAME_START.minusHours(10));
        when(teamStatRepository
                .findTopByTeamIdAndStatDateBeforeAndCollectedAtBeforeOrderByStatDateDesc(
                        1L, GAME_DATE, GAME_START
                )).thenReturn(Optional.of(homeStat));
        when(teamStatRepository
                .findTopByTeamIdAndStatDateBeforeAndCollectedAtBeforeOrderByStatDateDesc(
                        2L, GAME_DATE, GAME_START
                )).thenReturn(Optional.of(awayStat));
        when(startingPitcherRepository.findByGameIdAndSide(
                10L, StartingPitcherSide.HOME
        )).thenReturn(Optional.empty());
        when(startingPitcherRepository.findByGameIdAndSide(
                10L, StartingPitcherSide.AWAY
        )).thenReturn(Optional.empty());

        PredictionFeatures features = service.build(10L);

        assertThat(features.home().teamStatsAvailable()).isTrue();
        assertThat(features.home().teamStatDate())
                .isEqualTo(GAME_DATE.minusDays(1));
        assertThat(features.home().recent5AvgRuns())
                .isEqualByComparingTo("5.20");
        assertThat(features.home().venueWinRate())
                .isEqualByComparingTo("0.667");
        assertThat(features.home().startingPitcher()).isNull();
        verify(teamStatRepository)
                .findTopByTeamIdAndStatDateBeforeAndCollectedAtBeforeOrderByStatDateDesc(
                        1L, GAME_DATE, GAME_START
                );
    }

    @Test
    void unavailableStatsAndPitcherAnnouncedAfterStartStayExplicitlyMissing() {
        when(teamStatRepository
                .findTopByTeamIdAndStatDateBeforeAndCollectedAtBeforeOrderByStatDateDesc(
                        1L, GAME_DATE, GAME_START
                )).thenReturn(Optional.empty());
        when(teamStatRepository
                .findTopByTeamIdAndStatDateBeforeAndCollectedAtBeforeOrderByStatDateDesc(
                        2L, GAME_DATE, GAME_START
                )).thenReturn(Optional.empty());
        StartingPitcher late = instantiate(StartingPitcher.class);
        ReflectionTestUtils.setField(late, "firstCollectedAt", GAME_START.plusMinutes(1));
        when(startingPitcherRepository.findByGameIdAndSide(
                10L, StartingPitcherSide.HOME
        )).thenReturn(Optional.of(late));
        when(startingPitcherRepository.findByGameIdAndSide(
                10L, StartingPitcherSide.AWAY
        )).thenReturn(Optional.empty());

        PredictionFeatures features = service.build(10L);

        assertThat(features.home().teamStatsAvailable()).isFalse();
        assertThat(features.home().seasonWinRate()).isNull();
        assertThat(features.home().startingPitcher()).isNull();
        verify(pitcherStatRepository, never())
                .findTopByPlayerIdAndStatDateBeforeAndCollectedAtBeforeOrderByStatDateDesc(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()
                );
    }

    private TeamStat stat(
            Team team,
            LocalDate statDate,
            LocalDateTime collectedAt
    ) {
        TeamStat stat = TeamStat.create(team, 2026, statDate);
        stat.update(
                60,
                40,
                2,
                new BigDecimal("0.600"),
                6,
                3,
                1,
                30,
                15,
                1,
                30,
                25,
                1,
                new BigDecimal("0.280"),
                new BigDecimal("3.50"),
                new TeamRecentFormValues(
                        new BigDecimal("0.750"),
                        new BigDecimal("0.667"),
                        new BigDecimal("5.20"),
                        new BigDecimal("3.40"),
                        new BigDecimal("4.80"),
                        new BigDecimal("3.80")
                ),
                collectedAt
        );
        return stat;
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
