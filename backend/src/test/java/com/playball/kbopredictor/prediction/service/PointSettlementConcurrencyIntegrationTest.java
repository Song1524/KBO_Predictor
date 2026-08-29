package com.playball.kbopredictor.prediction.service;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.point.entity.PointHistory;
import com.playball.kbopredictor.point.entity.PointHistoryType;
import com.playball.kbopredictor.point.repository.PointHistoryRepository;
import com.playball.kbopredictor.prediction.dto.UserPredictionRequest;
import com.playball.kbopredictor.prediction.entity.GameOdds;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.entity.UserPrediction;
import com.playball.kbopredictor.prediction.repository.GameOddsRepository;
import com.playball.kbopredictor.prediction.repository.UserPredictionRepository;
import com.playball.kbopredictor.team.entity.Team;
import com.playball.kbopredictor.team.repository.TeamRepository;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.assertj.core.groups.Tuple;
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
import static org.assertj.core.groups.Tuple.tuple;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:point-concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
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
class PointSettlementConcurrencyIntegrationTest {

    private static final int INITIAL_POINT = 1_000;
    private static final int BET_POINT = 100;
    private static final int PAYOUT = 200;

    @Autowired
    private PredictionSettlementService predictionSettlementService;

    @Autowired
    private UserPredictionService userPredictionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private GameOddsRepository gameOddsRepository;

    @Autowired
    private UserPredictionRepository userPredictionRepository;

    @Autowired
    private PointHistoryRepository pointHistoryRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanDatabase() {
        transactionTemplate.executeWithoutResult(status -> {
            pointHistoryRepository.deleteAllInBatch();
            userPredictionRepository.deleteAllInBatch();
            gameOddsRepository.deleteAllInBatch();
            gameRepository.deleteAllInBatch();
            userRepository.deleteAllInBatch();
            teamRepository.deleteAllInBatch();
        });
    }

    @Test
    void concurrentPredictionBetAndSettlementRewardAreBothApplied()
            throws Exception {
        Scenario scenario = createScenario(true, false, 1);
        Long scheduledGameId = scenario.scheduledGameIds().getFirst();
        Long finishedGameId = scenario.settlementGameIds().getFirst();

        runWithOverlappingTransaction(
                scenario.userId(),
                () -> userPredictionService.createPrediction(
                        scenario.userId(),
                        new UserPredictionRequest(
                                scheduledGameId,
                                PredictionOutcome.HOME_WIN,
                                BET_POINT
                        )
                ),
                () -> predictionSettlementService.settleGame(finishedGameId)
        );

        assertThat(currentPoint(scenario.userId()))
                .isEqualTo(INITIAL_POINT - BET_POINT + PAYOUT);
        assertHistories(
                scenario.userId(),
                tuple(PointHistoryType.PREDICTION_BET, -BET_POINT, 900),
                tuple(PointHistoryType.PREDICTION_REWARD, PAYOUT, 1_100)
        );
    }

    @Test
    void concurrentSettlementsForDifferentGamesApplyBothRewards()
            throws Exception {
        Scenario scenario = createScenario(false, false, 2);
        Long firstGameId = scenario.settlementGameIds().get(0);
        Long secondGameId = scenario.settlementGameIds().get(1);

        runWithOverlappingTransaction(
                scenario.userId(),
                () -> predictionSettlementService.settleGame(firstGameId),
                () -> predictionSettlementService.settleGame(secondGameId)
        );

        assertThat(currentPoint(scenario.userId()))
                .isEqualTo(INITIAL_POINT + PAYOUT + PAYOUT);
        assertHistories(
                scenario.userId(),
                tuple(PointHistoryType.PREDICTION_REWARD, PAYOUT, 1_200),
                tuple(PointHistoryType.PREDICTION_REWARD, PAYOUT, 1_400)
        );
    }

    @Test
    void concurrentCancellationRefundAndPredictionBetAreBothApplied()
            throws Exception {
        Scenario scenario = createScenario(true, true, 1);
        Long cancelledGameId = scenario.settlementGameIds().getFirst();
        Long scheduledGameId = scenario.scheduledGameIds().getFirst();

        runWithOverlappingTransaction(
                scenario.userId(),
                () -> predictionSettlementService.settleGame(cancelledGameId),
                () -> userPredictionService.createPrediction(
                        scenario.userId(),
                        new UserPredictionRequest(
                                scheduledGameId,
                                PredictionOutcome.AWAY_WIN,
                                BET_POINT
                        )
                )
        );

        assertThat(currentPoint(scenario.userId())).isEqualTo(INITIAL_POINT);
        assertHistories(
                scenario.userId(),
                tuple(PointHistoryType.GAME_CANCEL_REFUND, BET_POINT, 1_100),
                tuple(PointHistoryType.PREDICTION_BET, -BET_POINT, 1_000)
        );
    }

