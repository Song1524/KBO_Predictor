package com.playball.kbopredictor.auth.service;

import com.playball.kbopredictor.auth.dto.DailyLoginBonusResult;
import com.playball.kbopredictor.common.config.TimeConfig;
import com.playball.kbopredictor.point.entity.PointHistoryType;
import com.playball.kbopredictor.point.repository.PointHistoryRepository;
import com.playball.kbopredictor.point.service.PointService;
import com.playball.kbopredictor.point.service.UserPointLockService;
import com.playball.kbopredictor.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class DailyLoginBonusService {

    public static final int DAILY_BONUS_POINTS = 50;

    private final UserPointLockService userPointLockService;
    private final PointHistoryRepository pointHistoryRepository;
    private final PointService pointService;
    private final Clock clock;

    @Transactional
    public DailyLoginBonusResult grantIfEligible(Long userId) {
        LocalDate bonusDate = LocalDate.now(
                clock.withZone(TimeConfig.KOREA_ZONE)
        );
        User user = userPointLockService.findByIdForUpdate(userId);

        if (pointHistoryRepository.existsByUserIdAndBonusDateAndType(
                userId,
                bonusDate,
                PointHistoryType.DAILY_LOGIN_BONUS
        )) {
            return DailyLoginBonusResult.notGranted();
        }

        pointService.grantDailyLoginBonus(
                user,
                DAILY_BONUS_POINTS,
                bonusDate
        );
        return DailyLoginBonusResult.granted(DAILY_BONUS_POINTS);
    }
}
