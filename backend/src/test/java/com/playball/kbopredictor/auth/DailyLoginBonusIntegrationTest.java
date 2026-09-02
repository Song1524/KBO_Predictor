package com.playball.kbopredictor.auth;

import com.playball.kbopredictor.auth.dto.DailyLoginBonusResult;
import com.playball.kbopredictor.auth.service.DailyLoginBonusService;
import com.playball.kbopredictor.common.config.TimeConfig;
import com.playball.kbopredictor.point.entity.PointHistory;
import com.playball.kbopredictor.point.entity.PointHistoryType;
import com.playball.kbopredictor.point.repository.PointHistoryRepository;
import com.playball.kbopredictor.point.service.PointService;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:daily-login-bonus;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
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
@Import(DailyLoginBonusIntegrationTest.FixedClockConfiguration.class)
class DailyLoginBonusIntegrationTest {

    private static final int SIGNUP_BONUS = 1_000;

    @Autowired
    private DailyLoginBonusService dailyLoginBonusService;
    @Autowired
    private PointService pointService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PointHistoryRepository pointHistoryRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private MutableClock clock;

    private Long userId;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.parse("2026-09-02T03:00:00Z"));
        userId = transactionTemplate.execute(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            User user = User.createLocal(
                    "daily-login-" + suffix + "@example.com",
                    "encoded-password",
                    "로그인" + suffix,
                    null,
                    LocalDateTime.now(clock)
            );
            userRepository.saveAndFlush(user);
            pointService.grantSignupBonus(user, SIGNUP_BONUS);
            return user.getId();
        });
    }

    @AfterEach
    void cleanDatabase() {
        transactionTemplate.executeWithoutResult(status -> {
            pointHistoryRepository.deleteAllInBatch();
            userRepository.deleteAllInBatch();
        });
    }

    @Test
    void firstAndNextDayLoginGrantOnceAndKeepSignupBonusSeparate() {
        clock.setInstant(Instant.parse("2026-09-02T14:59:59Z"));

        DailyLoginBonusResult first =
                dailyLoginBonusService.grantIfEligible(userId);
        DailyLoginBonusResult sameDate =
                dailyLoginBonusService.grantIfEligible(userId);

        clock.setInstant(Instant.parse("2026-09-02T15:00:00Z"));
        DailyLoginBonusResult nextDate =
                dailyLoginBonusService.grantIfEligible(userId);

        assertThat(first).isEqualTo(DailyLoginBonusResult.granted(50));
        assertThat(sameDate).isEqualTo(DailyLoginBonusResult.notGranted());
        assertThat(nextDate).isEqualTo(DailyLoginBonusResult.granted(50));
        assertThat(currentPoint()).isEqualTo(1_100);

        List<PointHistory> histories = historiesInLedgerOrder();
        assertThat(histories).hasSize(3);
        assertThat(histories.getFirst().getType())
                .isEqualTo(PointHistoryType.SIGNUP_BONUS);
        assertThat(histories.getFirst().getBalanceAfter()).isEqualTo(1_000);

        List<PointHistory> dailyBonuses = histories.stream()
                .filter(history -> history.getType()
                        == PointHistoryType.DAILY_LOGIN_BONUS)
                .toList();
        assertThat(dailyBonuses)
                .extracting(PointHistory::getBonusDate)
                .containsExactly(
                        LocalDate.of(2026, 9, 2),
                        LocalDate.of(2026, 9, 3)
                );
        assertThat(dailyBonuses)
                .extracting(PointHistory::getBalanceAfter)
                .containsExactly(1_050, 1_100);
        assertThat(histories.getLast().getBalanceAfter())
                .isEqualTo(currentPoint());
    }

    @Test
    void concurrentLoginsGrantExactlyOneBonusAndOneLedgerEntry()
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<DailyLoginBonusResult> first = executor.submit(
                    () -> grantAfterSignal(ready, start)
            );
            Future<DailyLoginBonusResult> second = executor.submit(
                    () -> grantAfterSignal(ready, start)
            );

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<DailyLoginBonusResult> results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
            assertThat(results).filteredOn(DailyLoginBonusResult::granted)
                    .singleElement()
                    .extracting(DailyLoginBonusResult::points)
                    .isEqualTo(50);
            assertThat(results).filteredOn(result -> !result.granted())
                    .singleElement()
                    .extracting(DailyLoginBonusResult::points)
                    .isEqualTo(0);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                    .isTrue();
        }

        List<PointHistory> dailyBonuses = dailyBonusHistories();
        assertThat(dailyBonuses).singleElement().satisfies(history -> {
            assertThat(history.getPointChange()).isEqualTo(50);
            assertThat(history.getBalanceAfter()).isEqualTo(1_050);
            assertThat(history.getBonusDate())
                    .isEqualTo(LocalDate.of(2026, 9, 2));
        });
        assertThat(currentPoint()).isEqualTo(1_050);
        assertThat(dailyBonuses.getFirst().getBalanceAfter())
                .isEqualTo(currentPoint());
    }

    @Test
    void databaseConstraintRejectsDuplicateBonusLedgerForSameDate() {
        dailyLoginBonusService.grantIfEligible(userId);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                status -> {
                    User user = userRepository.findById(userId).orElseThrow();
                    pointHistoryRepository.saveAndFlush(
                            PointHistory.createDailyLoginBonus(
                                    user,
                                    50,
                                    1_100,
                                    LocalDate.of(2026, 9, 2),
                                    LocalDateTime.now(clock)
                            )
                    );
                }
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(currentPoint()).isEqualTo(1_050);
        assertThat(dailyBonusHistories()).hasSize(1);
    }

    private DailyLoginBonusResult grantAfterSignal(
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시 로그인 시작 신호 대기 실패");
        }
        return dailyLoginBonusService.grantIfEligible(userId);
    }

    private int currentPoint() {
        return userRepository.findById(userId)
                .map(User::getPoint)
                .orElseThrow();
    }

    private List<PointHistory> historiesInLedgerOrder() {
        return pointHistoryRepository
                .findByUserIdOrderByCreatedAtDescIdDesc(userId)
                .stream()
                .sorted(Comparator.comparing(PointHistory::getId))
                .toList();
    }

    private List<PointHistory> dailyBonusHistories() {
        return historiesInLedgerOrder().stream()
                .filter(history -> history.getType()
                        == PointHistoryType.DAILY_LOGIN_BONUS)
                .toList();
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(
                    Instant.parse("2026-09-02T03:00:00Z"),
                    TimeConfig.KOREA_ZONE
            );
        }
    }

    static final class MutableClock extends Clock {

        private volatile Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return Clock.fixed(instant, requestedZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
