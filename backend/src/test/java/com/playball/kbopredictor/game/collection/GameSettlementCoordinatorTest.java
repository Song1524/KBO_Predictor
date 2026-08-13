package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.prediction.dto.PredictionSettlementResponse;
import com.playball.kbopredictor.prediction.repository.UserPredictionRepository;
import com.playball.kbopredictor.prediction.service.PredictionSettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameSettlementCoordinatorTest {

    @Mock
    private UserPredictionRepository userPredictionRepository;

    @Mock
    private PredictionSettlementService predictionSettlementService;

    private GameSettlementCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new GameSettlementCoordinator(
                userPredictionRepository,
                predictionSettlementService
        );
    }

    @Test
    void finishedTransitionTriggersSettlement() {
        GameUpsertResult result = result(
                GameStatus.IN_PROGRESS,
                GameStatus.FINISHED,
                null,
                GameResult.HOME_WIN,
                false
        );
        when(userPredictionRepository.existsByGameIdAndSettledFalse(1L))
                .thenReturn(true);
        when(predictionSettlementService.settleGame(1L))
                .thenReturn(settlement(false));

        assertThat(coordinator.settleIfNecessary(result))
                .isEqualTo(GameSettlementTriggerResult.SETTLED);
        verify(predictionSettlementService).settleGame(1L);
    }

    @Test
    void cancelledTransitionTriggersRefundSettlement() {
        GameUpsertResult result = result(
                GameStatus.SCHEDULED,
                GameStatus.CANCELLED,
                null,
                null,
                false
        );
        when(userPredictionRepository.existsByGameIdAndSettledFalse(1L))
                .thenReturn(true);
        when(predictionSettlementService.settleGame(1L))
                .thenReturn(settlement(true));

        assertThat(coordinator.settleIfNecessary(result))
                .isEqualTo(GameSettlementTriggerResult.SETTLED);
        verify(predictionSettlementService).settleGame(1L);
    }

    @Test
    void repeatedTerminalDataWithoutPendingPredictionsIsSkipped() {
        GameUpsertResult result = result(
                GameStatus.FINISHED,
                GameStatus.FINISHED,
                GameResult.HOME_WIN,
                GameResult.HOME_WIN,
                false
        );

        assertThat(coordinator.settleIfNecessary(result))
                .isEqualTo(GameSettlementTriggerResult.NOT_REQUIRED);
        verify(predictionSettlementService, never()).settleGame(1L);
    }

    @Test
    void newlyImportedHistoricalResultWithoutPredictionsIsSkipped() {
        GameUpsertResult result = result(
                null,
                GameStatus.FINISHED,
                null,
                GameResult.AWAY_WIN,
                false
        );

        assertThat(coordinator.settleIfNecessary(result))
                .isEqualTo(GameSettlementTriggerResult.NOT_REQUIRED);
        verify(predictionSettlementService, never()).settleGame(1L);
    }

    @Test
    void pendingPredictionsRetrySettlementAfterEarlierFailure() {
        GameUpsertResult result = result(
                GameStatus.FINISHED,
                GameStatus.FINISHED,
                GameResult.HOME_WIN,
                GameResult.HOME_WIN,
                false
        );
        when(userPredictionRepository.existsByGameIdAndSettledFalse(1L))
                .thenReturn(true);
        when(predictionSettlementService.settleGame(1L))
                .thenReturn(settlement(false));

        assertThat(coordinator.settleIfNecessary(result))
                .isEqualTo(GameSettlementTriggerResult.SETTLED);
        verify(predictionSettlementService).settleGame(1L);
    }

    @Test
    void finishedWithoutConfirmedScoreKeepsSettlementPending() {
        GameUpsertResult result = new GameUpsertResult(
                GameUpsertAction.UPDATED,
                1L,
                GameStatus.IN_PROGRESS,
                GameStatus.FINISHED,
                null,
                null,
                false,
                false
        );

        assertThat(coordinator.settleIfNecessary(result))
                .isEqualTo(GameSettlementTriggerResult.RESULT_PENDING);
        verify(predictionSettlementService, never()).settleGame(1L);
    }

    @Test
    void falseFinishedCorrectionToScheduledDoesNotTouchSettlement() {
        GameUpsertResult result = new GameUpsertResult(
                GameUpsertAction.UPDATED,
                1L,
                GameStatus.FINISHED,
                GameStatus.SCHEDULED,
                null,
                null,
                true,
                false
        );

        assertThat(coordinator.settleIfNecessary(result))
                .isEqualTo(GameSettlementTriggerResult.NOT_REQUIRED);
        verify(predictionSettlementService, never()).settleGame(1L);
    }

    @Test
    void laterConfirmedScoreSettlesPendingPredictionOnlyOnce() {
        GameUpsertResult unconfirmed = new GameUpsertResult(
                GameUpsertAction.UPDATED,
                1L,
                GameStatus.IN_PROGRESS,
                GameStatus.FINISHED,
                null,
                null,
                false,
                false
        );
        GameUpsertResult confirmed = result(
                GameStatus.FINISHED,
                GameStatus.FINISHED,
                null,
                GameResult.AWAY_WIN,
                false
        );
        when(userPredictionRepository.existsByGameIdAndSettledFalse(1L))
                .thenReturn(true, false);
        when(predictionSettlementService.settleGame(1L))
                .thenReturn(settlement(false));

        assertThat(coordinator.settleIfNecessary(unconfirmed))
                .isEqualTo(GameSettlementTriggerResult.RESULT_PENDING);
        assertThat(coordinator.settleIfNecessary(confirmed))
                .isEqualTo(GameSettlementTriggerResult.SETTLED);
        assertThat(coordinator.settleIfNecessary(confirmed))
                .isEqualTo(GameSettlementTriggerResult.NOT_REQUIRED);
        verify(predictionSettlementService).settleGame(1L);
    }

    @Test
    void correctedResultAfterSettlementRequiresReviewAndDoesNotResettle() {
        GameUpsertResult result = result(
                GameStatus.FINISHED,
                GameStatus.FINISHED,
                GameResult.HOME_WIN,
                GameResult.AWAY_WIN,
                true
        );
        when(userPredictionRepository.existsByGameIdAndSettledTrue(1L))
                .thenReturn(true);

        assertThat(coordinator.settleIfNecessary(result))
                .isEqualTo(
                        GameSettlementTriggerResult.CORRECTION_REQUIRES_REVIEW
                );
        verify(predictionSettlementService, never()).settleGame(1L);
    }

    private GameUpsertResult result(
            GameStatus previousStatus,
            GameStatus currentStatus,
            GameResult previousResult,
            GameResult currentResult,
            boolean terminalDataChanged
    ) {
        return new GameUpsertResult(
                GameUpsertAction.UPDATED,
                1L,
                previousStatus,
                currentStatus,
                previousResult,
                currentResult,
                terminalDataChanged,
                currentStatus == GameStatus.FINISHED
        );
    }

    private PredictionSettlementResponse settlement(boolean cancelled) {
        return new PredictionSettlementResponse(
                1L,
                cancelled ? null : GameResult.HOME_WIN,
                cancelled,
                cancelled ? null : 10L,
                cancelled ? null : "홈팀",
                1,
                cancelled ? 0 : 1,
                0,
                cancelled ? 1 : 0,
                200
        );
    }
}
