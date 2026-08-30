package com.playball.kbopredictor.prediction.service;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.point.entity.PointHistory;
import com.playball.kbopredictor.point.entity.PointHistoryType;
import com.playball.kbopredictor.point.repository.PointHistoryRepository;
import com.playball.kbopredictor.prediction.dto.GameResultCorrectionRequest;
import com.playball.kbopredictor.prediction.dto.PredictionSettlementResponse;
import com.playball.kbopredictor.prediction.dto.PredictionSettlementRollbackResponse;
import com.playball.kbopredictor.prediction.entity.GameOdds;
import com.playball.kbopredictor.prediction.entity.GameSettlement;
import com.playball.kbopredictor.prediction.entity.GameSettlementSource;
import com.playball.kbopredictor.prediction.entity.GameSettlementState;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.entity.PredictionSettlementStatus;
import com.playball.kbopredictor.prediction.entity.UserPrediction;
import com.playball.kbopredictor.prediction.repository.GameOddsRepository;
import com.playball.kbopredictor.prediction.repository.GameSettlementRepository;
import com.playball.kbopredictor.prediction.repository.UserPredictionRepository;
import com.playball.kbopredictor.ranking.repository.RankingQueryRepository;
import com.playball.kbopredictor.ranking.repository.RankingQueryRow;
import com.playball.kbopredictor.ranking.RankingType;
import com.playball.kbopredictor.ranking.dto.RankingEntryResponse;
import com.playball.kbopredictor.ranking.dto.RankingResponse;
import com.playball.kbopredictor.ranking.service.RankingService;
import com.playball.kbopredictor.team.entity.Team;
import com.playball.kbopredictor.team.repository.TeamRepository;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:settlement-recovery;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.kbo-data.sync-scheduler.enabled=false",
        "app.kbo-data.pregame-scheduler.enabled=false",
        "app.odds.closing-scheduler.enabled=false",
        "app.prediction.history-finalization-scheduler.enabled=false"
})
@ActiveProfiles("test")
class SettlementRecoveryIntegrationTest {

    private static final Long ADMIN_USER_ID = 9_000L;
    private static final int POST_BET_POINT = 900;
    private static final int BET_POINT = 100;
    private static final LocalDateTime PERIOD_START =
            LocalDateTime.of(2020, 1, 1, 0, 0);
    private static final LocalDateTime PERIOD_END =
            LocalDateTime.of(2030, 1, 1, 0, 0);

    @Autowired
    private PredictionSettlementService predictionSettlementService;
    @Autowired
    private GameSettlementRecoveryService recoveryService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private GameRepository gameRepository;
    @Autowired
    private GameOddsRepository gameOddsRepository;
    @Autowired
    private GameSettlementRepository gameSettlementRepository;
    @Autowired
    private UserPredictionRepository userPredictionRepository;
    @Autowired
    private PointHistoryRepository pointHistoryRepository;
    @Autowired
    private RankingQueryRepository rankingQueryRepository;
    @Autowired
    private RankingService rankingService;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanDatabase() {
        transactionTemplate.executeWithoutResult(status -> {
            pointHistoryRepository.deleteAllInBatch();
            userPredictionRepository.deleteAllInBatch();
            gameSettlementRepository.deleteAllInBatch();
            gameOddsRepository.deleteAllInBatch();
            gameRepository.deleteAllInBatch();
            userRepository.deleteAllInBatch();
            teamRepository.deleteAllInBatch();
        });
    }

