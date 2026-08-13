package com.playball.kbopredictor.point.service;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.point.entity.PointHistory;
import com.playball.kbopredictor.point.entity.PointHistoryType;
import com.playball.kbopredictor.point.repository.PointHistoryRepository;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.entity.UserPrediction;
import com.playball.kbopredictor.prediction.service.TestEntities;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PointServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 8, 10, 12, 0);

    @Mock
    private PointHistoryRepository pointHistoryRepository;

    @Mock
    private UserRepository userRepository;

    private PointService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL);
        service = new PointService(
                pointHistoryRepository,
                userRepository,
                clock
        );
        when(pointHistoryRepository.save(any(PointHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void predictionBetRecordsNegativeChangeAndActualBalance() {
        User user = TestEntities.user(1L, 1_000);
        UserPrediction prediction = prediction(
                user,
                PredictionOutcome.HOME_WIN,
                100
        );

        service.useForPrediction(user, prediction);

        PointHistory history = savedHistory();
        assertThat(user.getPoint()).isEqualTo(900);
        assertThat(history.getType()).isEqualTo(PointHistoryType.PREDICTION_BET);
        assertThat(history.getPointChange()).isEqualTo(-100);
        assertThat(history.getBalanceAfter()).isEqualTo(user.getPoint());
        assertThat(history.getDescription()).isEqualTo("홈팀 승 예측 참여");
        assertThat(history.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void predictionRewardRecordsPositiveChangeAndActualBalance() {
        User user = TestEntities.user(1L, 900);
        UserPrediction prediction = prediction(
                user,
                PredictionOutcome.HOME_WIN,
                100
        );

        service.rewardPrediction(user, prediction, 230);

        PointHistory history = savedHistory();
        assertThat(user.getPoint()).isEqualTo(1_130);
        assertThat(history.getType())
                .isEqualTo(PointHistoryType.PREDICTION_REWARD);
        assertThat(history.getPointChange()).isEqualTo(230);
        assertThat(history.getBalanceAfter()).isEqualTo(user.getPoint());
        assertThat(history.getDescription()).isEqualTo("홈팀 승 예측 적중");
    }

    @Test
    void settlementCorrectionRecordsExplicitPredictionReward() {
        User user = TestEntities.user(2L, 900);
        UserPrediction prediction = prediction(
                user,
                PredictionOutcome.HOME_WIN,
                100
        );

        service.rewardSettlementCorrection(user, prediction, 200);

        PointHistory history = savedHistory();
        assertThat(user.getPoint()).isEqualTo(1_100);
        assertThat(history.getType())
                .isEqualTo(PointHistoryType.PREDICTION_REWARD);
        assertThat(history.getPointChange()).isEqualTo(200);
        assertThat(history.getBalanceAfter()).isEqualTo(1_100);
        assertThat(history.getDescription())
                .isEqualTo("관리자 정산 보정: 홈팀 승 예측 적중");
    }

    @Test
    void cancelledGameRefundRecordsPositiveChangeAndActualBalance() {
        User user = TestEntities.user(1L, 900);
        UserPrediction prediction = prediction(
                user,
                PredictionOutcome.DRAW,
                100
        );

        service.refundCancelledGame(user, prediction);

        PointHistory history = savedHistory();
        assertThat(user.getPoint()).isEqualTo(1_000);
        assertThat(history.getType())
                .isEqualTo(PointHistoryType.GAME_CANCEL_REFUND);
        assertThat(history.getPointChange()).isEqualTo(100);
        assertThat(history.getBalanceAfter()).isEqualTo(user.getPoint());
        assertThat(history.getDescription())
                .isEqualTo("원정팀 vs 홈팀 경기 취소 환불");
    }

    @Test
    void signupBonusCreatesPositiveHistoryWithActualBalance() {
        User user = TestEntities.user(1L, 0);

        service.grantSignupBonus(user, 1000);

        PointHistory history = savedHistory();
        assertThat(user.getPoint()).isEqualTo(1000);
        assertThat(history.getPointChange()).isEqualTo(1000);
        assertThat(history.getBalanceAfter()).isEqualTo(1000);
        assertThat(history.getType()).isEqualTo(PointHistoryType.SIGNUP_BONUS);
        assertThat(history.getGame()).isNull();
        assertThat(history.getUserPrediction()).isNull();
    }

    private UserPrediction prediction(
            User user,
            PredictionOutcome outcome,
            int pointAmount
    ) {
        Game game = TestEntities.game(
                10L,
                GameStatus.SCHEDULED,
                LocalDate.of(2026, 8, 11),
                LocalTime.of(18, 30)
        );
        return UserPrediction.create(user, game, outcome, pointAmount);
    }

    private PointHistory savedHistory() {
        ArgumentCaptor<PointHistory> captor =
                ArgumentCaptor.forClass(PointHistory.class);
        verify(pointHistoryRepository).save(captor.capture());
        return captor.getValue();
    }
}
