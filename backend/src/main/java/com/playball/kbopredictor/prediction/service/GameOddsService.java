package com.playball.kbopredictor.prediction.service;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.dto.GameOddsResponse;
import com.playball.kbopredictor.prediction.dto.OutcomeOddsResponse;
import com.playball.kbopredictor.prediction.entity.GameOdds;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.repository.GameOddsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class GameOddsService {

    private final GameOddsRepository gameOddsRepository;
    private final GameRepository gameRepository;
    private final OddsCalculator oddsCalculator;
    private final Clock clock;

    @Transactional
    public GameOddsResponse getOddsByGameId(Long gameId) {
        Game game = findGameForUpdate(gameId);
        GameOdds odds = getOrCreateForUpdate(game);
        finalizeIfClosed(game, odds);
        return toResponse(game, odds);
    }

    @Transactional
    public Map<Long, GameOddsResponse> getOddsForGames(List<Game> games) {
        Map<Long, GameOddsResponse> result = new LinkedHashMap<>();
        for (Game game : games) {
            Game lockedGame = findGameForUpdate(game.getId());
            GameOdds odds = getOrCreateForUpdate(lockedGame);
            finalizeIfClosed(lockedGame, odds);
            result.put(game.getId(), toResponse(lockedGame, odds));
        }
        return result;
    }

    @Transactional
    public void placeBet(
            Game lockedGame,
            PredictionOutcome outcome,
            int pointAmount
    ) {
        GameOdds odds = getOrCreateForUpdate(lockedGame);
        finalizeIfClosed(lockedGame, odds);
        if (!isBettingOpen(lockedGame, odds)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "예측 참여가 마감되어 더 이상 참여할 수 없습니다."
            );
        }
        odds.addBet(outcome, pointAmount, now());
    }

    @Transactional
    public GameOdds finalizeForSettlement(Game lockedGame) {
        GameOdds odds = getOrCreateForUpdate(lockedGame);
        finalizeOdds(odds);
        return odds;
    }

    @Transactional
    public boolean finalizeExpiredGame(Long gameId) {
        Game game = findGameForUpdate(gameId);
        GameOdds odds = getOrCreateForUpdate(game);
        LocalDateTime closeAt = game.getPredictionCloseAt();

        if (odds.isFinalized() || closeAt == null || now().isBefore(closeAt)) {
            return false;
        }

        finalizeOdds(odds);
        log.info(
                "Finalized game odds: gameId={}, closeAt={}, totalBetPoints={}, " +
                        "homeWinOdds={}, drawOdds={}, awayWinOdds={}, finalizedAt={}",
                game.getId(),
                closeAt,
                odds.getTotalBetPoints(),
                odds.getFinalHomeWinOdds(),
                odds.getFinalDrawOdds(),
                odds.getFinalAwayWinOdds(),
                odds.getFinalizedAt()
        );
        return true;
    }

    private Game findGameForUpdate(Long gameId) {
        return gameRepository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "경기를 찾을 수 없습니다."
                ));
    }

    private GameOdds getOrCreateForUpdate(Game game) {
        return gameOddsRepository.findByGameIdForUpdate(game.getId())
                .orElseGet(() -> gameOddsRepository.saveAndFlush(
                        GameOdds.create(game, now())
                ));
    }

    private void finalizeIfClosed(Game game, GameOdds odds) {
        LocalDateTime closeAt = game.getPredictionCloseAt();
        boolean deadlineReached = closeAt != null && !now().isBefore(closeAt);
        if (!odds.isFinalized() &&
                (game.getStatus() != GameStatus.SCHEDULED || deadlineReached)) {
            finalizeOdds(odds);
        }
    }

    private void finalizeOdds(GameOdds odds) {
        if (odds.isFinalized()) {
            return;
        }

        long total = odds.getTotalBetPoints();
        odds.finalizeOdds(
                oddsCalculator.calculateOdds(total, odds.getHomeWinPoints()),
                oddsCalculator.calculateOdds(total, odds.getDrawPoints()),
                oddsCalculator.calculateOdds(total, odds.getAwayWinPoints()),
                now()
        );
    }

    private GameOddsResponse toResponse(Game game, GameOdds odds) {
        long total = odds.getTotalBetPoints();
        boolean bettingOpen = isBettingOpen(game, odds);

        return new GameOddsResponse(
                game.getId(),
                total,
                optionResponse(odds, PredictionOutcome.HOME_WIN, total),
                optionResponse(odds, PredictionOutcome.DRAW, total),
                optionResponse(odds, PredictionOutcome.AWAY_WIN, total),
                bettingOpen,
                odds.isFinalized(),
                game.getPredictionCloseAt(),
                odds.getFinalizedAt()
        );
    }

    private OutcomeOddsResponse optionResponse(
            GameOdds odds,
            PredictionOutcome outcome,
            long total
    ) {
        long points = odds.getBetPoints(outcome);
        BigDecimal currentOdds = odds.isFinalized()
                ? odds.getFinalOdds(outcome)
                : oddsCalculator.calculateOdds(total, points);

        return new OutcomeOddsResponse(
                outcome,
                points,
                oddsCalculator.calculateBettingRate(total, points),
                currentOdds
        );
    }

    private boolean isBettingOpen(Game game, GameOdds odds) {
        LocalDateTime closeAt = game.getPredictionCloseAt();
        return game.getStatus() == GameStatus.SCHEDULED &&
                !odds.isFinalized() &&
                (closeAt == null || now().isBefore(closeAt));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