    @Test
    void wrongHomeWinCanBeRolledBackCorrectedAndResettledAsAwayWin() {
        RecoveryFixture fixture = createFixture(
                GameStatus.FINISHED, GameResult.HOME_WIN, 5, 2,
                PredictionOutcome.HOME_WIN, PredictionOutcome.AWAY_WIN
        );

        PredictionSettlementResponse first = predictionSettlementService
                .settleGame(fixture.gameId(), ADMIN_USER_ID);
        assertThat(first.settlementRevision()).isEqualTo(1);
        assertThat(currentPoint(fixture.firstUserId())).isEqualTo(1_100);
        assertThat(currentPoint(fixture.secondUserId())).isEqualTo(900);
        assertRanking(
                periodRanking(fixture.firstUserId()),
                100,
                1,
                1,
                1
        );

        PredictionSettlementRollbackResponse rollback = recoveryService.rollback(
                fixture.gameId(), 1, ADMIN_USER_ID, "원정팀 승리 결과 정정"
        );
        assertThat(rollback.alreadyRolledBack()).isFalse();
        assertThat(rollback.reversedPointTotal()).isEqualTo(200);
        assertThat(currentPoint(fixture.firstUserId())).isEqualTo(900);
        assertThat(currentPoint(fixture.secondUserId())).isEqualTo(900);
        assertPredictionsPending(fixture.gameId());
        assertThat(findPeriodRanking(fixture.firstUserId())).isEmpty();
        assertRanking(
                totalRanking(fixture.firstUserId()),
                900,
                1,
                0,
                0
        );

        recoveryService.correctResult(
                fixture.gameId(), ADMIN_USER_ID,
                correctionRequest(1, GameStatus.FINISHED, 2, 5)
        );
        PredictionSettlementResponse second = predictionSettlementService
                .settleGame(fixture.gameId(), ADMIN_USER_ID, 1);

        assertThat(second.settlementRevision()).isEqualTo(2);
        assertThat(second.result()).isEqualTo(GameResult.AWAY_WIN);
        assertThat(currentPoint(fixture.firstUserId())).isEqualTo(900);
        assertThat(currentPoint(fixture.secondUserId())).isEqualTo(1_100);
        assertRanking(
                periodRanking(fixture.firstUserId()),
                -100,
                1,
                0,
                1
        );
        assertRanking(
                periodRanking(fixture.secondUserId()),
                100,
                1,
                1,
                1
        );
        assertRankingApisUseCurrentSettlement(fixture);
        assertHistories(
                fixture.firstUserId(),
                tuple(PointHistoryType.PREDICTION_REWARD, 200, 1_100),
                tuple(PointHistoryType.PREDICTION_REWARD_ROLLBACK, -200, 900)
        );
        assertHistories(
                fixture.secondUserId(),
                tuple(PointHistoryType.PREDICTION_REWARD, 200, 1_100)
        );
        assertThat(gameSettlementRepository
                .findByGameIdAndRevision(fixture.gameId(), 1)
                .orElseThrow()
                .getState()).isEqualTo(GameSettlementState.ROLLED_BACK);
        assertThat(gameSettlementRepository
                .findByGameIdAndRevision(fixture.gameId(), 1)
                .orElseThrow()
                .getResultCorrectedByUserId()).isEqualTo(ADMIN_USER_ID);
        assertThat(gameSettlementRepository.countByGameId(fixture.gameId()))
                .isEqualTo(2);
    }

    @Test
    void wrongDrawCanBeRolledBackAndResettledAsHomeWin() {
        RecoveryFixture fixture = createFixture(
                GameStatus.FINISHED, GameResult.DRAW, 3, 3,
                PredictionOutcome.DRAW, PredictionOutcome.HOME_WIN
        );

        predictionSettlementService.settleGame(fixture.gameId(), ADMIN_USER_ID);
        assertThat(currentPoint(fixture.firstUserId())).isEqualTo(1_200);
        assertRanking(
                periodRanking(fixture.firstUserId()),
                200,
                1,
                1,
                1
        );
        recoveryService.rollback(
                fixture.gameId(), 1, ADMIN_USER_ID, "무승부 오수집"
        );
        assertThat(currentPoint(fixture.firstUserId())).isEqualTo(900);
        assertThat(findPeriodRanking(fixture.firstUserId())).isEmpty();

        recoveryService.correctResult(
                fixture.gameId(), ADMIN_USER_ID,
                correctionRequest(1, GameStatus.FINISHED, 4, 3)
        );
        predictionSettlementService.settleGame(
                fixture.gameId(), ADMIN_USER_ID, 1
        );

        assertThat(currentPoint(fixture.firstUserId())).isEqualTo(900);
        assertThat(currentPoint(fixture.secondUserId())).isEqualTo(1_100);
        assertRanking(
                periodRanking(fixture.firstUserId()),
                -100,
                1,
                0,
                1
        );
        assertRanking(
                periodRanking(fixture.secondUserId()),
                100,
                1,
                1,
                1
        );
        assertHistories(
                fixture.firstUserId(),
                tuple(PointHistoryType.PREDICTION_REWARD, 300, 1_200),
                tuple(PointHistoryType.PREDICTION_REWARD_ROLLBACK, -300, 900)
        );
        assertHistories(
                fixture.secondUserId(),
                tuple(PointHistoryType.PREDICTION_REWARD, 200, 1_100)
        );
    }

