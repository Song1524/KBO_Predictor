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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SettlementCorrectionService {

    private static final long TARGET_PREDICTION_ID = 2L;
    private static final long TARGET_USER_ID = 2L;
    private static final String TARGET_EXTERNAL_GAME_ID = "20260812SSHT0";
    private static final PredictionOutcome TARGET_OUTCOME =
            PredictionOutcome.HOME_WIN;
    private static final int TARGET_POINT_AMOUNT = 100;
    private static final BigDecimal TARGET_FINAL_ODDS =
            new BigDecimal("2.00");
    private static final int TARGET_POINT_BEFORE = 900;
    private static final int TARGET_HOME_SCORE = 7;
    private static final int TARGET_AWAY_SCORE = 2;

    private final UserPredictionRepository userPredictionRepository;
    private final UserRepository userRepository;
    private final GameRepository gameRepository;
    private final GameOddsRepository gameOddsRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final OddsCalculator oddsCalculator;
    private final PointService pointService;

    @Transactional
    public SettlementCorrectionResponse correct(
            Long predictionId,
            SettlementCorrectionRequest request
    ) {
        validateCorrectionTarget(predictionId, request);

        UserPrediction prediction = userPredictionRepository
                .findByIdForUpdate(predictionId)
                .orElseThrow(() -> notFound("보정 대상 예측을 찾을 수 없습니다."));
        validatePredictionIdentity(prediction, request);

        User user = userRepository.findByIdForUpdate(request.expectedUserId())
                .orElseThrow(() -> notFound("보정 대상 사용자를 찾을 수 없습니다."));
        if (!user.getId().equals(prediction.getUser().getId())) {
            throw conflict("예측 사용자와 잠근 사용자 행이 일치하지 않습니다.");
        }

        Game game = gameRepository.findByIdForUpdate(prediction.getGame().getId())
                .orElseThrow(() -> notFound("보정 대상 경기를 찾을 수 없습니다."));
        validateFinalGame(game, request);

        GameOdds gameOdds = gameOddsRepository.findByGameIdForUpdate(game.getId())
                .orElseThrow(() -> notFound("보정 대상 경기의 최종 배당을 찾을 수 없습니다."));
        BigDecimal finalOdds = validateFinalOdds(gameOdds, request);
        int payout = oddsCalculator.calculatePayout(
                prediction.getPointAmount(),
                finalOdds
        );

        Optional<PointHistory> existingReward = pointHistoryRepository
                .findByUserPredictionIdAndType(
                        predictionId,
                        PointHistoryType.PREDICTION_REWARD
                );
        if (existingReward.isPresent()) {
            validateAlreadyApplied(prediction, existingReward.get(), payout);
            return response(
                    SettlementCorrectionStatus.ALREADY_APPLIED,
                    prediction,
                    user,
                    payout
            );
        }

        validateBeforeCorrection(prediction, user, request);

        pointService.rewardSettlementCorrection(user, prediction, payout);
        prediction.settleWon();

        return response(
                SettlementCorrectionStatus.APPLIED,
                prediction,
                user,
                payout
        );
    }

    private void validateCorrectionTarget(
            Long predictionId,
            SettlementCorrectionRequest request
    ) {
        if (predictionId == null || predictionId != TARGET_PREDICTION_ID) {
            throw conflict("이 보정 API는 prediction_id=2 전용입니다.");
        }
        if (!Long.valueOf(TARGET_USER_ID).equals(request.expectedUserId())
                || !TARGET_EXTERNAL_GAME_ID.equals(request.expectedExternalGameId())
                || TARGET_OUTCOME != request.expectedOutcome()
                || !Integer.valueOf(TARGET_POINT_AMOUNT).equals(request.expectedPointAmount())
                || TARGET_FINAL_ODDS.compareTo(request.expectedFinalOdds()) != 0
                || !Integer.valueOf(TARGET_POINT_BEFORE).equals(request.expectedCurrentPoint())) {
            throw conflict("요청의 예상값이 승인된 단일 보정 건과 일치하지 않습니다.");
        }
    }

    private void validatePredictionIdentity(
            UserPrediction prediction,
            SettlementCorrectionRequest request
    ) {
        if (!request.expectedUserId().equals(prediction.getUser().getId())) {
            throw conflict("예측 사용자 ID가 예상값과 일치하지 않습니다.");
        }
        if (!request.expectedExternalGameId().equals(
                prediction.getGame().getExternalGameId())) {
            throw conflict("예측 경기 externalGameId가 예상값과 일치하지 않습니다.");
        }
        if (request.expectedOutcome() != prediction.getSelectedOutcome()) {
            throw conflict("예측 선택 결과가 예상값과 일치하지 않습니다.");
        }
        if (!request.expectedPointAmount().equals(prediction.getPointAmount())) {
            throw conflict("예측 포인트가 예상값과 일치하지 않습니다.");
        }
    }

    private void validateFinalGame(
            Game game,
            SettlementCorrectionRequest request
    ) {
        if (!request.expectedExternalGameId().equals(game.getExternalGameId())) {
            throw conflict("잠근 경기의 externalGameId가 예상값과 일치하지 않습니다.");
        }
        if (game.getStatus() != GameStatus.FINISHED) {
            throw conflict("보정 대상 경기가 FINISHED 상태가 아닙니다.");
        }
        if (!Integer.valueOf(TARGET_HOME_SCORE).equals(game.getHomeScore())
                || !Integer.valueOf(TARGET_AWAY_SCORE).equals(game.getAwayScore())) {
            throw conflict("보정 대상 경기의 최종 점수가 KIA 7 : 2 삼성과 일치하지 않습니다.");
        }
        if (game.getResult() != GameResult.HOME_WIN
                || !request.expectedOutcome().matches(game.getResult())) {
            throw conflict("보정 대상 경기 결과가 HOME_WIN과 일치하지 않습니다.");
        }
        if (game.getHomeTeam() == null
                || game.getWinnerTeam() == null
                || !game.getHomeTeam().getId().equals(game.getWinnerTeam().getId())) {
            throw conflict("보정 대상 경기의 승리 팀이 홈팀과 일치하지 않습니다.");
        }
    }

    private BigDecimal validateFinalOdds(
            GameOdds gameOdds,
            SettlementCorrectionRequest request
    ) {
        if (!gameOdds.isFinalized()) {
            throw conflict("보정 대상 경기의 배당이 확정되지 않았습니다.");
        }
        BigDecimal finalOdds = gameOdds.getFinalOdds(request.expectedOutcome());
        if (finalOdds == null
                || request.expectedFinalOdds().compareTo(finalOdds) != 0) {
            throw conflict("최종 HOME 배당이 예상값과 일치하지 않습니다.");
        }
        return finalOdds;
    }

    private void validateBeforeCorrection(
            UserPrediction prediction,
            User user,
            SettlementCorrectionRequest request
    ) {
        if (!Boolean.TRUE.equals(prediction.getSettled())
                || !Boolean.FALSE.equals(prediction.getIsCorrect())
                || prediction.getSettlementStatus()
                != PredictionSettlementStatus.LOST) {
            throw conflict("예측이 보정 전 LOST 상태와 일치하지 않습니다.");
        }
        if (!request.expectedCurrentPoint().equals(user.getPoint())) {
            throw conflict("사용자 현재 포인트가 예상값과 일치하지 않습니다.");
        }
    }

    private void validateAlreadyApplied(
            UserPrediction prediction,
            PointHistory reward,
            int payout
    ) {
        if (!Boolean.TRUE.equals(prediction.getSettled())
                || !Boolean.TRUE.equals(prediction.getIsCorrect())
                || prediction.getSettlementStatus()
                != PredictionSettlementStatus.WON) {
            throw conflict("reward 이력은 있지만 예측 상태가 WON이 아닙니다.");
        }
        if (reward.getUser() == null
                || !Long.valueOf(TARGET_USER_ID).equals(reward.getUser().getId())
                || reward.getGame() == null
                || !prediction.getGame().getId().equals(reward.getGame().getId())
                || reward.getUserPrediction() == null
                || !prediction.getId().equals(reward.getUserPrediction().getId())
                || reward.getType() != PointHistoryType.PREDICTION_REWARD
                || reward.getPointChange() != payout
                || reward.getBalanceAfter() != TARGET_POINT_BEFORE + payout) {
            throw conflict("기존 reward 이력이 승인된 보정 내용과 일치하지 않습니다.");
        }
    }

    private SettlementCorrectionResponse response(
            SettlementCorrectionStatus status,
            UserPrediction prediction,
            User user,
            int payout
    ) {
        return new SettlementCorrectionResponse(
                status,
                prediction.getId(),
                user.getId(),
                prediction.getGame().getExternalGameId(),
                prediction.getIsCorrect(),
                prediction.getSettled(),
                prediction.getSettlementStatus(),
                payout,
                user.getPoint()
        );
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
