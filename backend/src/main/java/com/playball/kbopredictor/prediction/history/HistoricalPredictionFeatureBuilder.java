package com.playball.kbopredictor.prediction.history;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import com.playball.kbopredictor.prediction.feature.TeamPredictionFeatures;
import com.playball.kbopredictor.team.entity.Team;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HistoricalPredictionFeatureBuilder {

    private static final String DATA_SOURCE =
            "KBO_OFFICIAL_SCHEDULE_RESULTS_STORED_IN_GAMES;"
                    + "STRICT_CUTOFF=GAME_DATE_EXCLUSIVE";

    private final GameRepository gameRepository;

    public HistoricalPredictionFeatures build(Long gameId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "경기를 찾을 수 없습니다."
                ));
        if (game.getGameDate() == null || game.getSeason() == null) {
            throw new IllegalStateException(
                    "경기 날짜와 시즌이 있어야 Historical Feature를 생성할 수 있습니다."
            );
        }

        TeamHistory home = calculate(game, game.getHomeTeam(), true);
        TeamHistory away = calculate(game, game.getAwayTeam(), false);
        LocalDateTime startAt = LocalDateTime.of(
                game.getGameDate(),
                game.getGameTime() == null ? LocalTime.MIN : game.getGameTime()
        );
        PredictionFeatures features = new PredictionFeatures(
                game.getId(),
                game.getGameDate(),
                startAt,
                home.features(),
                away.features()
        );
        List<String> missing = new ArrayList<>();
        addMissing("HOME", home.features(), missing);
        addMissing("AWAY", away.features(), missing);
        return new HistoricalPredictionFeatures(
                features,
                home.gameCount(),
                away.gameCount(),
                home.wins(),
                home.losses(),
                home.draws(),
                away.wins(),
                away.losses(),
                away.draws(),
                PredictionGenerationMethod.HISTORICAL_INTERNAL_GAMES,
                DATA_SOURCE,
                missing
        );
    }

    private TeamHistory calculate(Game target, Team team, boolean homeSide) {
        List<Game> games = gameRepository.findTeamSeasonGamesBefore(
                team.getId(),
                target.getSeason(),
                GameStatus.FINISHED,
                target.getGameDate()
        ).stream()
                .filter(this::validFinishedGame)
                .filter(game -> game.getGameDate() != null
                        && game.getGameDate().isBefore(target.getGameDate()))
                .filter(game -> game.getSeason() != null
                        && game.getSeason().equals(target.getSeason()))
                .toList();

        OutcomeCounts season = count(games, team.getId());
        List<Game> recent5 = games.stream().limit(5).toList();
        List<Game> recent10 = games.stream().limit(10).toList();
        List<Game> venueGames = games.stream()
                .filter(game -> homeSide
                        ? game.getHomeTeam().getId().equals(team.getId())
                        : game.getAwayTeam().getId().equals(team.getId()))
                .toList();
        TeamPredictionFeatures features = new TeamPredictionFeatures(
                team.getId(),
                team.getName(),
                !games.isEmpty(),
                target.getGameDate().minusDays(1),
                winRate(season),
                winRate(count(recent5, team.getId())),
                winRate(count(recent10, team.getId())),
                averageRuns(recent5, team.getId(), true),
                averageRuns(recent5, team.getId(), false),
                averageRuns(recent10, team.getId(), true),
                averageRuns(recent10, team.getId(), false),
                null,
                null,
                winRate(count(venueGames, team.getId())),
                null
        );
        return new TeamHistory(
                features,
                games.size(),
                season.wins(),
                season.losses(),
                season.draws()
        );
    }

    private boolean validFinishedGame(Game game) {
        return game.getStatus() == GameStatus.FINISHED
                && game.getResult() != null
                && game.getHomeScore() != null
                && game.getAwayScore() != null;
    }

    private OutcomeCounts count(List<Game> games, Long teamId) {
        int wins = 0;
        int losses = 0;
        int draws = 0;
        for (Game game : games) {
            if (game.getResult() == GameResult.DRAW) {
                draws++;
            } else if (won(game, teamId)) {
                wins++;
            } else {
                losses++;
            }
        }
        return new OutcomeCounts(wins, losses, draws);
    }

    private boolean won(Game game, Long teamId) {
        return game.getResult() == GameResult.HOME_WIN
                && game.getHomeTeam().getId().equals(teamId)
                || game.getResult() == GameResult.AWAY_WIN
                && game.getAwayTeam().getId().equals(teamId);
    }

    private BigDecimal winRate(OutcomeCounts counts) {
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
        int total = games.stream()
                .mapToInt(game -> runs(game, teamId, scored))
                .sum();
        return BigDecimal.valueOf(total)
                .divide(BigDecimal.valueOf(games.size()), 2, RoundingMode.HALF_UP);
    }

    private int runs(Game game, Long teamId, boolean scored) {
        boolean isHome = game.getHomeTeam().getId().equals(teamId);
        if (scored) {
            return isHome ? game.getHomeScore() : game.getAwayScore();
        }
        return isHome ? game.getAwayScore() : game.getHomeScore();
    }

    private void addMissing(
            String side,
            TeamPredictionFeatures features,
            List<String> missing
    ) {
        addIfNull(side + "_SEASON_WIN_RATE", features.seasonWinRate(), missing);
        addIfNull(side + "_RECENT_5_WIN_RATE", features.recent5WinRate(), missing);
        addIfNull(side + "_RECENT_10_WIN_RATE", features.recent10WinRate(), missing);
        addIfNull(side + "_RECENT_5_AVG_RUNS", features.recent5AvgRuns(), missing);
        addIfNull(side + "_RECENT_5_AVG_RUNS_ALLOWED", features.recent5AvgRunsAllowed(), missing);
        addIfNull(side + "_RECENT_10_AVG_RUNS", features.recent10AvgRuns(), missing);
        addIfNull(side + "_RECENT_10_AVG_RUNS_ALLOWED", features.recent10AvgRunsAllowed(), missing);
        addIfNull(side + "_VENUE_WIN_RATE", features.venueWinRate(), missing);
        addIfNull(side + "_BATTING_AVERAGE", features.battingAverage(), missing);
        addIfNull(side + "_TEAM_ERA", features.era(), missing);
        missing.add(side + "_STARTING_PITCHER_ERA");
        missing.add(side + "_STARTING_PITCHER_WHIP");
    }

    private void addIfNull(String name, Object value, List<String> missing) {
        if (value == null) {
            missing.add(name);
        }
    }

    private record OutcomeCounts(int wins, int losses, int draws) {
    }

    private record TeamHistory(
            TeamPredictionFeatures features,
            int gameCount,
            int wins,
            int losses,
            int draws
    ) {
    }
}
