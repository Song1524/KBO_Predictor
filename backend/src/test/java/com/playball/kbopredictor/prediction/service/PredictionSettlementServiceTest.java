package com.playball.kbopredictor.prediction.service;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.point.service.PointService;
import com.playball.kbopredictor.point.service.UserPointLockService;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PredictionSettlementServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 20, 0);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(
            NOW.atZone(SEOUL).toInstant(),
            ZoneOffset.UTC
    );

    @Mock
    private GameRepository gameRepository;

    @Mock
    private UserPredictionRepository userPredictionRepository;

    @Mock
    private UserPointLockService userPointLockService;

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
                userPointLockService,
                gameOddsService,
                oddsCalculator,
                pointService,
                CLOCK
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
        lockUser(user);

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
        assertThat(prediction.getSettledAt()).isEqualTo(NOW);
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
        User lockedUser = TestEntities.user(1L, 950);
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
        lockUser(lockedUser);

        PredictionSettlementResponse response = service.settleGame(game.getId());

        assertThat(response.cancelled()).isTrue();
        assertThat(response.refundedCount()).isEqualTo(1);
        assertThat(response.correctCount()).isZero();
        assertThat(response.totalPaidPoints()).isEqualTo(100);
        assertThat(user.getPoint()).isEqualTo(900);
        assertThat(lockedUser.getPoint()).isEqualTo(1_050);
        assertThat(prediction.getSettlementStatus())
                .isEqualTo(PredictionSettlementStatus.REFUNDED);
        assertThat(prediction.getIsCorrect()).isNull();
        assertThat(prediction.getSettledAt()).isEqualTo(NOW);
        verify(pointService).refundCancelledGame(lockedUser, prediction);
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
        lockUser(user);

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
        assertThat(prediction.getSettledAt()).isEqualTo(NOW);
        verifyNoInteractions(pointService);
    }

    @Test
    void firstSettlementTimestampCannotBeOverwritten() {
        Game game = finishedGame(405L, GameResult.HOME_WIN);
        User user = TestEntities.user(1L, 900);
        UserPrediction prediction = UserPrediction.create(
                user,
                game,
                PredictionOutcome.AWAY_WIN,
                100
        );

        prediction.settleLost(NOW);

        assertThatThrownBy(() -> prediction.refund(NOW.plusDays(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Prediction is already settled");
        assertThat(prediction.getSettledAt()).isEqualTo(NOW);
        assertThat(prediction.getSettlementStatus())
                .isEqualTo(PredictionSettlementStatus.LOST);
    }

    @Test
    void finishedGameWithoutScoreCannotBeSettled() {
        Game game = finishedGame(410L, GameResult.HOME_WIN);
        ReflectionTestUtils.setField(game, "homeScore", null);
        ReflectionTestUtils.setField(game, "awayScore", null);
        when(gameRepository.findByIdForUpdate(game.getId()))
                .thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service.settleGame(game.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Final score is not available");
        verifyNoInteractions(gameOddsService, pointService);
    }

    @Test
    void inconsistentFinalScoreAndResultCannotBeSettled() {
        Game game = finishedGame(420L, GameResult.HOME_WIN);
        ReflectionTestUtils.setField(game, "homeScore", 2);
        ReflectionTestUtils.setField(game, "awayScore", 7);
        when(gameRepository.findByIdForUpdate(game.getId()))
                .thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service.settleGame(game.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Final score and game result do not match");
        verifyNoInteractions(gameOddsService, pointService);
    }

    @Test
    void confirmedZeroZeroIsAValidDrawAndSettlesNormally() {
        Game game = finishedGame(430L, GameResult.DRAW);
        ReflectionTestUtils.setField(game, "homeScore", 0);
        ReflectionTestUtils.setField(game, "awayScore", 0);
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
        lockUser(user);

        PredictionSettlementResponse response = service.settleGame(game.getId());

        assertThat(response.correctCount()).isEqualTo(1);
        assertThat(prediction.getSettlementStatus())
                .isEqualTo(PredictionSettlementStatus.WON);
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
        lockUser(user);

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

    private void lockUser(User user) {
        when(userPointLockService.findByIdForUpdate(user.getId()))
                .thenReturn(user);
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