    @Test
    void cancellationRefundCanBeRolledBackAndResettledAsFinishedGame() {
        RecoveryFixture fixture = createFixture(
                GameStatus.CANCELLED, null, null, null,
                PredictionOutcome.HOME_WIN, PredictionOutcome.AWAY_WIN
        );

        predictionSettlementService.settleGame(fixture.gameId(), ADMIN_USER_ID);
        assertThat(currentPoint(fixture.firstUserId())).isEqualTo(1_000);
        assertThat(currentPoint(fixture.secondUserId())).isEqualTo(1_000);
        assertThat(findPeriodRanking(fixture.firstUserId())).isEmpty();
        assertThat(findPeriodRanking(fixture.secondUserId())).isEmpty();

        recoveryService.rollback(
                fixture.gameId(), 1, ADMIN_USER_ID, "취소 상태 오수집"
        );
        assertThat(currentPoint(fixture.firstUserId())).isEqualTo(900);
        assertThat(currentPoint(fixture.secondUserId())).isEqualTo(900);
        assertThat(findPeriodRanking(fixture.firstUserId())).isEmpty();

        recoveryService.correctResult(
                fixture.gameId(), ADMIN_USER_ID,
                correctionRequest(1, GameStatus.FINISHED, 4, 1)
        );
        predictionSettlementService.settleGame(
                fixture.gameId(), ADMIN_USER_ID, 1
        );

        assertThat(currentPoint(fixture.firstUserId())).isEqualTo(1_100);
        assertThat(currentPoint(fixture.secondUserId())).isEqualTo(900);
        assertRanking(
                periodRanking(fixture.firstUserId()),
                100,
                1,
                1,
                1
        );
        assertRanking(
                periodRanking(fixture.secondUserId()),
                -100,
                1,
                0,
                1
        );
        assertHistories(
                fixture.firstUserId(),
                tuple(PointHistoryType.GAME_CANCEL_REFUND, 100, 1_000),
                tuple(PointHistoryType.GAME_CANCEL_REFUND_ROLLBACK, -100, 900),
                tuple(PointHistoryType.PREDICTION_REWARD, 200, 1_100)
        );
        assertHistories(
                fixture.secondUserId(),
                tuple(PointHistoryType.GAME_CANCEL_REFUND, 100, 1_000),
                tuple(PointHistoryType.GAME_CANCEL_REFUND_ROLLBACK, -100, 900)
        );
    }

    @Test
    void duplicateRollbackDoesNotReversePointsTwice() {
        RecoveryFixture fixture = createFixture(
                GameStatus.FINISHED, GameResult.HOME_WIN, 5, 2,
                PredictionOutcome.HOME_WIN, PredictionOutcome.AWAY_WIN
        );
        predictionSettlementService.settleGame(fixture.gameId(), ADMIN_USER_ID);

        PredictionSettlementRollbackResponse first = recoveryService.rollback(
                fixture.gameId(), 1, ADMIN_USER_ID, "결과 정정"
        );
        PredictionSettlementRollbackResponse second = recoveryService.rollback(
                fixture.gameId(), 1, ADMIN_USER_ID, "중복 요청"
        );

        assertThat(first.alreadyRolledBack()).isFalse();
        assertThat(second.alreadyRolledBack()).isTrue();
        assertThat(second.reversedPointHistoryCount()).isZero();
        assertThat(currentPoint(fixture.firstUserId())).isEqualTo(900);
        assertHistories(
                fixture.firstUserId(),
                tuple(PointHistoryType.PREDICTION_REWARD, 200, 1_100),
                tuple(PointHistoryType.PREDICTION_REWARD_ROLLBACK, -200, 900)
        );
    }

