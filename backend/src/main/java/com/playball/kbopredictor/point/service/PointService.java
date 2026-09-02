package com.playball.kbopredictor.point.service;

import com.playball.kbopredictor.point.dto.PointHistoryResponse;
import com.playball.kbopredictor.point.entity.PointHistory;
import com.playball.kbopredictor.point.entity.PointHistoryType;
import com.playball.kbopredictor.point.repository.PointHistoryRepository;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.entity.GameSettlement;
import com.playball.kbopredictor.prediction.entity.UserPrediction;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PointService {

    private final PointHistoryRepository pointHistoryRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void useForPrediction(
            User user,
            UserPrediction prediction
    ) {
        applyChange(
                user,
                prediction,
                -prediction.getPointAmount(),
                PointHistoryType.PREDICTION_BET,
                outcomeLabel(prediction) + " 예측 참여"
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void rewardPrediction(
            User user,
            UserPrediction prediction,
            int payout
    ) {
        applyChange(
                user,
                prediction,
                payout,
                PointHistoryType.PREDICTION_REWARD,
                outcomeLabel(prediction) + " 예측 적중"
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refundCancelledGame(
            User user,
            UserPrediction prediction
    ) {
        applyChange(
                user,
                prediction,
                prediction.getPointAmount(),
                PointHistoryType.GAME_CANCEL_REFUND,
                prediction.getGame().getAwayTeam().getName() + " vs " +
                        prediction.getGame().getHomeTeam().getName() +
                        " 경기 취소 환불"
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void grantSignupBonus(User user, int points) {
        if (points <= 0) {
            throw new IllegalArgumentException(
                    "Signup bonus points must be greater than zero."
            );
        }
        applyChange(
                user,
                null,
                null,
                null,
                null,
                points,
                PointHistoryType.SIGNUP_BONUS,
                "회원가입 축하 포인트"
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void grantDailyLoginBonus(
            User user,
            int points,
            LocalDate bonusDate
    ) {
        if (points <= 0) {
            throw new IllegalArgumentException(
                    "Daily login bonus points must be greater than zero."
            );
        }
        if (user.getPoint() == null) {
            throw new IllegalStateException("사용자 포인트 잔액이 없습니다.");
        }

        final int balanceAfter;
        try {
            balanceAfter = Math.addExact(user.getPoint(), points);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(
                    "포인트 잔액이 허용 범위를 초과했습니다.",
                    exception
            );
        }

        user.changePoint(points);
        pointHistoryRepository.save(PointHistory.createDailyLoginBonus(
                user,
                points,
                balanceAfter,
                bonusDate,
                LocalDateTime.now(clock)
        ));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reverseSettlement(
            User user,
            UserPrediction prediction,
            PointHistory originalHistory
    ) {
        if (originalHistory.getPointChange() <= 0) {
            throw new IllegalArgumentException(
                    "Only positive settlement histories can be reversed"
            );
        }
        PointHistoryType reversalType = switch (originalHistory.getType()) {
            case PREDICTION_REWARD ->
                    PointHistoryType.PREDICTION_REWARD_ROLLBACK;
            case GAME_CANCEL_REFUND ->
                    PointHistoryType.GAME_CANCEL_REFUND_ROLLBACK;
            default -> throw new IllegalArgumentException(
                    "Unsupported settlement history type: "
                            + originalHistory.getType()
            );
        };
        applyChange(
                user,
                prediction.getGame(),
                prediction,
                originalHistory.getSettlement(),
                originalHistory,
                Math.negateExact(originalHistory.getPointChange()),
                reversalType,
                originalHistory.getDescription() + " 정산 원복"
        );
    }

    public List<PointHistoryResponse> getMyHistory(Long authenticatedUserId) {
        if (!userRepository.existsById(authenticatedUserId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "사용자를 찾을 수 없습니다."
            );
        }

        return pointHistoryRepository
                .findByUserIdOrderByCreatedAtDescIdDesc(authenticatedUserId)
                .stream()
                .map(PointHistoryResponse::from)
                .toList();
    }

    private void applyChange(
            User user,
            UserPrediction prediction,
            int pointChange,
            PointHistoryType type,
            String description
    ) {
        applyChange(
                user,
                prediction == null ? null : prediction.getGame(),
                prediction,
                prediction == null ? null : prediction.getSettlement(),
                null,
                pointChange,
                type,
                description
        );
    }

    private void applyChange(
            User user,
            com.playball.kbopredictor.game.entity.Game game,
            UserPrediction prediction,
            GameSettlement settlement,
            PointHistory reversalOf,
            int pointChange,
            PointHistoryType type,
            String description
    ) {
        if (user.getPoint() == null) {
            throw new IllegalStateException("사용자 포인트 잔액이 없습니다.");
        }

        final int balanceAfter;
        try {
            balanceAfter = Math.addExact(user.getPoint(), pointChange);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(
                    "포인트 잔액이 허용 범위를 초과했습니다.",
                    exception
            );
        }

        if (balanceAfter < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "보유 포인트가 부족합니다."
            );
        }

        user.changePoint(pointChange);
        pointHistoryRepository.save(PointHistory.create(
                user,
                game,
                prediction,
                settlement,
                reversalOf,
                pointChange,
                user.getPoint(),
                type,
                description,
                LocalDateTime.now(clock)
        ));
    }

    private String outcomeLabel(UserPrediction prediction) {
        PredictionOutcome outcome = prediction.getSelectedOutcome();
        return switch (outcome) {
            case HOME_WIN -> prediction.getGame().getHomeTeam().getName() + " 승";
            case DRAW -> "무승부";
            case AWAY_WIN -> prediction.getGame().getAwayTeam().getName() + " 승";
        };
    }
}
