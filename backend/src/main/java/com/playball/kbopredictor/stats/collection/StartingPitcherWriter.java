package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.game.entity.Game;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class StartingPitcherWriter {

    private final GameRepository gameRepository;
    private final PlayerRepository playerRepository;
    private final StartingPitcherRepository startingPitcherRepository;
    private final PitcherStatRepository pitcherStatRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StartingPitcherWriteResult upsert(
            CollectedStartingPitcher collected,
            LocalDate statDate,
            LocalDateTime now
    ) {
        Game game = gameRepository.findByExternalGameId(
                collected.externalGameId()
        ).orElseThrow(() -> new PregameDataCollectionException(
                "먼저 경기 일정을 동기화해야 합니다: "
                        + collected.externalGameId()
        ));
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
                        now
                ));
        player.update(team, collected.playerName(), now);
        player = playerRepository.save(player);

        StartingPitcher startingPitcher = startingPitcherRepository
                .findByGameIdAndSide(game.getId(), collected.side())
                .orElse(null);
        boolean inserted = startingPitcher == null;
        if (inserted) {
            startingPitcher = StartingPitcher.create(
                    game,
                    team,
                    player,
                    collected.side(),
                    now
            );
        } else {
            startingPitcher.update(team, player, now);
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
                    now
            );
            pitcherStatRepository.save(pitcherStat);
            pitcherStatSaved = true;
        }
        return new StartingPitcherWriteResult(inserted, pitcherStatSaved);
    }
}
