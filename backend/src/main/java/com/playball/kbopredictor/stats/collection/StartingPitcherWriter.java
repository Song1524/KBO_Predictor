package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.player.entity.Player;
import com.playball.kbopredictor.player.repository.PlayerRepository;
import com.playball.kbopredictor.stats.entity.PitcherStat;
import com.playball.kbopredictor.stats.entity.StartingPitcher;
import com.playball.kbopredictor.stats.repository.PitcherStatRepository;
import com.playball.kbopredictor.stats.repository.StartingPitcherRepository;
import com.playball.kbopredictor.team.entity.Team;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StartingPitcherWriter {

    private final GameRepository gameRepository;
    private final PlayerRepository playerRepository;
    private final StartingPitcherRepository startingPitcherRepository;
    private final PitcherStatRepository pitcherStatRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StartingPitcherWriteResult upsert(
            CollectedStartingPitcher collected,
            LocalDate statDate,
            LocalDateTime now
    ) {
        return write(collected, statDate, now, false).orElseThrow();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<StartingPitcherWriteResult> upsertBeforeClose(
            CollectedStartingPitcher collected,
            LocalDate statDate
    ) {
        return write(collected, statDate, null, true);
    }

    private Optional<StartingPitcherWriteResult> write(
            CollectedStartingPitcher collected,
            LocalDate statDate,
            LocalDateTime now,
            boolean enforcePredictionClose
    ) {
        Game game = gameRepository.findByExternalGameId(
                collected.externalGameId()
        ).orElseThrow(() -> new PregameDataCollectionException(
                "먼저 경기 일정을 동기화해야 합니다: "
                        + collected.externalGameId()
        ));
        LocalDateTime writeAt = enforcePredictionClose
                ? LocalDateTime.now(clock)
                : now;
        if (enforcePredictionClose
                && (game.getStatus() != GameStatus.SCHEDULED
                || game.getPredictionCloseAt() == null
                || !writeAt.isBefore(game.getPredictionCloseAt()))) {
            return Optional.empty();
        }
        Team team = switch (collected.side()) {
            case HOME -> game.getHomeTeam();
            case AWAY -> game.getAwayTeam();
        };
        if (!collected.teamCode().equals(team.getKboTeamCode())) {
            throw new PregameDataCollectionException(
                    "경기와 선발투수의 팀 코드가 일치하지 않습니다: "
                            + collected.externalGameId()
            );
        }

        Player player = playerRepository
                .findByKboPlayerIdForUpdate(collected.kboPlayerId())
                .orElseGet(() -> Player.create(
                        collected.kboPlayerId(),
                        team,
                        collected.playerName(),
                        writeAt
                ));
        player.update(team, collected.playerName(), writeAt);
        player = playerRepository.save(player);

        StartingPitcher startingPitcher = startingPitcherRepository
                .findByGameIdAndSide(game.getId(), collected.side())
                .orElse(null);
        boolean inserted = startingPitcher == null;
        boolean playerChanged = !inserted && !Objects.equals(
                startingPitcher.getPlayer().getKboPlayerId(),
                collected.kboPlayerId()
        );
        if (inserted) {
            startingPitcher = StartingPitcher.create(
                    game,
                    team,
                    player,
                    collected.side(),
                    writeAt
            );
        } else {
            startingPitcher.update(team, player, writeAt);
        }
        startingPitcherRepository.save(startingPitcher);

        boolean pitcherStatSaved = false;
        if (collected.seasonStat() != null) {
            CollectedPitcherSeasonStat sourceStat = collected.seasonStat();
            PitcherStat pitcherStat = pitcherStatRepository
                    .findByPlayerIdAndSeasonAndStatDate(
                            player.getId(),
                            sourceStat.season(),
                            statDate
                    )
                    .orElse(null);
            if (pitcherStat == null) {
                pitcherStat = PitcherStat.create(
                        player,
                        sourceStat.season(),
                        statDate
                );
            }
            pitcherStat.update(
                    sourceStat.era(),
                    sourceStat.wins(),
                    sourceStat.losses(),
                    sourceStat.innings(),
                    sourceStat.whip(),
                    writeAt
            );
            pitcherStatRepository.save(pitcherStat);
            pitcherStatSaved = true;
        }
        return Optional.of(new StartingPitcherWriteResult(
                inserted,
                pitcherStatSaved,
                playerChanged,
                game.getId(),
                collected.side()
        ));
    }
}
