package com.playball.kbopredictor.prediction.service;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.point.entity.PointHistory;
import com.playball.kbopredictor.point.entity.PointHistoryType;
import com.playball.kbopredictor.point.repository.PointHistoryRepository;
import com.playball.kbopredictor.point.service.PointService;
import com.playball.kbopredictor.prediction.dto.SettlementCorrectionRequest;
import com.playball.kbopredictor.prediction.dto.SettlementCorrectionResponse;
import com.playball.kbopredictor.prediction.dto.SettlementCorrectionStatus;
import com.playball.kbopredictor.prediction.entity.GameOdds;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.entity.PredictionSettlementStatus;
import com.playball.kbopredictor.prediction.entity.UserPrediction;
import com.playball.kbopredictor.prediction.repository.GameOddsRepository;
import com.playball.kbopredictor.prediction.repository.UserPredictionRepository;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettlementCorrectionServiceTest {

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 8, 13, 10, 0);

    @Mock
    private UserPredictionRepository userPredictionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameOddsRepository gameOddsRepository;

    @Mock
    private PointHistoryRepository pointHistoryRepository;

    private SettlementCorrectionService service;
    private User user;
    private Game game;
    private UserPrediction prediction;
    private GameOdds gameOdds;
    private AtomicReference<PointHistory> savedReward;

    @BeforeEach
    void setUp() {
        user = TestEntities.user(2L, 900);
        game = TestEntities.game(
                12L,
                GameStatus.FINISHED,
                LocalDate.of(2026, 8, 12),
                LocalTime.of(18, 30)
        );
        ReflectionTestUtils.setField(
                game,
                "externalGameId",
                "20260812SSHT0"
        );
        ReflectionTestUtils.setField(game, "homeScore", 7);
        ReflectionTestUtils.setField(game, "awayScore", 2);
        ReflectionTestUtils.setField(game, "result", GameResult.HOME_WIN);
        ReflectionTestUtils.setField(game, "winnerTeam", game.getHomeTeam());

        prediction = UserPrediction.create(
                user,
                game,
                PredictionOutcome.HOME_WIN,
                100
        );
        ReflectionTestUtils.setField(prediction, "id", 2L);
        prediction.settleLost();

        gameOdds = finalizedOdds("2.00");
        savedReward = new AtomicReference<>();

        lenient().when(userPredictionRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(prediction));
        lenient().when(userRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(user));
        lenient().when(gameRepository.findByIdForUpdate(12L))
                .thenReturn(Optional.of(game));
        lenient().when(gameOddsRepository.findByGameIdForUpdate(12L))
                .thenReturn(Optional.of(gameOdds));
        lenient().when(pointHistoryRepository.findByUserPredictionIdAndType(
                        2L,
                        PointHistoryType.PREDICTION_REWARD
                ))
                .thenAnswer(invocation -> Optional.ofNullable(savedReward.get()));
        lenient().when(pointHistoryRepository.save(any(PointHistory.class)))
                .thenAnswer(invocation -> {
                    PointHistory history = invocation.getArgument(0);
                    savedReward.set(history);
                    return history;
                });

        Clock clock = Clock.fixed(
                NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                ZoneId.of("Asia/Seoul")
        );
        PointService pointService = new PointService(
                pointHistoryRepository,
                userRepository,
                clock
        );
        service = new SettlementCorrectionService(
                userPredictionRepository,
                userRepository,
                gameRepository,
                gameOddsRepository,
                pointHistoryRepository,
                new OddsCalculator(new BigDecimal("10.00")),
                pointService
        );
    }

    @Test
    void correctsLostPredictionAndPaysTwoHundredPoints() {
        SettlementCorrectionResponse response = service.correct(2L, request());

        assertThat(response.status()).isEqualTo(SettlementCorrectionStatus.APPLIED);
        assertThat(response.rewardPoint()).isEqualTo(200);
        assertThat(response.currentPoint()).isEqualTo(1_100);
        assertThat(user.getPoint()).isEqualTo(1_100);
        assertThat(prediction.getIsCorrect()).isTrue();
        assertThat(prediction.getSettled()).isTrue();
        assertThat(prediction.getSettlementStatus())
                .isEqualTo(PredictionSettlementStatus.WON);

        PointHistory history = savedReward.get();
        assertThat(history).isNotNull();
        assertThat(history.getType()).isEqualTo(PointHistoryType.PREDICTION_REWARD);
        assertThat(history.getPointChange()).isEqualTo(200);
        assertThat(history.getBalanceAfter()).isEqualTo(1_100);
        assertThat(history.getUserPrediction()).isSameAs(prediction);
        assertThat(history.getDescription())
                .isEqualTo("관리자 정산 보정: 홈팀 승 예측 적중");
    }

    @Test
    void paysFromCurrentEightHundredPointBalanceAfterLaterBet() {
        ReflectionTestUtils.setField(user, "point", 800);

        SettlementCorrectionResponse response = service.correct(2L, request());

        assertThat(response.currentPoint()).isEqualTo(1_000);
        assertThat(user.getPoint()).isEqualTo(1_000);
        assertThat(savedReward.get().getPointChange()).isEqualTo(200);
        assertThat(savedReward.get().getBalanceAfter()).isEqualTo(1_000);
    }

    @Test
    void alwaysAddsPayoutToExecutionTimeBalanceAfterSeveralPointChanges() {
        user.changePoint(-100);
        user.changePoint(75);
        user.changePoint(-25);
        assertThat(user.getPoint()).isEqualTo(850);

        SettlementCorrectionResponse response = service.correct(2L, request());

        assertThat(response.currentPoint()).isEqualTo(1_050);
        assertThat(savedReward.get().getBalanceAfter()).isEqualTo(1_050);
    }

    @Test
    void repeatedRequestReturnsAlreadyAppliedWithoutPayingAgain() {
        ReflectionTestUtils.setField(user, "point", 800);

        SettlementCorrectionResponse first = service.correct(2L, request());
        SettlementCorrectionResponse second = service.correct(2L, request());

        assertThat(first.status()).isEqualTo(SettlementCorrectionStatus.APPLIED);
        assertThat(second.status())
                .isEqualTo(SettlementCorrectionStatus.ALREADY_APPLIED);
        assertThat(second.currentPoint()).isEqualTo(1_000);
        assertThat(user.getPoint()).isEqualTo(1_000);
        verify(pointHistoryRepository, times(1)).save(any(PointHistory.class));
    }

    @Test
    void gameResultMismatchStopsBeforeAnyChange() {
        ReflectionTestUtils.setField(game, "result", GameResult.AWAY_WIN);

        assertCorrectionConflict("경기 결과");
    }

    @Test
    void finalOddsMismatchStopsBeforeAnyChange() {
        gameOdds = finalizedOdds("1.99");
        when(gameOddsRepository.findByGameIdForUpdate(12L))
                .thenReturn(Optional.of(gameOdds));

        assertCorrectionConflict("최종 HOME 배당");
    }

    @Test
    void existingRewardWithLostPredictionIsRejectedAsInconsistent() {
        savedReward.set(rewardHistory(200, 1_100));

        assertCorrectionConflict("reward 이력은 있지만");
    }

    @Test
    void correctionForPredictionTwoDoesNotChangePredictionOne() {
        UserPrediction samsungPrediction = UserPrediction.create(
                TestEntities.user(1L, 900),
                game,
                PredictionOutcome.AWAY_WIN,
                100
        );
        ReflectionTestUtils.setField(samsungPrediction, "id", 1L);
        samsungPrediction.settleLost();

        service.correct(2L, request());

        assertThat(samsungPrediction.getIsCorrect()).isFalse();
        assertThat(samsungPrediction.getSettled()).isTrue();
        assertThat(samsungPrediction.getSettlementStatus())
                .isEqualTo(PredictionSettlementStatus.LOST);
        verify(userPredictionRepository, never()).findByIdForUpdate(1L);
    }

    @Test
    void laterPredictionAndItsBetHistoryRemainUnchanged() {
        ReflectionTestUtils.setField(user, "point", 800);
        Game laterGame = TestEntities.game(
                13L,
                GameStatus.SCHEDULED,
                LocalDate.of(2026, 8, 13),
                LocalTime.of(18, 30)
        );
        UserPrediction laterPrediction = UserPrediction.create(
                user,
                laterGame,
                PredictionOutcome.AWAY_WIN,
                100
        );
        ReflectionTestUtils.setField(laterPrediction, "id", 3L);
        PointHistory laterBet = PointHistory.create(
                user,
                laterGame,
                laterPrediction,
                -100,
                800,
                PointHistoryType.PREDICTION_BET,
                "후속 경기 예측 참여",
                NOW.minusHours(1)
        );

        service.correct(2L, request());

        assertThat(laterPrediction.getSettled()).isFalse();
        assertThat(laterPrediction.getIsCorrect()).isNull();
        assertThat(laterPrediction.getSettlementStatus())
                .isEqualTo(PredictionSettlementStatus.PENDING);
        assertThat(laterBet.getType()).isEqualTo(PointHistoryType.PREDICTION_BET);
        assertThat(laterBet.getPointChange()).isEqualTo(-100);
        assertThat(laterBet.getBalanceAfter()).isEqualTo(800);
        verify(userPredictionRepository, never()).findByIdForUpdate(3L);
    }

    @Test
    void anyPredictionIdOtherThanTwoIsRejectedBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.correct(1L, request()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("prediction_id=2 전용");

        verifyNoInteractions(
                userPredictionRepository,
                userRepository,
                gameRepository,
                gameOddsRepository
        );
    }

    private void assertCorrectionConflict(String message) {
        int pointBefore = user.getPoint();

        assertThatThrownBy(() -> service.correct(2L, request()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(message);
        assertThat(user.getPoint()).isEqualTo(pointBefore);
        assertThat(prediction.getIsCorrect()).isFalse();
        assertThat(prediction.getSettlementStatus())
                .isEqualTo(PredictionSettlementStatus.LOST);
        verify(pointHistoryRepository, never()).save(any(PointHistory.class));
    }

    private SettlementCorrectionRequest request() {
        return new SettlementCorrectionRequest(
                2L,
                "20260812SSHT0",
                PredictionOutcome.HOME_WIN,
                100,
                new BigDecimal("2.00")
        );
    }

    private GameOdds finalizedOdds(String homeOdds) {
        GameOdds odds = GameOdds.create(game, NOW.minusHours(2));
        odds.finalizeOdds(
                new BigDecimal(homeOdds),
                new BigDecimal("6.00"),
                new BigDecimal("2.50"),
                NOW.minusHours(1)
        );
        return odds;
    }

    private PointHistory rewardHistory(int pointChange, int balanceAfter) {
        return PointHistory.create(
                user,
                game,
                prediction,
                pointChange,
                balanceAfter,
                PointHistoryType.PREDICTION_REWARD,
                "관리자 정산 보정: 홈팀 승 예측 적중",
                NOW
        );
    }
}
