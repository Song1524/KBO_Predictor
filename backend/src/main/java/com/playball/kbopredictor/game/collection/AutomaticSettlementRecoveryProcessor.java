package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.repository.GameSettlementRepository;
import com.playball.kbopredictor.prediction.repository.UserPredictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutomaticSettlementRecoveryProcessor {
    private final GameRepository games;
    private final UserPredictionRepository predictions;
    private final GameSettlementRepository settlements;
    private final GameUpsertService upsertService;
    private final GameSettlementCoordinator coordinator;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GameSettlementTriggerResult recover(Long gameId, CollectedGame collected) {
        Game game = games.findByIdForUpdate(gameId).orElseThrow();
        // Recheck after HTTP: settlement/rollback may have happened while collecting.
        LocalDateTime now = LocalDateTime.now(clock);
        if (game.getGameDate().isAfter(now.toLocalDate())
                || (game.getGameDate().equals(now.toLocalDate())
                    && (game.getGameTime() == null || game.getGameTime().isAfter(now.toLocalTime())))
                || settlements.findFirstByGameIdOrderByRevisionDesc(gameId).isPresent()
                || predictions.existsByGameIdAndSettledTrue(gameId)
                || !predictions.existsByGameIdAndSettledFalse(gameId)) {
            return GameSettlementTriggerResult.NOT_REQUIRED;
        }
        if (!matches(game, collected)) {
            throw new IllegalArgumentException("Collected result does not identify recovery game " + gameId);
        }
        boolean terminal = game.getStatus() == GameStatus.FINISHED
                || game.getStatus() == GameStatus.CANCELLED;
        if (terminal && (game.getResult() != null || game.getStatus() == GameStatus.CANCELLED)
                && (game.getStatus() != collected.status()
                    || (collected.finalScoreConfirmed()
                        && (!Objects.equals(game.getResult(), collected.result())
                            || !Objects.equals(game.getHomeScore(), collected.homeScore())
                            || !Objects.equals(game.getAwayScore(), collected.awayScore()))))) {
            log.warn("Automatic settlement recovery requires result review: gameId={}", gameId);
            return GameSettlementTriggerResult.CORRECTION_REQUIRES_REVIEW;
        }
        GameUpsertResult updated = upsertService.upsertWithinTransaction(collected);
        if (!gameId.equals(updated.gameId())) {
            throw new IllegalStateException("Recovery upsert resolved a different game " + updated.gameId());
        }
        return coordinator.settleIfNecessary(updated);
    }

    static boolean matches(Game game, CollectedGame collected) {
        return game.getGameDate().equals(collected.gameDate())
                && game.getHomeTeam().getKboTeamCode().equals(collected.homeTeamCode())
                && game.getAwayTeam().getKboTeamCode().equals(collected.awayTeamCode())
                && (game.getExternalGameId() != null
                    ? game.getExternalGameId().equals(collected.externalGameId())
                    : game.getGameTime() != null && game.getGameTime().equals(collected.gameTime()));
    }
}
