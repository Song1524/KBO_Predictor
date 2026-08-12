package com.playball.kbopredictor.prediction.feature;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.stats.entity.PitcherStat;
import com.playball.kbopredictor.stats.entity.StartingPitcher;
import com.playball.kbopredictor.stats.entity.StartingPitcherSide;
import com.playball.kbopredictor.stats.entity.TeamStat;
import com.playball.kbopredictor.stats.repository.PitcherStatRepository;
import com.playball.kbopredictor.stats.repository.StartingPitcherRepository;
import com.playball.kbopredictor.stats.repository.TeamStatRepository;
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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PredictionFeatureService {

    private final GameRepository gameRepository;
    private final TeamStatRepository teamStatRepository;
    private final StartingPitcherRepository startingPitcherRepository;
    private final PitcherStatRepository pitcherStatRepository;

    public PredictionFeatures build(Long gameId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "경기를 찾을 수 없습니다."
                ));
        if (game.getGameDate() == null) {
            throw new IllegalStateException("경기 날짜가 없어 Feature를 만들 수 없습니다.");
        }

        LocalDateTime gameStartAt = LocalDateTime.of(
                game.getGameDate(),
                game.getGameTime() == null ? LocalTime.MIN : game.getGameTime()
        );
        return new PredictionFeatures(
                game.getId(),
                game.getGameDate(),
                gameStartAt,
                buildTeam(game, game.getHomeTeam(), StartingPitcherSide.HOME, gameStartAt),
                buildTeam(game, game.getAwayTeam(), StartingPitcherSide.AWAY, gameStartAt)
        );
    }

    private TeamPredictionFeatures buildTeam(
            Game game,
            Team team,
            StartingPitcherSide side,
            LocalDateTime gameStartAt
    ) {
        TeamStat stat = teamStatRepository
                .findTopByTeamIdAndStatDateLessThanEqualAndCollectedAtBeforeOrderByStatDateDescCollectedAtDesc(
                        team.getId(),
                        game.getGameDate(),
                        gameStartAt
                )
                .orElse(null);
        StartingPitcherFeatures pitcher = buildPitcher(
                game,
                side,
                gameStartAt
        );

        if (stat == null) {
            return new TeamPredictionFeatures(
                    team.getId(),
                    team.getName(),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    pitcher
            );
        }

        BigDecimal venueWinRate = side == StartingPitcherSide.HOME
                ? winRate(stat.getHomeWins(), stat.getHomeLosses())
                : winRate(stat.getAwayWins(), stat.getAwayLosses());
        return new TeamPredictionFeatures(
                team.getId(),
                team.getName(),
                true,
                stat.getStatDate(),
                stat.getWinRate(),
                stat.getRecent5WinRate(),
                stat.getRecent10WinRate(),
                stat.getRecent5AvgRuns(),
                stat.getRecent5AvgRunsAllowed(),
                stat.getRecent10AvgRuns(),
                stat.getRecent10AvgRunsAllowed(),
                stat.getBattingAverage(),
                stat.getEra(),
                venueWinRate,
                pitcher
        );
    }

    private StartingPitcherFeatures buildPitcher(
            Game game,
            StartingPitcherSide side,
            LocalDateTime gameStartAt
    ) {
        StartingPitcher startingPitcher = startingPitcherRepository
                .findByGameIdAndSide(game.getId(), side)
                .filter(value -> value.getFirstCollectedAt().isBefore(gameStartAt))
                .orElse(null);
        if (startingPitcher == null) {
            return null;
        }

        PitcherStat stat = pitcherStatRepository
                .findTopByPlayerIdAndStatDateLessThanEqualAndCollectedAtBeforeOrderByStatDateDescCollectedAtDesc(
                        startingPitcher.getPlayer().getId(),
                        game.getGameDate(),
                        gameStartAt
                )
                .orElse(null);
        return new StartingPitcherFeatures(
                startingPitcher.getPlayer().getId(),
                startingPitcher.getPlayer().getKboPlayerId(),
                startingPitcher.getPlayer().getName(),
                true,
                stat != null,
                stat == null ? null : stat.getStatDate(),
                stat == null ? null : stat.getEra(),
                stat == null ? null : stat.getWins(),
                stat == null ? null : stat.getLosses(),
                stat == null ? null : stat.getInnings(),
                stat == null ? null : stat.getWhip()
        );
    }

    private BigDecimal winRate(Integer wins, Integer losses) {
        if (wins == null || losses == null || wins + losses == 0) {
            return null;
        }
        return BigDecimal.valueOf(wins)
                .divide(BigDecimal.valueOf(wins + losses), 3, RoundingMode.HALF_UP);
    }
}
