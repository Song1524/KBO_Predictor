package com.playball.kbopredictor.prediction.service;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.point.service.PointService;
import com.playball.kbopredictor.prediction.dto.PredictionSettlementResponse;
import com.playball.kbopredictor.prediction.entity.GameOdds;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.entity.PredictionSettlementStatus;
import com.playball.kbopredictor.prediction.entity.UserPrediction;
import com.playball.kbopredictor.prediction.repository.UserPredictionRepository;
import com.playball.kbopredictor.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PredictionSettlementServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 20, 0);

    @Mock
    private GameRepository gameRepository;

    @Mock
    private UserPredictionRepository userPredictionRepository;

    @Mock
    private GameOddsService gameOddsService;

    @Mock
    private PointService pointService;

    private OddsCalculator oddsCalculator;
    private PredictionSettlementService service;

    @BeforeEach
    void setUp() {
        oddsCalculator = new OddsCalculator(new BigDecimal("10.00"));
        service = new PredictionSettlementService(
                gameRepository,
                userPredictionRepository,
                gameOddsService,
                oddsCalculator,
                pointService
        );

        lenient().doAnswer(invocation -> {
            User user = invocation.getArgument(0);
            int payout = invocation.getArgument(2);
            user.changePoint(payout);
            return null;
        }).when(pointService).rewardPrediction(
                any(User.class),
                any(UserPrediction.class),
                anyInt()
        );
        lenient().doAnswer(invocation -> {
            User user = invocation.getArgument(0);
            UserPrediction prediction = invocation.getArgument(1);
            user.changePoint(prediction.getPointAmount());
            return null;
        }).when(pointService).refundCancelledGame(
                any(User.class),
                any(UserPrediction.class)
        );
    }

    @ParameterizedTest
    @EnumSource(PredictionOutcome.class)
    void settlesHomeDrawAndAwayWithFinalOdds(PredictionOutcome outcome) {
        GameResult result = GameResult.valueOf(outcome.name());
        Game game = finishedGame(100L, result);
        User user = TestEntities.user(1L, 900);
        UserPrediction prediction = UserPrediction.create(user, game, outcome, 100);
        GameOdds finalOdds = finalizedOdds(game);

        when(gameRepository.findByIdForUpdate(game.getId()))
                .thenReturn(Optional.of(game));
        when(gameOddsService.finalizeForSettlement(game)).thenReturn(finalOdds);
        when(userPredictionRepository.findByGameIdAndSettledFalse(game.getId()))
                .thenReturn(List.of(prediction));

        PredictionSettlementResponse response = service.settleGame(game.getId());

        int expectedPayout = oddsCalculator.calculatePayout(
                100,
                finalOdds.getFinalOdds(outcome)
        );
        assertThat(response.correctCount()).isEqualTo(1);
        assertThat(response.refundedCount()).isZero();
        assertThat(response.totalPaidPoints()).isEqualTo(expectedPayout);
        assertThat(user.getPoint()).isEqualTo(900 + expectedPayout);
        assertThat(prediction.getSettlementStatus())
                .isEqualTo(PredictionSettlementStatus.WON);
        verify(pointService).rewardPrediction(user, prediction, expectedPayout);
    }

    @Test
    void refundsAllPointsForCancelledGame() {
        Game game = TestEntities.game(
                200L,
                GameStatus.CANCELLED,
                LocalDate.of(2026, 8, 10),
                LocalTime.of(18, 30)
        );
        User user = TestEntities.user(1L, 900);
        UserPrediction prediction = UserPrediction.create(
                user,
                game,
                PredictionOutcome.DRAW,
                100
        );

        when(gameRepository.findByIdForUpdate(game.getId()))
                .thenReturn(Optional.of(game));
        when(gameOddsService.finalizeForSettlement(game))
                .thenReturn(finalizedOdds(game));
        when(userPredictionRepository.findByGameIdAndSettledFalse(game.getId()))
                .thenReturn(List.of(prediction));

        PredictionSettlementResponse response = service.settleGame(game.getId());

        assertThat(response.cancelled()).isTrue();
        assertThat(response.refundedCount()).isEqualTo(1);
        assertThat(response.correctCount()).isZero();
        assertThat(response.totalPaidPoints()).isEqualTo(100);
        assertThat(user.getPoint()).isEqualTo(1_000);
        assertThat(prediction.getSettlementStatus())
                .isEqualTo(PredictionSettlementStatus.REFUNDED);
        assertThat(prediction.getIsCorrect()).isNull();
        verify(pointService).refundCancelledGame(user, prediction);
    }

    @Test
    void repeatedSettlementDoesNotPayTwice() {
        Game game = finishedGame(300L, GameResult.DRAW);
        User user = TestEntities.user(1L, 900);
        UserPrediction prediction = UserPrediction.create(
                user,
                game,
                PredictionOutcome.DRAW,
                100
        );
        GameOdds finalOdds = finalizedOdds(game);

        when(gameRepository.findByIdForUpdate(game.getId()))
                .thenReturn(Optional.of(game));
        when(gameOddsService.finalizeForSettlement(game)).thenReturn(finalOdds);
        when(userPredictionRepository.findByGameIdAndSettledFalse(game.getId()))
                .thenReturn(List.of(prediction), List.of());

        PredictionSettlementResponse first = service.settleGame(game.getId());
        int pointAfterFirstSettlement = user.getPoint();
        PredictionSettlementResponse second = service.settleGame(game.getId());

        assertThat(first.correctCount()).isEqualTo(1);
        assertThat(second.totalCount()).isZero();
        assertThat(second.totalPaidPoints()).isZero();
        assertThat(user.getPoint()).isEqualTo(pointAfterFirstSettlement);
        verify(pointService, times(1)).rewardPrediction(
                eq(user),
                eq(prediction),
                anyInt()
        );
    }

    @Test
    void incorrectPredictionCreatesNoPointHistory() {
        Game game = finishedGame(400L, GameResult.HOME_WIN);
        User user = TestEntities.user(1L, 900);
        UserPrediction prediction = UserPrediction.create(
                user,
                game,
                PredictionOutcome.AWAY_WIN,
                100
        );

        when(gameRepository.findByIdForUpdate(game.getId()))
                .thenReturn(Optional.of(game));
        when(gameOddsService.finalizeForSettlement(game))
                .thenReturn(finalizedOdds(game));
        when(userPredictionRepository.findByGameIdAndSettledFalse(game.getId()))
                .thenReturn(List.of(prediction));

        PredictionSettlementResponse response = service.settleGame(game.getId());

        assertThat(response.incorrectCount()).isEqualTo(1);
        assertThat(response.totalPaidPoints()).isZero();
        assertThat(user.getPoint()).isEqualTo(900);
        verifyNoInteractions(pointService);
    }

    @Test
    void repeatedCancellationDoesNotRefundOrCreateHistoryTwice() {
        Game game = TestEntities.game(
                500L,
                GameStatus.CANCELLED,
                LocalDate.of(2026, 8, 10),
                LocalTime.of(18, 30)
        );
        User user = TestEntities.user(1L, 900);
        UserPrediction prediction = UserPrediction.create(
                user,
                game,
                PredictionOutcome.HOME_WIN,
                100
        );

        when(gameRepository.findByIdForUpdate(game.getId()))
                .thenReturn(Optional.of(game));
        when(gameOddsService.finalizeForSettlement(game))
                .thenReturn(finalizedOdds(game));
        when(userPredictionRepository.findByGameIdAndSettledFalse(game.getId()))
                .thenReturn(List.of(prediction), List.of());

        service.settleGame(game.getId());
        int pointAfterFirstRefund = user.getPoint();
        PredictionSettlementResponse second = service.settleGame(game.getId());

        assertThat(second.totalCount()).isZero();
        assertThat(second.totalPaidPoints()).isZero();
        assertThat(user.getPoint()).isEqualTo(pointAfterFirstRefund);
        verify(pointService, times(1)).refundCancelledGame(user, prediction);
    }

    private Game finishedGame(Long id, GameResult result) {
        Game game = TestEntities.game(
                id,
                GameStatus.FINISHED,
                LocalDate.of(2026, 8, 10),
                LocalTime.of(18, 30)
        );
        TestEntities.setResult(game, result);
        return game;
    }

    private GameOdds finalizedOdds(Game game) {
        GameOdds odds = GameOdds.create(game, NOW.minusHours(2));
        odds.finalizeOdds(
                new BigDecimal("2.50"),
                new BigDecimal("6.50"),
                new BigDecimal("3.20"),
                NOW.minusHours(1)
        );
        return odds;
    }
}
