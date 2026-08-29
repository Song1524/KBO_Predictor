package com.playball.kbopredictor.prediction.service;

import com.playball.kbopredictor.common.config.TimeConfig;
import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.point.service.PointService;
import com.playball.kbopredictor.point.service.UserPointLockService;
import com.playball.kbopredictor.prediction.dto.PredictionSettlementResponse;
import com.playball.kbopredictor.prediction.entity.GameOdds;
import com.playball.kbopredictor.prediction.entity.UserPrediction;
import com.playball.kbopredictor.prediction.repository.UserPredictionRepository;
import com.playball.kbopredictor.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PredictionSettlementService {

    private final GameRepository gameRepository;
    private final UserPredictionRepository userPredictionRepository;
    private final UserPointLockService userPointLockService;
    private final GameOddsService gameOddsService;
    private final OddsCalculator oddsCalculator;
    private final PointService pointService;
    private final Clock clock;

    @Transactional
    public PredictionSettlementResponse settleGame(Long gameId) {

        Game game = gameRepository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "경기를 찾을 수 없습니다."
                ));

        validateGame(game);

        GameOdds finalOdds = gameOddsService.finalizeForSettlement(game);

        List<UserPrediction> predictions =
                userPredictionRepository.findByGameIdAndSettledFalse(gameId);

        int correctCount = 0;
        int incorrectCount = 0;
        int refundedCount = 0;
        long totalPaidPoints = 0;
        boolean cancelled = game.getStatus() == GameStatus.CANCELLED;
        LocalDateTime settledAt = LocalDateTime.ofInstant(
                clock.instant(),
                TimeConfig.KOREA_ZONE
        );

        for (UserPrediction prediction : predictions) {
            if (cancelled) {
                int refundPoint = prediction.getPointAmount();
                User lockedUser = findUserForUpdate(prediction);
                pointService.refundCancelledGame(
                        lockedUser,
                        prediction
                );
                prediction.refund(settledAt);
                refundedCount++;
                totalPaidPoints += refundPoint;
            } else if (prediction.getSelectedOutcome().matches(game.getResult())) {
                int payout = oddsCalculator.calculatePayout(
                        prediction.getPointAmount(),
                        finalOdds.getFinalOdds(prediction.getSelectedOutcome())
                );
                User lockedUser = findUserForUpdate(prediction);
                pointService.rewardPrediction(
                        lockedUser,
                        prediction,
                        payout
                );
                prediction.settleWon(settledAt);
                correctCount++;
                totalPaidPoints += payout;
            } else {
                prediction.settleLost(settledAt);
                incorrectCount++;
            }
        }

        Long winnerTeamId = game.getWinnerTeam() == null
                ? null
                : game.getWinnerTeam().getId();
        String winnerTeamName = game.getWinnerTeam() == null
                ? null
                : game.getWinnerTeam().getName();

        return new PredictionSettlementResponse(
                game.getId(),
                game.getResult(),
                cancelled,
                winnerTeamId,
                winnerTeamName,
                predictions.size(),
                correctCount,
                incorrectCount,
                refundedCount,
                totalPaidPoints
        );
    }

    private User findUserForUpdate(UserPrediction prediction) {
        return userPointLockService.findByIdForUpdate(
                prediction.getUser().getId()
        );
    }

    private void validateGame(Game game) {

        if (game.getStatus() == GameStatus.CANCELLED) {
            return;
        }

        if (game.getStatus() != GameStatus.FINISHED) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "종료된 경기만 정산할 수 있습니다."
            );
        }

        if (game.getResult() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "경기 결과가 등록되지 않았습니다."
            );
        }

        validateFinalScore(game);
        validateWinnerTeam(game);
    }

    private void validateFinalScore(Game game) {
        Integer homeScore = game.getHomeScore();
        Integer awayScore = game.getAwayScore();
        if (homeScore == null || awayScore == null
                || homeScore < 0 || awayScore < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Final score is not available"
            );
        }

        GameResult scoreResult;
        if (homeScore > awayScore) {
            scoreResult = GameResult.HOME_WIN;
        } else if (homeScore < awayScore) {
            scoreResult = GameResult.AWAY_WIN;
        } else {
            scoreResult = GameResult.DRAW;
        }
        if (scoreResult != game.getResult()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Final score and game result do not match"
            );
        }
    }

    private void validateWinnerTeam(Game game) {
        if (game.getResult() == GameResult.DRAW) {
            if (game.getWinnerTeam() != null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "무승부 경기에는 승리 팀을 지정할 수 없습니다."
                );
            }
            return;
        }

        if (game.getWinnerTeam() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "승리 팀이 등록되지 않았습니다."
            );
        }

        Long expectedWinnerId = game.getResult() == GameResult.HOME_WIN
                ? game.getHomeTeam().getId()
                : game.getAwayTeam().getId();

        if (!expectedWinnerId.equals(game.getWinnerTeam().getId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "경기 결과와 승리 팀이 일치하지 않습니다."
            );
        }
    }
}
