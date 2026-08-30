package com.playball.kbopredictor.prediction.service;

import com.playball.kbopredictor.common.config.TimeConfig;
import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.point.entity.PointHistory;
import com.playball.kbopredictor.point.entity.PointHistoryType;
import com.playball.kbopredictor.point.repository.PointHistoryRepository;
import com.playball.kbopredictor.point.service.PointService;
import com.playball.kbopredictor.point.service.UserPointLockService;
import com.playball.kbopredictor.prediction.dto.*;
import com.playball.kbopredictor.prediction.entity.*;
import com.playball.kbopredictor.prediction.repository.GameSettlementRepository;
import com.playball.kbopredictor.prediction.repository.UserPredictionRepository;
import com.playball.kbopredictor.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class GameSettlementRecoveryService {

    private final GameRepository gameRepository;
    private final GameSettlementRepository gameSettlementRepository;
    private final UserPredictionRepository userPredictionRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final UserPointLockService userPointLockService;
    private final PointService pointService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public GameSettlementStatusResponse getStatus(Long gameId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> notFound("경기를 찾을 수 없습니다."));
        GameSettlement latest = gameSettlementRepository
                .findFirstByGameIdOrderByRevisionDesc(gameId)
                .orElse(null);

        long settledCount = userPredictionRepository
                .countByGameIdAndSettledTrue(gameId);
        long pendingCount = userPredictionRepository
                .countByGameIdAndSettledFalse(gameId);
        boolean correctionReviewRequired = latest != null
                && (latest.getState() == GameSettlementState.SETTLED
                && resultSnapshotDiffers(game, latest)
                || latest.getState() == GameSettlementState.ROLLED_BACK
                && latest.getResultCorrectedAt() != null
                && correctedSnapshotDiffers(game, latest));
        boolean recoveryPending = latest != null
                && latest.getState() == GameSettlementState.ROLLED_BACK;

        return new GameSettlementStatusResponse(
                game.getId(),
                game.getStatus(),
                game.getResult(),
                game.getHomeScore(),
                game.getAwayScore(),
                latest == null ? null : latest.getRevision(),
                latest == null ? null : latest.getState(),
                latest == null ? null : latest.getSource(),
                latest == null ? null : latest.getGameStatus(),
                latest == null ? null : latest.getGameResult(),
                latest == null ? null : latest.getHomeScore(),
                latest == null ? null : latest.getAwayScore(),
                latest == null ? 0 : latest.getPredictionCount(),
                settledCount,
                pendingCount,
                latest == null ? null : latest.getSettledByUserId(),
                latest == null ? null : latest.getSettledAt(),
                latest == null ? null : latest.getRolledBackByUserId(),
                latest == null ? null : latest.getRolledBackAt(),
                latest == null ? null : latest.getRollbackReason(),
                latest == null ? null : latest.getResultCorrectedByUserId(),
                latest == null ? null : latest.getResultCorrectedAt(),
                latest == null ? null : latest.getResultCorrectionReason(),
                latest == null ? null : latest.getCorrectedGameStatus(),
                latest == null ? null : latest.getCorrectedGameResult(),
                latest == null ? null : latest.getCorrectedHomeScore(),
                latest == null ? null : latest.getCorrectedAwayScore(),
                correctionReviewRequired,
                recoveryPending
        );
    }

    @Transactional
    public PredictionSettlementRollbackResponse rollback(
            Long gameId,
            int settlementRevision,
            Long adminUserId,
            String reason
    ) {
        validateAdminAndReason(adminUserId, reason);
        Game game = gameRepository.findByIdForUpdate(gameId)
                .orElseThrow(() -> notFound("경기를 찾을 수 없습니다."));
        GameSettlement settlement = gameSettlementRepository
                .findByGameIdAndRevision(gameId, settlementRevision)
                .orElseThrow(() -> notFound("정산 회차를 찾을 수 없습니다."));

        if (settlement.getState() == GameSettlementState.ROLLED_BACK) {
            return rollbackResponse(settlement, true, 0);
        }

        GameSettlement latest = gameSettlementRepository
                .findFirstByGameIdOrderByRevisionDesc(gameId)
                .orElseThrow(() -> notFound("정산 이력을 찾을 수 없습니다."));
        if (!latest.getId().equals(settlement.getId())) {
            throw conflict("최신 정산 회차만 rollback할 수 있습니다.");
        }

        List<UserPrediction> predictions = userPredictionRepository
                .findBySettlementIdAndSettledTrueOrderByUserIdAscIdAsc(
                        settlement.getId()
                );
        if (predictions.size() != settlement.getPredictionCount()) {
            throw conflict("정산 예측 건수가 일치하지 않아 rollback할 수 없습니다.");
        }

        LocalDateTime now = now();
        int reversedHistoryCount = 0;
        long reversedPointTotal = 0;
        for (UserPrediction prediction : predictions) {
            PointHistory original = originalSettlementHistory(
                    prediction,
                    settlement
            );
            if (original != null) {
                if (pointHistoryRepository.existsByReversalOfId(original.getId())) {
                    throw conflict("이미 역분개된 포인트 이력이 있습니다.");
                }
                User lockedUser = userPointLockService.findByIdForUpdate(
                        prediction.getUser().getId()
                );
                if (lockedUser.getPoint() == null
                        || lockedUser.getPoint() < original.getPointChange()) {
                    throw conflict(
                            "사용자 잔액이 부족하여 정산을 원복할 수 없습니다: userId="
                                    + lockedUser.getId()
                    );
                }
                pointService.reverseSettlement(
                        lockedUser,
                        prediction,
                        original
                );
                reversedHistoryCount++;
                reversedPointTotal = Math.addExact(
                        reversedPointTotal,
                        original.getPointChange()
                );
            }
            prediction.rollbackSettlement(now);
        }

        settlement.rollback(
                adminUserId,
                reason,
                reversedPointTotal,
                now
        );
        log.info(
                "Admin settlement rollback completed: adminUserId={}, gameId={}, settlementRevision={}, restoredPredictions={}, reversedHistories={}, reversedPoints={}, reason={}",
                adminUserId,
                game.getId(),
                settlementRevision,
                predictions.size(),
                reversedHistoryCount,
                reversedPointTotal,
                normalizeReason(reason)
        );
        return rollbackResponse(
                settlement,
                false,
                reversedHistoryCount
        );
    }

    @Transactional
    public GameResultCorrectionResponse correctResult(
            Long gameId,
            Long adminUserId,
            GameResultCorrectionRequest request
    ) {
        validateAdminAndReason(adminUserId, request.reason());
        Game game = gameRepository.findByIdForUpdate(gameId)
                .orElseThrow(() -> notFound("경기를 찾을 수 없습니다."));
        GameSettlement settlement = gameSettlementRepository
                .findByGameIdAndRevision(
                        gameId,
                        request.settlementRevision()
                )
                .orElseThrow(() -> notFound("정산 회차를 찾을 수 없습니다."));
        GameSettlement latest = gameSettlementRepository
                .findFirstByGameIdOrderByRevisionDesc(gameId)
                .orElseThrow(() -> notFound("정산 이력을 찾을 수 없습니다."));
        if (!latest.getId().equals(settlement.getId())
                || settlement.getState() != GameSettlementState.ROLLED_BACK) {
            throw conflict("최신 정산을 rollback한 후에만 결과를 수정할 수 있습니다.");
        }

        var previousStatus = game.getStatus();
        var previousResult = game.getResult();
        Integer previousHomeScore = game.getHomeScore();
        Integer previousAwayScore = game.getAwayScore();
        LocalDateTime correctedAt = now();
        try {
            game.correctTerminalResult(
                    request.status(),
                    request.homeScore(),
                    request.awayScore(),
                    request.cancelReason(),
                    correctedAt
            );
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    exception.getMessage(),
                    exception
            );
        }
        settlement.recordResultCorrection(
                adminUserId,
                request.reason(),
                game,
                correctedAt
        );
        log.info(
                "Admin game result corrected: adminUserId={}, gameId={}, settlementRevision={}, previousStatus={}, previousResult={}, previousHomeScore={}, previousAwayScore={}, correctedStatus={}, correctedResult={}, correctedHomeScore={}, correctedAwayScore={}, reason={}",
                adminUserId,
                gameId,
                request.settlementRevision(),
                previousStatus,
                previousResult,
                previousHomeScore,
                previousAwayScore,
                game.getStatus(),
                game.getResult(),
                game.getHomeScore(),
                game.getAwayScore(),
                normalizeReason(request.reason())
        );

        return new GameResultCorrectionResponse(
                game.getId(),
                request.settlementRevision(),
                game.getStatus(),
                game.getResult(),
                game.getHomeScore(),
                game.getAwayScore(),
                game.getWinnerTeam() == null
                        ? null
                        : game.getWinnerTeam().getId(),
                game.getWinnerTeam() == null
                        ? null
                        : game.getWinnerTeam().getName(),
                adminUserId,
                request.reason().trim(),
                correctedAt
        );
    }

    private PointHistory originalSettlementHistory(
            UserPrediction prediction,
            GameSettlement settlement
    ) {
        PointHistoryType historyType = switch (prediction.getSettlementStatus()) {
            case WON -> PointHistoryType.PREDICTION_REWARD;
            case REFUNDED -> PointHistoryType.GAME_CANCEL_REFUND;
            case LOST -> null;
            case PENDING -> throw conflict(
                    "미정산 예측이 활성 정산 회차에 포함되어 있습니다."
            );
        };
        if (historyType == null) {
            return null;
        }
        return pointHistoryRepository
                .findByUserPredictionIdAndSettlementIdAndType(
                        prediction.getId(),
                        settlement.getId(),
                        historyType
                )
                .orElseThrow(() -> conflict(
                        "원본 지급/환불 이력이 없어 rollback할 수 없습니다."
                ));
    }

    private PredictionSettlementRollbackResponse rollbackResponse(
            GameSettlement settlement,
            boolean alreadyRolledBack,
            int reversedHistoryCount
    ) {
        return new PredictionSettlementRollbackResponse(
                settlement.getGame().getId(),
                settlement.getRevision(),
                alreadyRolledBack,
                alreadyRolledBack ? 0 : settlement.getPredictionCount(),
                reversedHistoryCount,
                settlement.getReversedPointTotal(),
                settlement.getRolledBackByUserId(),
                settlement.getRolledBackAt()
        );
    }

    private boolean resultSnapshotDiffers(
            Game game,
            GameSettlement settlement
    ) {
        return game.getStatus() != settlement.getGameStatus()
                || game.getResult() != settlement.getGameResult()
                || !Objects.equals(game.getHomeScore(), settlement.getHomeScore())
                || !Objects.equals(game.getAwayScore(), settlement.getAwayScore());
    }

    private boolean correctedSnapshotDiffers(
            Game game,
            GameSettlement settlement
    ) {
        return game.getStatus() != settlement.getCorrectedGameStatus()
                || game.getResult() != settlement.getCorrectedGameResult()
                || !Objects.equals(
                        game.getHomeScore(),
                        settlement.getCorrectedHomeScore()
                )
                || !Objects.equals(
                        game.getAwayScore(),
                        settlement.getCorrectedAwayScore()
                );
    }

    private void validateAdminAndReason(Long adminUserId, String reason) {
        if (adminUserId == null) {
            throw new IllegalArgumentException("adminUserId is required");
        }
        if (reason == null || reason.isBlank() || reason.length() > 255) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "사유는 1자 이상 255자 이하여야 합니다."
            );
        }
    }

    private String normalizeReason(String reason) {
        return reason.trim().replace('\n', ' ').replace('\r', ' ');
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), TimeConfig.KOREA_ZONE);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
