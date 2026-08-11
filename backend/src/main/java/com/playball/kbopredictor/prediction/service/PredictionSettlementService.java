package com.playball.kbopredictor.prediction.service;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.point.service.PointService;
import com.playball.kbopredictor.prediction.dto.PredictionSettlementResponse;
import com.playball.kbopredictor.prediction.entity.GameOdds;
import com.playball.kbopredictor.prediction.entity.UserPrediction;
import com.playball.kbopredictor.prediction.repository.UserPredictionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PredictionSettlementService {

    private final GameRepository gameRepository;
    private final UserPredictionRepository userPredictionRepository;
    private final GameOddsService gameOddsService;
    private final OddsCalculator oddsCalculator;
    private final PointService pointService;

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

        for (UserPrediction prediction : predictions) {
            if (cancelled) {
                int refundPoint = prediction.getPointAmount();
                pointService.refundCancelledGame(
                        prediction.getUser(),
                        prediction
                );
                prediction.refund();
                refundedCount++;
                totalPaidPoints += refundPoint;
            } else if (prediction.getSelectedOutcome().matches(game.getResult())) {
                int payout = oddsCalculator.calculatePayout(
                        prediction.getPointAmount(),
                        finalOdds.getFinalOdds(prediction.getSelectedOutcome())
                );
                pointService.rewardPrediction(
                        prediction.getUser(),
                        prediction,
                        payout
                );
                prediction.settleWon();
                correctCount++;
                totalPaidPoints += payout;
            } else {
                prediction.settleLost();
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

        validateWinnerTeam(game);
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
