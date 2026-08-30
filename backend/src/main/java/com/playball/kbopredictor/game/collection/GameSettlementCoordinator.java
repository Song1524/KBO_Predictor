package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.prediction.dto.PredictionSettlementResponse;
import com.playball.kbopredictor.prediction.entity.GameSettlementState;
import com.playball.kbopredictor.prediction.repository.GameSettlementRepository;
import com.playball.kbopredictor.prediction.repository.UserPredictionRepository;
import com.playball.kbopredictor.prediction.service.PredictionSettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class GameSettlementCoordinator {

    private final UserPredictionRepository userPredictionRepository;
    private final GameSettlementRepository gameSettlementRepository;
    private final PredictionSettlementService predictionSettlementService;

    public GameSettlementTriggerResult settleIfNecessary(
            GameUpsertResult upsertResult
    ) {
        Long gameId = upsertResult.gameId();
        boolean hasSettledPredictions = gameId != null
                && userPredictionRepository.existsByGameIdAndSettledTrue(gameId);

        if (upsertResult.terminalDataChanged() && hasSettledPredictions) {
            log.warn(
                    "이미 정산된 경기의 KBO 결과 정정 감지 - 자동 재정산 생략, 관리자 확인 필요: gameId={}, previousStatus={}, currentStatus={}, previousResult={}, currentResult={}",
                    gameId,
                    upsertResult.previousStatus(),
                    upsertResult.currentStatus(),
                    upsertResult.previousResult(),
                    upsertResult.currentResult()
            );
            return GameSettlementTriggerResult.CORRECTION_REQUIRES_REVIEW;
        }

        boolean recoveryPending = gameId != null
                && gameSettlementRepository
                .findFirstByGameIdOrderByRevisionDesc(gameId)
                .filter(settlement -> settlement.getState()
                        == GameSettlementState.ROLLED_BACK)
                .isPresent();
        if (recoveryPending) {
            log.warn(
                    "관리자 rollback 이후 수동 재정산 대기 중 - 자동 정산 생략: gameId={}",
                    gameId
            );
            return GameSettlementTriggerResult.CORRECTION_REQUIRES_REVIEW;
        }

        if (!upsertResult.currentlyTerminal()) {
            return GameSettlementTriggerResult.NOT_REQUIRED;
        }

        if (upsertResult.currentStatus() == GameStatus.FINISHED
                && !upsertResult.finalScoreConfirmed()) {
            log.warn(
                    "KBO final score is not confirmed; settlement is pending: gameId={}",
                    gameId
            );
            return GameSettlementTriggerResult.RESULT_PENDING;
        }

        boolean hasPendingPredictions = gameId != null
                && userPredictionRepository.existsByGameIdAndSettledFalse(gameId);
        if (!hasPendingPredictions) {
            return GameSettlementTriggerResult.NOT_REQUIRED;
        }

        PredictionSettlementResponse response =
                predictionSettlementService.settleGame(gameId);
        log.info(
                "KBO 경기 자동 정산 완료: gameId={}, cancelled={}, predictions={}, correct={}, incorrect={}, refunded={}, paidPoints={}",
                gameId,
                response.cancelled(),
                response.totalCount(),
                response.correctCount(),
                response.incorrectCount(),
                response.refundedCount(),
                response.totalPaidPoints()
        );
        return GameSettlementTriggerResult.SETTLED;
    }
}
