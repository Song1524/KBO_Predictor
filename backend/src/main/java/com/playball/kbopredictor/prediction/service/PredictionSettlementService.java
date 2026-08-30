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
import com.playball.kbopredictor.prediction.entity.GameSettlement;
import com.playball.kbopredictor.prediction.entity.GameSettlementSource;
import com.playball.kbopredictor.prediction.entity.GameSettlementState;
import com.playball.kbopredictor.prediction.entity.UserPrediction;
import com.playball.kbopredictor.prediction.repository.GameSettlementRepository;
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
    private final GameSettlementRepository gameSettlementRepository;
    private final UserPointLockService userPointLockService;
    private final GameOddsService gameOddsService;
    private final OddsCalculator oddsCalculator;
    private final PointService pointService;
    private final Clock clock;

    @Transactional
    public PredictionSettlementResponse settleGame(Long gameId) {
        return settleGame(
                gameId,
                null,
                GameSettlementSource.AUTOMATIC,
                null
        );
    }

    @Transactional
    public PredictionSettlementResponse settleGame(
            Long gameId,
            Long adminUserId
    ) {
        return settleGame(gameId, adminUserId, null);
    }

    @Transactional
    public PredictionSettlementResponse settleGame(
            Long gameId,
            Long adminUserId,
            Integer rollbackRevision
    ) {
        if (adminUserId == null) {
            throw new IllegalArgumentException("adminUserId is required");
        }
        return settleGame(
                gameId,
                adminUserId,
                GameSettlementSource.ADMIN,
                rollbackRevision
        );
    }

    private PredictionSettlementResponse settleGame(
            Long gameId,
            Long actorUserId,
            GameSettlementSource source,
            Integer rollbackRevision
    ) {

        Game game = gameRepository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "경기를 찾을 수 없습니다."
                ));

        validateGame(game);

        GameSettlement latestSettlement = gameSettlementRepository
                .findFirstByGameIdOrderByRevisionDesc(gameId)
                .orElse(null);
        if (latestSettlement != null
                && latestSettlement.getState()
                == GameSettlementState.ROLLED_BACK) {
            validateRecoveryResettlement(
                    source,
                    rollbackRevision,
                    latestSettlement,
                    game
            );
        } else if (rollbackRevision != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "rollback 대기 중인 정산 회차가 없습니다."
            );
        }

        GameOdds finalOdds = gameOddsService.finalizeForSettlement(game);

        List<UserPrediction> predictions =
                userPredictionRepository
                        .findByGameIdAndSettledFalseOrderByUserIdAscIdAsc(gameId);

        int correctCount = 0;
        int incorrectCount = 0;
        int refundedCount = 0;
        long totalPaidPoints = 0;
        boolean cancelled = game.getStatus() == GameStatus.CANCELLED;
        LocalDateTime settledAt = LocalDateTime.ofInstant(
                clock.instant(),
                TimeConfig.KOREA_ZONE
        );

        if (predictions.isEmpty()) {
            return response(
                    game,
                    latestSettlement == null
                            ? null
                            : latestSettlement.getRevision(),
                    0,
                    0,
                    0,
                    0,
                    0
            );
        }
        if (latestSettlement != null
                && latestSettlement.getState() == GameSettlementState.SETTLED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "활성 정산 회차가 존재합니다. 먼저 rollback이 필요합니다."
            );
        }

        int revision = latestSettlement == null
                ? 1
                : latestSettlement.getRevision() + 1;
        GameSettlement settlement = gameSettlementRepository.saveAndFlush(
                GameSettlement.start(
                        game,
                        revision,
                        source,
                        actorUserId,
                        settledAt
                )
        );

        for (UserPrediction prediction : predictions) {
            if (cancelled) {
                int refundPoint = prediction.getPointAmount();
                prediction.refund(settledAt, settlement);
                User lockedUser = findUserForUpdate(prediction);
                pointService.refundCancelledGame(
                        lockedUser,
                        prediction
                );
                refundedCount++;
                totalPaidPoints += refundPoint;
            } else if (prediction.getSelectedOutcome().matches(game.getResult())) {
                int payout = oddsCalculator.calculatePayout(
                        prediction.getPointAmount(),
                        finalOdds.getFinalOdds(prediction.getSelectedOutcome())
                );
                prediction.settleWon(settledAt, settlement);
                User lockedUser = findUserForUpdate(prediction);
                pointService.rewardPrediction(
                        lockedUser,
                        prediction,
                        payout
                );
                correctCount++;
                totalPaidPoints += payout;
            } else {
                prediction.settleLost(settledAt, settlement);
                incorrectCount++;
            }
        }

        settlement.complete(
                predictions.size(),
                correctCount,
                incorrectCount,
                refundedCount,
                totalPaidPoints
        );

        return response(
                game,
                revision,
                predictions.size(),
                correctCount,
                incorrectCount,
                refundedCount,
                totalPaidPoints
        );
    }

    private PredictionSettlementResponse response(
            Game game,
            Integer settlementRevision,
            int totalCount,
            int correctCount,
            int incorrectCount,
            int refundedCount,
            long totalPaidPoints
    ) {
        Long winnerTeamId = game.getWinnerTeam() == null
                ? null
                : game.getWinnerTeam().getId();
        String winnerTeamName = game.getWinnerTeam() == null
                ? null
                : game.getWinnerTeam().getName();

        return new PredictionSettlementResponse(
                game.getId(),
                settlementRevision,
                game.getResult(),
                game.getStatus() == GameStatus.CANCELLED,
                winnerTeamId,
                winnerTeamName,
                totalCount,
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

    private void validateRecoveryResettlement(
            GameSettlementSource source,
            Integer rollbackRevision,
            GameSettlement latestSettlement,
            Game game
    ) {
        if (source != GameSettlementSource.ADMIN) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "관리자 rollback 이후에는 관리자 재정산이 필요합니다."
            );
        }
        if (rollbackRevision == null
                || rollbackRevision != latestSettlement.getRevision()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "rollback 회차를 확인한 관리자 재정산 요청이 필요합니다."
            );
        }
        if (latestSettlement.getResultCorrectedAt() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "경기 결과를 관리자 정정한 후 재정산할 수 있습니다."
            );
        }
        if (game.getStatus() != latestSettlement.getCorrectedGameStatus()
                || game.getResult() != latestSettlement.getCorrectedGameResult()
                || !java.util.Objects.equals(
                        game.getHomeScore(),
                        latestSettlement.getCorrectedHomeScore()
                )
                || !java.util.Objects.equals(
                        game.getAwayScore(),
                        latestSettlement.getCorrectedAwayScore()
                )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "관리자 결과 정정 이후 경기 데이터가 변경되었습니다."
            );
        }
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
