package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.GameStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.playball.kbopredictor.prediction.shadow.ShadowFinishedGameEvaluationService;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameSyncServiceTest {

    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 8, 12);

    @Mock
    private GameDataCollector gameDataCollector;

    @Mock
    private GameUpsertService gameUpsertService;

    @Mock
    private GameSettlementCoordinator gameSettlementCoordinator;

    @Mock
    private ShadowFinishedGameEvaluationService shadowEvaluationService;

    private GameSyncService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                TARGET_DATE.atTime(12, 0)
                        .atZone(ZoneId.of("Asia/Seoul"))
                        .toInstant(),
                ZoneId.of("Asia/Seoul")
        );
        service = new GameSyncService(
                gameDataCollector,
                gameUpsertService,
                gameSettlementCoordinator,
                shadowEvaluationService,
                clock
        );
    }

    @Test
    void oneBadGameDoesNotPreventOtherGamesFromBeingSaved() {
        CollectedGame first = scheduled("20260812LGOB0", "LG", "OB");
        CollectedGame second = scheduled("20260812HHKT0", "HH", "KT");
        when(gameDataCollector.collect(TARGET_DATE)).thenReturn(
                new GameCollectionBatch(
                        3,
                        List.of(first, second),
                        List.of("행 3: 알 수 없는 팀")
                )
        );
        when(gameUpsertService.upsert(first))
                .thenThrow(new IllegalStateException("DB 저장 실패"));
        when(gameUpsertService.upsert(second))
                .thenReturn(updated(2L, GameStatus.SCHEDULED, GameStatus.SCHEDULED));

        GameSyncResponse response = service.sync(TARGET_DATE);

        assertThat(response.sourceRowCount()).isEqualTo(3);
        assertThat(response.updatedCount()).isEqualTo(1);
        assertThat(response.insertedCount()).isZero();
        assertThat(response.failedCount()).isEqualTo(2);
        assertThat(response.errors()).hasSize(2);
        verify(gameUpsertService).upsert(first);
        verify(gameUpsertService).upsert(second);
        verify(gameSettlementCoordinator)
                .settleIfNecessary(updated(
                        2L,
                        GameStatus.SCHEDULED,
                        GameStatus.SCHEDULED
                ));
    }

    @Test
    void collectionFailureDoesNotAttemptAnyDatabaseMutation() {
        when(gameDataCollector.collect(TARGET_DATE))
                .thenThrow(new GameDataCollectionException("외부 API 실패"));

        assertThatThrownBy(() -> service.sync(TARGET_DATE))
                .isInstanceOf(GameDataCollectionException.class)
                .hasMessageContaining("외부 API 실패");

        verifyNoInteractions(gameUpsertService);
        verifyNoInteractions(gameSettlementCoordinator);
    }

    @Test
    void settlementFailureForOneGameDoesNotStopNextGame() {
        CollectedGame finished = collected(
                "20260812LGOB0",
                "LG",
                "OB",
                GameStatus.FINISHED
        );
        CollectedGame cancelled = collected(
                "20260812HHKT0",
                "HH",
                "KT",
                GameStatus.CANCELLED
        );
        when(gameDataCollector.collect(TARGET_DATE)).thenReturn(
                new GameCollectionBatch(2, List.of(finished, cancelled), List.of())
        );
        GameUpsertResult finishedResult = updated(
                1L,
                GameStatus.IN_PROGRESS,
                GameStatus.FINISHED
        );
        GameUpsertResult cancelledResult = updated(
                2L,
                GameStatus.SCHEDULED,
                GameStatus.CANCELLED
        );
        when(gameUpsertService.upsert(finished)).thenReturn(finishedResult);
        when(gameUpsertService.upsert(cancelled)).thenReturn(cancelledResult);
        when(gameSettlementCoordinator.settleIfNecessary(finishedResult))
                .thenThrow(new IllegalStateException("정산 DB 오류"));
        when(gameSettlementCoordinator.settleIfNecessary(cancelledResult))
                .thenReturn(GameSettlementTriggerResult.SETTLED);

        GameSyncResponse response = service.sync(TARGET_DATE);

        assertThat(response.updatedCount()).isEqualTo(2);
        assertThat(response.statusChangedCount()).isEqualTo(2);
        assertThat(response.finishedCount()).isEqualTo(1);
        assertThat(response.cancelledCount()).isEqualTo(1);
        assertThat(response.settlementSuccessCount()).isEqualTo(1);
        assertThat(response.failedCount()).isEqualTo(1);
        verify(gameSettlementCoordinator).settleIfNecessary(finishedResult);
        verify(gameSettlementCoordinator).settleIfNecessary(cancelledResult);
    }

    @Test
    void shadowEvaluationFailureDoesNotAffectSuccessfulSettlement() {
        CollectedGame finished = collected(
                "20260812LGOB0", "LG", "OB", GameStatus.FINISHED
        );
        GameUpsertResult finishedResult = updated(
                1L, GameStatus.IN_PROGRESS, GameStatus.FINISHED
        );
        when(gameDataCollector.collect(TARGET_DATE)).thenReturn(
                new GameCollectionBatch(1, List.of(finished), List.of())
        );
        when(gameUpsertService.upsert(finished)).thenReturn(finishedResult);
        when(gameSettlementCoordinator.settleIfNecessary(finishedResult))
                .thenReturn(GameSettlementTriggerResult.SETTLED);
        when(shadowEvaluationService.evaluateAndLog(1L))
                .thenThrow(new IllegalStateException("evaluation failure"));

        GameSyncResponse response = service.sync(TARGET_DATE);

        assertThat(response.settlementSuccessCount()).isEqualTo(1);
        assertThat(response.failedCount()).isZero();
        verify(gameSettlementCoordinator).settleIfNecessary(finishedResult);
        verify(shadowEvaluationService).evaluateAndLog(1L);
    }

    private CollectedGame scheduled(
            String externalGameId,
            String awayTeamCode,
            String homeTeamCode
    ) {
        return new CollectedGame(
                externalGameId,
                2026,
                TARGET_DATE,
                LocalTime.of(18, 30),
                awayTeamCode,
                homeTeamCode,
                "테스트 구장",
                GameStatus.SCHEDULED,
                null,
                null,
                null,
                null
        );
    }

    private CollectedGame collected(
            String externalGameId,
            String awayTeamCode,
            String homeTeamCode,
            GameStatus status
    ) {
        return new CollectedGame(
                externalGameId,
                2026,
                TARGET_DATE,
                LocalTime.of(18, 30),
                awayTeamCode,
                homeTeamCode,
                "테스트 구장",
                status,
                status == GameStatus.FINISHED ? 2 : null,
                status == GameStatus.FINISHED ? 5 : null,
                status == GameStatus.FINISHED
                        ? com.playball.kbopredictor.game.entity.GameResult.HOME_WIN
                        : null,
                status == GameStatus.CANCELLED ? "우천취소" : null
        );
    }

    private GameUpsertResult updated(
            Long gameId,
            GameStatus previousStatus,
            GameStatus currentStatus
    ) {
        return new GameUpsertResult(
                GameUpsertAction.UPDATED,
                gameId,
                previousStatus,
                currentStatus,
                null,
                null,
                false
        );
    }
}
