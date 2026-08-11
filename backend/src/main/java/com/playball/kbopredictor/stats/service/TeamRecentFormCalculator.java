package com.playball.kbopredictor.stats.service;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.stats.entity.TeamRecentFormValues;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamRecentFormCalculator {

    private final GameRepository gameRepository;

    public TeamRecentForm calculate(Long teamId, LocalDate cutoffExclusive) {
        List<Game> games = gameRepository.findTeamGamesBefore(
                teamId,
                GameStatus.FINISHED,
                cutoffExclusive,
                PageRequest.of(0, 10)
        ).stream()
                .filter(game -> game.getStatus() == GameStatus.FINISHED)
                .filter(game -> game.getGameDate() != null
                        && game.getGameDate().isBefore(cutoffExclusive))
                .filter(game -> game.getResult() != null)
                .filter(game -> game.getHomeScore() != null
                        && game.getAwayScore() != null)
                .limit(10)
                .toList();

        OutcomeCounts counts = countOutcomes(games, teamId);
        List<Game> recent5 = games.stream().limit(5).toList();

        TeamRecentFormValues values = new TeamRecentFormValues(
                calculateWinRate(recent5, teamId),
                calculateWinRate(games, teamId),
                averageRuns(recent5, teamId, true),
                averageRuns(recent5, teamId, false),
                averageRuns(games, teamId, true),
                averageRuns(games, teamId, false)
        );
        return new TeamRecentForm(
                counts.wins(),
                counts.losses(),
                counts.draws(),
                values
        );
    }

    private OutcomeCounts countOutcomes(List<Game> games, Long teamId) {
        int wins = 0;
        int losses = 0;
        int draws = 0;
        for (Game game : games) {
            if (game.getResult() == GameResult.DRAW) {
                draws++;
            } else if (isWin(game, teamId)) {
                wins++;
            } else {
                losses++;
            }
        }
        return new OutcomeCounts(wins, losses, draws);
    }

    private BigDecimal calculateWinRate(List<Game> games, Long teamId) {
        if (games.isEmpty()) {
            return null;
        }
        OutcomeCounts counts = countOutcomes(games, teamId);
        int decisions = counts.wins() + counts.losses();
        if (decisions == 0) {
            return null;
        }
        return BigDecimal.valueOf(counts.wins())
                .divide(BigDecimal.valueOf(decisions), 3, RoundingMode.HALF_UP);
    }

    private BigDecimal averageRuns(
            List<Game> games,
            Long teamId,
            boolean scored
    ) {
        if (games.isEmpty()) {
            return null;
        }

        int total = 0;
        for (Game game : games) {
            boolean home = game.getHomeTeam().getId().equals(teamId);
            Integer runs = scored
                    ? (home ? game.getHomeScore() : game.getAwayScore())
                    : (home ? game.getAwayScore() : game.getHomeScore());
            if (runs == null) {
                return null;
            }
            total += runs;
        }
        return BigDecimal.valueOf(total)
                .divide(BigDecimal.valueOf(games.size()), 2, RoundingMode.HALF_UP);
    }

    private boolean isWin(Game game, Long teamId) {
        return (game.getResult() == GameResult.HOME_WIN
                && game.getHomeTeam().getId().equals(teamId))
                || (game.getResult() == GameResult.AWAY_WIN
                && game.getAwayTeam().getId().equals(teamId));
    }

    private record OutcomeCounts(int wins, int losses, int draws) {
    }
}