    @Test
    void concurrentDuplicateRollbackReversesPointsOnlyOnce() throws Exception {
        RecoveryFixture fixture = createFixture(
                GameStatus.FINISHED, GameResult.HOME_WIN, 5, 2,
                PredictionOutcome.HOME_WIN, PredictionOutcome.AWAY_WIN
        );
        predictionSettlementService.settleGame(fixture.gameId(), ADMIN_USER_ID);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PredictionSettlementRollbackResponse> first = executor.submit(
                    () -> rollbackWhenStarted(fixture.gameId(), ready, start)
            );
            Future<PredictionSettlementRollbackResponse> second = executor.submit(
                    () -> rollbackWhenStarted(fixture.gameId(), ready, start)
            );
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    first.get(10, TimeUnit.SECONDS).alreadyRolledBack(),
                    second.get(10, TimeUnit.SECONDS).alreadyRolledBack()
            )).containsExactlyInAnyOrder(false, true);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(currentPoint(fixture.firstUserId())).isEqualTo(900);
        assertHistories(
                fixture.firstUserId(),
                tuple(PointHistoryType.PREDICTION_REWARD, 200, 1_100),
                tuple(PointHistoryType.PREDICTION_REWARD_ROLLBACK, -200, 900)
        );
    }

    @Test
    void repeatedSettlementWithoutRollbackDoesNotPayAgain() {
        RecoveryFixture fixture = createFixture(
                GameStatus.FINISHED, GameResult.HOME_WIN, 5, 2,
                PredictionOutcome.HOME_WIN, PredictionOutcome.AWAY_WIN
        );

        PredictionSettlementResponse first = predictionSettlementService
                .settleGame(fixture.gameId(), ADMIN_USER_ID);
        PredictionSettlementResponse second = predictionSettlementService
                .settleGame(fixture.gameId(), ADMIN_USER_ID);

        assertThat(first.totalPaidPoints()).isEqualTo(200);
        assertThat(second.totalCount()).isZero();
        assertThat(second.totalPaidPoints()).isZero();
        assertThat(second.settlementRevision()).isEqualTo(1);
        assertThat(currentPoint(fixture.firstUserId())).isEqualTo(1_100);
        assertThat(gameSettlementRepository.countByGameId(fixture.gameId()))
                .isEqualTo(1);
        assertHistories(
                fixture.firstUserId(),
                tuple(PointHistoryType.PREDICTION_REWARD, 200, 1_100)
        );
    }

    @Test
    void rolledBackSettlementCannotRunAgainBeforeResultCorrection() {
        RecoveryFixture fixture = createFixture(
                GameStatus.FINISHED, GameResult.HOME_WIN, 5, 2,
                PredictionOutcome.HOME_WIN, PredictionOutcome.AWAY_WIN
        );
        predictionSettlementService.settleGame(fixture.gameId(), ADMIN_USER_ID);
        recoveryService.rollback(
                fixture.gameId(), 1, ADMIN_USER_ID, "결과 확인 필요"
        );

        assertThatThrownBy(() -> predictionSettlementService.settleGame(
                fixture.gameId(), ADMIN_USER_ID, 1
        ))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("경기 결과를 관리자 정정한 후");
        assertThat(currentPoint(fixture.firstUserId())).isEqualTo(900);
        assertThat(gameSettlementRepository.countByGameId(fixture.gameId()))
                .isEqualTo(1);
    }

    @Test
    void resettlementStopsWhenGameChangesAfterAdminCorrection() {
        RecoveryFixture fixture = createFixture(
                GameStatus.FINISHED, GameResult.HOME_WIN, 5, 2,
                PredictionOutcome.HOME_WIN, PredictionOutcome.AWAY_WIN
        );
        predictionSettlementService.settleGame(fixture.gameId(), ADMIN_USER_ID);
        recoveryService.rollback(
                fixture.gameId(), 1, ADMIN_USER_ID, "원정팀 승리 정정"
        );
        recoveryService.correctResult(
                fixture.gameId(), ADMIN_USER_ID,
                correctionRequest(1, GameStatus.FINISHED, 2, 5)
        );
        transactionTemplate.executeWithoutResult(status -> {
            Game game = gameRepository.findById(fixture.gameId()).orElseThrow();
            game.correctTerminalResult(
                    GameStatus.FINISHED,
                    5,
                    2,
                    null,
                    LocalDateTime.now()
            );
        });

        assertThatThrownBy(() -> predictionSettlementService.settleGame(
                fixture.gameId(), ADMIN_USER_ID, 1
        ))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("관리자 결과 정정 이후 경기 데이터가 변경");
        assertThat(currentPoint(fixture.firstUserId())).isEqualTo(900);
        assertThat(gameSettlementRepository.countByGameId(fixture.gameId()))
                .isEqualTo(1);
    }

    @Test
    void settlementCanBeCorrectedAcrossThreeRevisions() {
        RecoveryFixture fixture = createFixture(
                GameStatus.FINISHED, GameResult.HOME_WIN, 5, 2,
                PredictionOutcome.HOME_WIN, PredictionOutcome.AWAY_WIN
        );

        predictionSettlementService.settleGame(fixture.gameId(), ADMIN_USER_ID);
        recoveryService.rollback(
                fixture.gameId(), 1, ADMIN_USER_ID, "1차 결과 정정"
        );
        recoveryService.correctResult(
                fixture.gameId(), ADMIN_USER_ID,
                correctionRequest(1, GameStatus.FINISHED, 2, 5)
        );
        predictionSettlementService.settleGame(
                fixture.gameId(), ADMIN_USER_ID, 1
        );

        recoveryService.rollback(
                fixture.gameId(), 2, ADMIN_USER_ID, "2차 결과 재정정"
        );
        recoveryService.correctResult(
                fixture.gameId(), ADMIN_USER_ID,
                correctionRequest(2, GameStatus.FINISHED, 6, 2)
        );
        PredictionSettlementResponse third = predictionSettlementService
                .settleGame(fixture.gameId(), ADMIN_USER_ID, 2);

        assertThat(third.settlementRevision()).isEqualTo(3);
        assertThat(currentPoint(fixture.firstUserId())).isEqualTo(1_100);
        assertThat(currentPoint(fixture.secondUserId())).isEqualTo(900);
        assertThat(gameSettlementRepository.countByGameId(fixture.gameId()))
                .isEqualTo(3);
        assertThat(gameSettlementRepository
                .findByGameIdAndRevision(fixture.gameId(), 1)
                .orElseThrow()
                .getState()).isEqualTo(GameSettlementState.ROLLED_BACK);
        assertThat(gameSettlementRepository
                .findByGameIdAndRevision(fixture.gameId(), 2)
                .orElseThrow()
                .getState()).isEqualTo(GameSettlementState.ROLLED_BACK);
        assertThat(gameSettlementRepository
                .findByGameIdAndRevision(fixture.gameId(), 3)
                .orElseThrow()
                .getState()).isEqualTo(GameSettlementState.SETTLED);
        assertRanking(
                periodRanking(fixture.firstUserId()),
                100,
                1,
                1,
                1
        );
        assertRanking(
                periodRanking(fixture.secondUserId()),
                -100,
                1,
                0,
                1
        );
        assertHistories(
                fixture.firstUserId(),
                tuple(PointHistoryType.PREDICTION_REWARD, 200, 1_100),
                tuple(PointHistoryType.PREDICTION_REWARD_ROLLBACK, -200, 900),
                tuple(PointHistoryType.PREDICTION_REWARD, 200, 1_100)
        );
        assertHistories(
                fixture.secondUserId(),
                tuple(PointHistoryType.PREDICTION_REWARD, 200, 1_100),
                tuple(PointHistoryType.PREDICTION_REWARD_ROLLBACK, -200, 900)
        );
    }

    @Test
    void rankingUsesOnlyCurrentRewardWhenWinIsResettledWithDifferentPayout() {
        RecoveryFixture fixture = createFixture(
                GameStatus.FINISHED, GameResult.HOME_WIN, 5, 2,
                PredictionOutcome.HOME_WIN, PredictionOutcome.AWAY_WIN
        );
        changeFinalHomeOdds(fixture.gameId(), "5.00");
        predictionSettlementService.settleGame(fixture.gameId(), ADMIN_USER_ID);
        assertRanking(
                periodRanking(fixture.firstUserId()),
                400,
                1,
                1,
                1
        );

        recoveryService.rollback(
                fixture.gameId(), 1, ADMIN_USER_ID, "지급액 재정정"
        );
        recoveryService.correctResult(
                fixture.gameId(), ADMIN_USER_ID,
                correctionRequest(1, GameStatus.FINISHED, 6, 2)
        );
        changeFinalHomeOdds(fixture.gameId(), "3.00");
        predictionSettlementService.settleGame(
                fixture.gameId(), ADMIN_USER_ID, 1
        );

        assertThat(currentPoint(fixture.firstUserId())).isEqualTo(1_200);
        assertRanking(
                totalRanking(fixture.firstUserId()),
                1_200,
                1,
                1,
                1
        );
        assertRanking(
                periodRanking(fixture.firstUserId()),
                200,
                1,
                1,
                1
        );
        assertHistories(
                fixture.firstUserId(),
                tuple(PointHistoryType.PREDICTION_REWARD, 500, 1_400),
                tuple(PointHistoryType.PREDICTION_REWARD_ROLLBACK, -500, 900),
                tuple(PointHistoryType.PREDICTION_REWARD, 300, 1_200)
        );
    }

    @Test
    void legacySettlementRemainsIncludedInRanking() {
        RecoveryFixture fixture = createFixture(
                GameStatus.FINISHED, GameResult.HOME_WIN, 5, 2,
                PredictionOutcome.HOME_WIN, PredictionOutcome.AWAY_WIN
        );
        createLegacySettlement(fixture.gameId());

        assertRanking(
                totalRanking(fixture.firstUserId()),
                1_100,
                1,
                1,
                1
        );
        assertRanking(
                periodRanking(fixture.firstUserId()),
                100,
                1,
                1,
                1
        );
        assertThat(gameSettlementRepository
                .findByGameIdAndRevision(fixture.gameId(), 1)
                .orElseThrow()
                .getSource()).isEqualTo(GameSettlementSource.LEGACY);
    }

    @Test
    void mismatchedRevisionNeverChangesAnotherSettlementCycle() {
        RecoveryFixture fixture = createFixture(
                GameStatus.FINISHED, GameResult.HOME_WIN, 5, 2,
                PredictionOutcome.HOME_WIN, PredictionOutcome.AWAY_WIN
        );
        predictionSettlementService.settleGame(fixture.gameId(), ADMIN_USER_ID);

        assertThatThrownBy(() -> recoveryService.rollback(
                fixture.gameId(), 2, ADMIN_USER_ID, "잘못된 회차"
        ))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("정산 회차를 찾을 수 없습니다");
        assertThat(currentPoint(fixture.firstUserId())).isEqualTo(1_100);
        assertThat(gameSettlementRepository
                .findByGameIdAndRevision(fixture.gameId(), 1)
                .orElseThrow()
                .getState()).isEqualTo(GameSettlementState.SETTLED);

        recoveryService.rollback(
                fixture.gameId(), 1, ADMIN_USER_ID, "실제 1차 rollback"
        );
        recoveryService.correctResult(
                fixture.gameId(), ADMIN_USER_ID,
                correctionRequest(1, GameStatus.FINISHED, 2, 5)
        );
        assertThatThrownBy(() -> predictionSettlementService.settleGame(
                fixture.gameId(), ADMIN_USER_ID, 2
        ))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("rollback 회차를 확인한");

        assertThat(currentPoint(fixture.firstUserId())).isEqualTo(900);
        assertThat(currentPoint(fixture.secondUserId())).isEqualTo(900);
        assertThat(gameSettlementRepository.countByGameId(fixture.gameId()))
                .isEqualTo(1);
        assertHistories(
                fixture.firstUserId(),
                tuple(PointHistoryType.PREDICTION_REWARD, 200, 1_100),
                tuple(PointHistoryType.PREDICTION_REWARD_ROLLBACK, -200, 900)
        );
    }

    private RecoveryFixture createFixture(
            GameStatus status,
            GameResult result,
            Integer homeScore,
            Integer awayScore,
            PredictionOutcome firstOutcome,
            PredictionOutcome secondOutcome
    ) {
        return transactionTemplate.execute(transactionStatus -> {
            Team homeTeam = createTeam("HOME");
            Team awayTeam = createTeam("AWAY");
            User firstUser = createUser("first", POST_BET_POINT);
            User secondUser = createUser("second", POST_BET_POINT);
            Game game = createGame(
                    homeTeam, awayTeam, status, result, homeScore, awayScore
            );
            createOdds(game);
            userPredictionRepository.saveAndFlush(UserPrediction.create(
                    firstUser, game, firstOutcome, BET_POINT
            ));
            userPredictionRepository.saveAndFlush(UserPrediction.create(
                    secondUser, game, secondOutcome, BET_POINT
            ));
            return new RecoveryFixture(
                    game.getId(), firstUser.getId(), secondUser.getId()
            );
        });
    }

    private PredictionSettlementRollbackResponse rollbackWhenStarted(
            Long gameId,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("rollback 시작 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        return recoveryService.rollback(
                gameId, 1, ADMIN_USER_ID, "동시 중복 rollback"
        );
    }

    private Team createTeam(String prefix) {
        Team team = TestEntities.team(null, prefix + "팀");
        String suffix = UUID.randomUUID().toString().substring(0, 5);
        ReflectionTestUtils.setField(team, "kboTeamCode", prefix + suffix);
        ReflectionTestUtils.setField(team, "shortName", prefix + suffix);
        ReflectionTestUtils.setField(team, "createdAt", LocalDateTime.now());
        return teamRepository.saveAndFlush(team);
    }

    private User createUser(String prefix, int point) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = User.createLocal(
                prefix + "-" + suffix + "@example.com",
                "encoded-password",
                prefix + suffix,
                null,
                LocalDateTime.now()
        );
        user.changePoint(point);
        return userRepository.saveAndFlush(user);
    }

    private Game createGame(
            Team homeTeam,
            Team awayTeam,
            GameStatus status,
            GameResult result,
            Integer homeScore,
            Integer awayScore
    ) {
        LocalDate gameDate = LocalDate.now().minusDays(1);
        Team winner = result == GameResult.HOME_WIN
                ? homeTeam
                : result == GameResult.AWAY_WIN ? awayTeam : null;
        Game game = Game.createCollected(
                "RECOVERY-" + UUID.randomUUID().toString().substring(0, 16),
                gameDate.getYear(), gameDate, LocalTime.of(18, 30),
                homeTeam, awayTeam, "테스트 구장", status,
                homeScore, awayScore, winner, result,
                status == GameStatus.CANCELLED ? "우천 취소" : null,
                LocalDateTime.now()
        );
        return gameRepository.saveAndFlush(game);
    }

    private void createOdds(Game game) {
        LocalDateTime now = LocalDateTime.now();
        GameOdds odds = GameOdds.create(game, now);
        odds.addBet(PredictionOutcome.HOME_WIN, 100, now);
        odds.addBet(PredictionOutcome.DRAW, 100, now);
        odds.addBet(PredictionOutcome.AWAY_WIN, 100, now);
        odds.finalizeOdds(
                new BigDecimal("2.00"),
                new BigDecimal("3.00"),
                new BigDecimal("2.00"),
                now
        );
        gameOddsRepository.saveAndFlush(odds);
    }

    private void changeFinalHomeOdds(Long gameId, String oddsValue) {
        transactionTemplate.executeWithoutResult(status -> {
            GameOdds odds = gameOddsRepository.findByGameId(gameId)
                    .orElseThrow();
            ReflectionTestUtils.setField(
                    odds,
                    "finalHomeWinOdds",
                    new BigDecimal(oddsValue)
            );
        });
    }

    private void createLegacySettlement(Long gameId) {
        transactionTemplate.executeWithoutResult(status -> {
            Game game = gameRepository.findById(gameId).orElseThrow();
            LocalDateTime settledAt = LocalDateTime.now();
            GameSettlement settlement = gameSettlementRepository.saveAndFlush(
                    GameSettlement.start(
                            game,
                            1,
                            GameSettlementSource.LEGACY,
                            null,
                            settledAt
                    )
            );
            List<UserPrediction> predictions = userPredictionRepository
                    .findByGameIdAndSettledFalseOrderByUserIdAscIdAsc(gameId);
            int correctCount = 0;
            int incorrectCount = 0;
            long paidPoints = 0;
            for (UserPrediction prediction : predictions) {
                if (prediction.getSelectedOutcome().matches(game.getResult())) {
                    prediction.settleWon(settledAt, settlement);
                    User user = userRepository.findById(
                            prediction.getUser().getId()
                    ).orElseThrow();
                    user.changePoint(200);
                    pointHistoryRepository.save(PointHistory.create(
                            user,
                            game,
                            prediction,
                            settlement,
                            null,
                            200,
                            user.getPoint(),
                            PointHistoryType.PREDICTION_REWARD,
                            "LEGACY 정산 지급",
                            settledAt
                    ));
                    correctCount++;
                    paidPoints += 200;
                } else {
                    prediction.settleLost(settledAt, settlement);
                    incorrectCount++;
                }
            }
            settlement.complete(
                    predictions.size(),
                    correctCount,
                    incorrectCount,
                    0,
                    paidPoints
            );
        });
    }

    private GameResultCorrectionRequest correctionRequest(
            int revision,
            GameStatus status,
            Integer homeScore,
            Integer awayScore
    ) {
        return new GameResultCorrectionRequest(
                revision, status, homeScore, awayScore, null, "공식 기록 정정"
        );
    }

    private void assertPredictionsPending(Long gameId) {
        List<UserPrediction> predictions = userPredictionRepository
                .findByGameIdAndSettledFalseOrderByUserIdAscIdAsc(gameId);
        assertThat(predictions).hasSize(2);
        assertThat(predictions).allSatisfy(prediction -> {
            assertThat(prediction.getSettled()).isFalse();
            assertThat(prediction.getSettlementStatus())
                    .isEqualTo(PredictionSettlementStatus.PENDING);
            assertThat(prediction.getIsCorrect()).isNull();
            assertThat(prediction.getSettledAt()).isNull();
            assertThat(prediction.getSettlement()).isNull();
        });
    }

    private int currentPoint(Long userId) {
        return userRepository.findById(userId)
                .map(User::getPoint)
                .orElseThrow();
    }

    private RankingQueryRow totalRanking(Long userId) {
        return rankingQueryRepository.findTotalByUserId(userId).orElseThrow();
    }

    private java.util.Optional<RankingQueryRow> findPeriodRanking(Long userId) {
        return rankingQueryRepository.findPeriodByUserId(
                PERIOD_START,
                PERIOD_END,
                userId
        );
    }

    private RankingQueryRow periodRanking(Long userId) {
        return findPeriodRanking(userId).orElseThrow();
    }

    private void assertRanking(
            RankingQueryRow row,
            long score,
            long predictionCount,
            long correctCount,
            long gradedCount
    ) {
        assertThat(row.score()).isEqualTo(score);
        assertThat(row.predictionCount()).isEqualTo(predictionCount);
        assertThat(row.correctCount()).isEqualTo(correctCount);
        assertThat(row.gradedPredictionCount()).isEqualTo(gradedCount);
    }

    private void assertRankingApisUseCurrentSettlement(
            RecoveryFixture fixture
    ) {
        RankingEntryResponse total = rankingEntry(
                rankingService.getRankings(
                        RankingType.TOTAL_POINT,
                        20,
                        null
                ),
                fixture.secondUserId()
        );
        RankingEntryResponse monthly = rankingEntry(
                rankingService.getRankings(
                        RankingType.MONTHLY_PROFIT,
                        20,
                        null
                ),
                fixture.secondUserId()
        );
        RankingEntryResponse weekly = rankingEntry(
                rankingService.getRankings(
                        RankingType.WEEKLY_PROFIT,
                        20,
                        null
                ),
                fixture.secondUserId()
        );

        assertThat(total.currentPoint()).isEqualTo(1_100);
        assertThat(total.correctCount()).isEqualTo(1);
        assertThat(monthly.periodProfit()).isEqualTo(100);
        assertThat(monthly.correctCount()).isEqualTo(1);
        assertThat(weekly.periodProfit()).isEqualTo(100);
        assertThat(weekly.correctCount()).isEqualTo(1);
    }

    private RankingEntryResponse rankingEntry(
            RankingResponse response,
            Long userId
    ) {
        return response.rankings().stream()
                .filter(entry -> entry.userId() == userId)
                .findFirst()
                .orElseThrow();
    }

    private void assertHistories(Long userId, Tuple... expected) {
        List<PointHistory> histories = pointHistoryRepository
                .findByUserIdOrderByCreatedAtDescIdDesc(userId)
                .stream()
                .sorted(Comparator.comparing(PointHistory::getId))
                .toList();
        assertThat(histories)
                .extracting(
                        PointHistory::getType,
                        PointHistory::getPointChange,
                        PointHistory::getBalanceAfter
                )
                .containsExactly(expected);
        assertThat(histories.getLast().getBalanceAfter())
                .isEqualTo(currentPoint(userId));
    }

    private record RecoveryFixture(
            Long gameId,
            Long firstUserId,
            Long secondUserId
    ) {
    }
}
