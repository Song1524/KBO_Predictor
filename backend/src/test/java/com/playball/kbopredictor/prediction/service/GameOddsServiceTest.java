package com.playball.kbopredictor.prediction.service;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.dto.GameOddsResponse;
import com.playball.kbopredictor.prediction.entity.GameOdds;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.repository.GameOddsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameOddsServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 12, 0);

    @Mock
    private GameOddsRepository gameOddsRepository;

    @Mock
    private GameRepository gameRepository;

    private GameOddsService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL);
        service = new GameOddsService(
                gameOddsRepository,
                gameRepository,
                new OddsCalculator(new BigDecimal("10.00")),
                clock
        );
    }

    @Test
    void bettingParticipationChangesRatesAndOdds() {
        Game game = TestEntities.game(
                10L,
                GameStatus.SCHEDULED,
                NOW.toLocalDate().plusDays(1),
                LocalTime.of(18, 30)
        );
        GameOdds odds = GameOdds.create(game, NOW);
        stubLockedGameAndOdds(game, odds);

        service.placeBet(game, PredictionOutcome.HOME_WIN, 60_000);
        service.placeBet(game, PredictionOutcome.DRAW, 10_000);
        service.placeBet(game, PredictionOutcome.AWAY_WIN, 30_000);

        GameOddsResponse response = service.getOddsByGameId(game.getId());

        assertThat(response.totalBetPoints()).isEqualTo(100_000);
        assertThat(response.homeWin().userBettingRate()).isEqualByComparingTo("60.00");
        assertThat(response.draw().userBettingRate()).isEqualByComparingTo("10.00");
        assertThat(response.awayWin().userBettingRate()).isEqualByComparingTo("30.00");
        assertThat(response.homeWin().odds()).isEqualByComparingTo("1.67");
        assertThat(response.draw().odds()).isEqualByComparingTo("10.00");
        assertThat(response.awayWin().odds()).isEqualByComparingTo("3.33");
        assertThat(response.bettingOpen()).isTrue();
    }

    @Test
    void ratesAndOddsUseWageredPointsRatherThanParticipantCount() {
        Game game = TestEntities.game(
                15L,
                GameStatus.SCHEDULED,
                NOW.toLocalDate().plusDays(1),
                LocalTime.of(18, 30)
        );
        GameOdds odds = GameOdds.create(game, NOW);
        stubLockedGameAndOdds(game, odds);

        service.placeBet(game, PredictionOutcome.HOME_WIN, 1_000);
        for (int participant = 0; participant < 5; participant++) {
            service.placeBet(game, PredictionOutcome.AWAY_WIN, 100);
        }

        GameOddsResponse response = service.getOddsByGameId(game.getId());

        assertThat(response.totalBetPoints()).isEqualTo(1_500);
        assertThat(response.homeWin().userBettingRate()).isEqualByComparingTo("66.67");
        assertThat(response.awayWin().userBettingRate()).isEqualByComparingTo("33.33");
        assertThat(response.homeWin().odds()).isEqualByComparingTo("1.50");
        assertThat(response.awayWin().odds()).isEqualByComparingTo("3.00");
    }

    @Test
    void oddsAreFinalizedExactlyAtDeadlineAndCannotChange() {
        Game game = TestEntities.game(
                20L,
                GameStatus.SCHEDULED,
                NOW.toLocalDate(),
                NOW.toLocalTime().plusMinutes(10)
        );
        GameOdds odds = GameOdds.create(game, NOW.minusHours(1));
        odds.addBet(PredictionOutcome.HOME_WIN, 100, NOW.minusHours(1));
        odds.addBet(PredictionOutcome.DRAW, 100, NOW.minusHours(1));
        stubLockedGameAndOdds(game, odds);

        GameOddsResponse response = service.getOddsByGameId(game.getId());

        assertThat(response.finalized()).isTrue();
        assertThat(response.bettingOpen()).isFalse();
        assertThat(response.homeWin().odds()).isEqualByComparingTo("2.00");
        assertThat(response.draw().odds()).isEqualByComparingTo("2.00");

        assertThatThrownBy(() ->
                service.placeBet(game, PredictionOutcome.AWAY_WIN, 100)
        ).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("예측 참여가 마감");
    }

    @Test
    void schedulerFinalizationDoesNothingBeforeDeadline() {
        Game game = TestEntities.game(
                30L,
                GameStatus.SCHEDULED,
                NOW.toLocalDate(),
                NOW.toLocalTime().plusMinutes(10).plusSeconds(1)
        );
        GameOdds odds = GameOdds.create(game, NOW.minusHours(1));
        odds.addBet(PredictionOutcome.HOME_WIN, 100, NOW.minusHours(1));
        stubLockedGameAndOdds(game, odds);

        assertThat(service.finalizeExpiredGame(game.getId())).isFalse();
        assertThat(odds.isFinalized()).isFalse();
    }

    @Test
    void schedulerFinalizesAllOutcomesAtDeadlineAndIsIdempotent() {
        Game game = TestEntities.game(
                40L,
                GameStatus.SCHEDULED,
                NOW.toLocalDate(),
                NOW.toLocalTime().plusMinutes(10)
        );
        GameOdds odds = GameOdds.create(game, NOW.minusHours(1));
        odds.addBet(PredictionOutcome.HOME_WIN, 60, NOW.minusMinutes(10));
        odds.addBet(PredictionOutcome.AWAY_WIN, 40, NOW.minusMinutes(5));
        stubLockedGameAndOdds(game, odds);

        assertThat(service.finalizeExpiredGame(game.getId())).isTrue();
        assertThat(odds.isFinalized()).isTrue();
        assertThat(odds.getFinalHomeWinOdds()).isEqualByComparingTo("1.67");
        assertThat(odds.getFinalDrawOdds()).isEqualByComparingTo("10.00");
        assertThat(odds.getFinalAwayWinOdds()).isEqualByComparingTo("2.50");
        assertThat(odds.getFinalizedAt()).isEqualTo(NOW);

        assertThat(service.finalizeExpiredGame(game.getId())).isFalse();
        assertThat(odds.getFinalHomeWinOdds()).isEqualByComparingTo("1.67");
        assertThatThrownBy(() ->
                odds.addBet(PredictionOutcome.DRAW, 100, NOW.plusMinutes(1))
        ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 최종 배당");
    }

    private void stubLockedGameAndOdds(Game game, GameOdds odds) {
        when(gameRepository.findByIdForUpdate(game.getId()))
                .thenReturn(Optional.of(game));
        when(gameOddsRepository.findByGameIdForUpdate(game.getId()))
                .thenReturn(Optional.of(odds));
    }
}
