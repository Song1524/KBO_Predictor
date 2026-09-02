package com.playball.kbopredictor.auth;

import com.playball.kbopredictor.auth.dto.DailyLoginBonusResult;
import com.playball.kbopredictor.auth.service.DailyLoginBonusService;
import com.playball.kbopredictor.common.config.TimeConfig;
import com.playball.kbopredictor.point.entity.PointHistoryType;
import com.playball.kbopredictor.point.repository.PointHistoryRepository;
import com.playball.kbopredictor.point.service.PointService;
import com.playball.kbopredictor.point.service.UserPointLockService;
import com.playball.kbopredictor.prediction.service.TestEntities;
import com.playball.kbopredictor.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyLoginBonusServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private UserPointLockService userPointLockService;
    @Mock
    private PointHistoryRepository pointHistoryRepository;
    @Mock
    private PointService pointService;

    @Test
    void firstLoginOfSeoulDateGrantsBonus() {
        User user = TestEntities.user(USER_ID, 1_000);
        LocalDate expectedDate = LocalDate.of(2026, 9, 2);
        DailyLoginBonusService service = serviceAt("2026-09-02T14:59:59Z");
        when(userPointLockService.findByIdForUpdate(USER_ID)).thenReturn(user);
        when(pointHistoryRepository.existsByUserIdAndBonusDateAndType(
                USER_ID,
                expectedDate,
                PointHistoryType.DAILY_LOGIN_BONUS
        )).thenReturn(false);

        DailyLoginBonusResult result = service.grantIfEligible(USER_ID);

        assertThat(result.granted()).isTrue();
        assertThat(result.points()).isEqualTo(50);
        verify(pointService).grantDailyLoginBonus(user, 50, expectedDate);
    }

    @Test
    void repeatedLoginOnSameSeoulDateDoesNotGrantBonus() {
        User user = TestEntities.user(USER_ID, 1_050);
        LocalDate expectedDate = LocalDate.of(2026, 9, 2);
        DailyLoginBonusService service = serviceAt("2026-09-02T10:00:00Z");
        when(userPointLockService.findByIdForUpdate(USER_ID)).thenReturn(user);
        when(pointHistoryRepository.existsByUserIdAndBonusDateAndType(
                USER_ID,
                expectedDate,
                PointHistoryType.DAILY_LOGIN_BONUS
        )).thenReturn(true);

        DailyLoginBonusResult result = service.grantIfEligible(USER_ID);

        assertThat(result.granted()).isFalse();
        assertThat(result.points()).isZero();
        verify(pointService, never()).grantDailyLoginBonus(
                user,
                50,
                expectedDate
        );
    }

    @Test
    void midnightBoundaryUsesAsiaSeoulDate() {
        User user = TestEntities.user(USER_ID, 1_000);
        LocalDate beforeMidnight = LocalDate.of(2026, 9, 2);
        LocalDate afterMidnight = LocalDate.of(2026, 9, 3);
        when(userPointLockService.findByIdForUpdate(USER_ID)).thenReturn(user);

        serviceAt("2026-09-02T14:59:59Z").grantIfEligible(USER_ID);
        serviceAt("2026-09-02T15:00:00Z").grantIfEligible(USER_ID);

        verify(pointHistoryRepository).existsByUserIdAndBonusDateAndType(
                USER_ID,
                beforeMidnight,
                PointHistoryType.DAILY_LOGIN_BONUS
        );
        verify(pointHistoryRepository).existsByUserIdAndBonusDateAndType(
                USER_ID,
                afterMidnight,
                PointHistoryType.DAILY_LOGIN_BONUS
        );
        verify(pointService).grantDailyLoginBonus(user, 50, beforeMidnight);
        verify(pointService).grantDailyLoginBonus(user, 50, afterMidnight);
    }

    private DailyLoginBonusService serviceAt(String instant) {
        Clock clock = Clock.fixed(
                Instant.parse(instant),
                TimeConfig.KOREA_ZONE
        );
        return new DailyLoginBonusService(
                userPointLockService,
                pointHistoryRepository,
                pointService,
                clock
        );
    }
}
