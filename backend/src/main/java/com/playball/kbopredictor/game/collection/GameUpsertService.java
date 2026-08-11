package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.team.entity.Team;
import com.playball.kbopredictor.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class GameUpsertService {

    private final GameRepository gameRepository;
    private final TeamRepository teamRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GameUpsertResult upsert(CollectedGame collectedGame) {
        Team homeTeam = getTeam(collectedGame.homeTeamCode());
        Team awayTeam = getTeam(collectedGame.awayTeamCode());
        LocalDateTime now = LocalDateTime.now(clock);

        Optional<Game> existingGame = gameRepository.findByExternalGameId(
                collectedGame.externalGameId()
        );
        if (existingGame.isEmpty() && collectedGame.gameTime() != null) {
            existingGame = gameRepository
                    .findByGameDateAndGameTimeAndHomeTeamIdAndAwayTeamId(
                            collectedGame.gameDate(),
                            collectedGame.gameTime(),
                            homeTeam.getId(),
                            awayTeam.getId()
                    );
        }

        Team winnerTeam = determineWinner(
                collectedGame.result(),
                homeTeam,
                awayTeam
        );

        if (existingGame.isPresent()) {
            Game game = existingGame.get();
            GameStatusSnapshot previous = snapshot(game);
            boolean terminalDataChanged = isTerminal(previous.status())
                    && terminalDataChanged(previous, collectedGame, homeTeam, awayTeam);

            game.updateCollected(
                    collectedGame.externalGameId(),
                    collectedGame.season(),
                    collectedGame.gameDate(),
                    collectedGame.gameTime(),
                    homeTeam,
                    awayTeam,
                    collectedGame.stadium(),
                    collectedGame.status(),
                    collectedGame.homeScore(),
                    collectedGame.awayScore(),
                    winnerTeam,
                    collectedGame.result(),
                    collectedGame.cancelReason(),
                    now
            );
            gameRepository.saveAndFlush(game);
            return new GameUpsertResult(
                    GameUpsertAction.UPDATED,
                    game.getId(),
                    previous.status(),
                    game.getStatus(),
                    previous.result(),
                    game.getResult(),
                    terminalDataChanged
            );
        }

        Game newGame = Game.createCollected(
                collectedGame.externalGameId(),
                collectedGame.season(),
                collectedGame.gameDate(),
                collectedGame.gameTime(),
                homeTeam,
                awayTeam,
                collectedGame.stadium(),
                collectedGame.status(),
                collectedGame.homeScore(),
                collectedGame.awayScore(),
                winnerTeam,
                collectedGame.result(),
                collectedGame.cancelReason(),
                now
        );
        gameRepository.saveAndFlush(newGame);
        return new GameUpsertResult(
                GameUpsertAction.INSERTED,
                newGame.getId(),
                null,
                newGame.getStatus(),
                null,
                newGame.getResult(),
                false
        );
    }

    private Team getTeam(String kboTeamCode) {
        return teamRepository.findByKboTeamCode(kboTeamCode)
                .orElseThrow(() -> new IllegalStateException(
                        "KBO 팀 코드가 DB에 없습니다: " + kboTeamCode
                ));
    }

    private Team determineWinner(
            GameResult result,
            Team homeTeam,
            Team awayTeam
    ) {
        if (result == GameResult.HOME_WIN) {
            return homeTeam;
        }
        if (result == GameResult.AWAY_WIN) {
            return awayTeam;
        }
        return null;
    }

    private GameStatusSnapshot snapshot(Game game) {
        return new GameStatusSnapshot(
                game.getStatus(),
                game.getResult(),
                game.getHomeScore(),
                game.getAwayScore(),
                game.getHomeTeam().getId(),
                game.getAwayTeam().getId(),
                game.getCancelReason()
        );
    }

    private boolean terminalDataChanged(
            GameStatusSnapshot previous,
            CollectedGame collected,
            Team homeTeam,
            Team awayTeam
    ) {
        return previous.status() != collected.status()
                || previous.result() != collected.result()
                || !Objects.equals(previous.homeScore(), collected.homeScore())
                || !Objects.equals(previous.awayScore(), collected.awayScore())
                || !Objects.equals(previous.homeTeamId(), homeTeam.getId())
                || !Objects.equals(previous.awayTeamId(), awayTeam.getId())
                || !Objects.equals(
                        previous.cancelReason(),
                        collected.cancelReason()
                );
    }

    private boolean isTerminal(GameStatus status) {
        return status == GameStatus.FINISHED
                || status == GameStatus.CANCELLED;
    }

    private record GameStatusSnapshot(
            GameStatus status,
            GameResult result,
            Integer homeScore,
            Integer awayScore,
            Long homeTeamId,
            Long awayTeamId,
            String cancelReason
    ) {
    }
}