    private Scenario createScenario(
            boolean includeScheduledGame,
            boolean cancelled,
            int settlementGameCount
    ) {
        return transactionTemplate.execute(status -> {
            Team homeTeam = createTeam("HOME");
            Team awayTeam = createTeam("AWAY");
            User user = createUser();
            List<Long> scheduledGameIds = includeScheduledGame
                    ? List.of(createScheduledGame(homeTeam, awayTeam).getId())
                    : List.of();
            java.util.ArrayList<Long> settlementGameIds =
                    new java.util.ArrayList<>();
            for (int index = 0; index < settlementGameCount; index++) {
                Game game = cancelled
                        ? createCancelledGame(homeTeam, awayTeam)
                        : createFinishedGame(homeTeam, awayTeam);
                createFinalOdds(game);
                createPendingPrediction(user, game);
                settlementGameIds.add(game.getId());
            }
            return new Scenario(
                    user.getId(),
                    scheduledGameIds,
                    List.copyOf(settlementGameIds)
            );
        });
    }

    private Team createTeam(String prefix) {
        Team team = TestEntities.team(null, prefix + "팀");
        String suffix = UUID.randomUUID().toString().substring(0, 5);
        ReflectionTestUtils.setField(team, "kboTeamCode", prefix + suffix);
        ReflectionTestUtils.setField(team, "shortName", prefix);
        ReflectionTestUtils.setField(team, "createdAt", LocalDateTime.now());
        return teamRepository.saveAndFlush(team);
    }

    private User createUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = User.createLocal(
                "point-" + suffix + "@example.com",
                "encoded-password",
                "포인트" + suffix,
                null,
                LocalDateTime.now()
        );
        user.changePoint(INITIAL_POINT);
        return userRepository.saveAndFlush(user);
    }

    private Game createScheduledGame(Team homeTeam, Team awayTeam) {
        LocalDate gameDate = LocalDate.now().plusDays(2);
        Game game = Game.createCollected(
                uniqueExternalGameId(),
                gameDate.getYear(),
                gameDate,
                LocalTime.of(18, 30),
                homeTeam,
                awayTeam,
                "테스트 구장",
                GameStatus.SCHEDULED,
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.now()
        );
        return gameRepository.saveAndFlush(game);
    }

    private Game createFinishedGame(Team homeTeam, Team awayTeam) {
        LocalDate gameDate = LocalDate.now().minusDays(1);
        Game game = Game.createCollected(
                uniqueExternalGameId(),
                gameDate.getYear(),
                gameDate,
                LocalTime.of(18, 30),
                homeTeam,
                awayTeam,
                "테스트 구장",
                GameStatus.FINISHED,
                5,
                2,
                homeTeam,
                GameResult.HOME_WIN,
                null,
                LocalDateTime.now()
        );
        return gameRepository.saveAndFlush(game);
    }

    private Game createCancelledGame(Team homeTeam, Team awayTeam) {
        LocalDate gameDate = LocalDate.now().minusDays(1);
        Game game = Game.createCollected(
                uniqueExternalGameId(),
                gameDate.getYear(),
                gameDate,
                LocalTime.of(18, 30),
                homeTeam,
                awayTeam,
                "테스트 구장",
                GameStatus.CANCELLED,
                null,
                null,
                null,
                null,
                "우천 취소",
                LocalDateTime.now()
        );
        return gameRepository.saveAndFlush(game);
    }

    private void createFinalOdds(Game game) {
        LocalDateTime now = LocalDateTime.now();
        GameOdds odds = GameOdds.create(game, now);
        odds.addBet(PredictionOutcome.HOME_WIN, BET_POINT, now);
        odds.finalizeOdds(
                new BigDecimal("2.00"),
                new BigDecimal("10.00"),
                new BigDecimal("10.00"),
                now
        );
        gameOddsRepository.saveAndFlush(odds);
    }

    private void createPendingPrediction(User user, Game game) {
        userPredictionRepository.saveAndFlush(UserPrediction.create(
                user,
                game,
                PredictionOutcome.HOME_WIN,
                BET_POINT
        ));
    }

    private void runWithOverlappingTransaction(
            Long userId,
            Runnable firstOperation,
            Runnable secondOperation
    ) throws Exception {
        CountDownLatch secondTransactionLoadedUser = new CountDownLatch(1);
        CountDownLatch firstOperationCommitted = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                awaitUnchecked(
                        secondTransactionLoadedUser,
                        "두 번째 트랜잭션의 기존 잔액 조회"
                );
                try {
                    firstOperation.run();
                } finally {
                    firstOperationCommitted.countDown();
                }
            });
            Future<?> second = executor.submit(() -> {
                transactionTemplate.executeWithoutResult(status -> {
                    userRepository.findById(userId).orElseThrow();
                    secondTransactionLoadedUser.countDown();
                    awaitUnchecked(
                            firstOperationCommitted,
                            "첫 번째 포인트 변경 커밋"
                    );
                    secondOperation.run();
                });
            });

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void await(CountDownLatch latch, String event) throws Exception {
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException(event + " 대기 시간이 초과되었습니다.");
        }
    }

    private void awaitUnchecked(CountDownLatch latch, String event) {
        try {
            await(latch, event);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int currentPoint(Long userId) {
        return userRepository.findById(userId)
                .map(User::getPoint)
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

    private String uniqueExternalGameId() {
        return "CONCURRENCY-" + UUID.randomUUID().toString().substring(0, 16);
    }

    private record Scenario(
            Long userId,
            List<Long> scheduledGameIds,
            List<Long> settlementGameIds
    ) {
    }
}
