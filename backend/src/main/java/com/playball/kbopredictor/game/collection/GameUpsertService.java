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
import java.time.LocalTime;
import java.util.List;
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
        validateConfirmedFinalScore(collectedGame);
        Team homeTeam = getTeam(collectedGame.homeTeamCode());
        Team awayTeam = getTeam(collectedGame.awayTeamCode());
        LocalDateTime now = LocalDateTime.now(clock);

        Optional<Game> existingGame = findExistingGame(
                collectedGame,
                homeTeam,
                awayTeam
        );

        if (existingGame.isPresent()) {
            Game game = existingGame.get();
            validateStatusTransition(game.getStatus(), collectedGame.status());
            GameStatusSnapshot previous = snapshot(game);
            Integer homeScore = collectedGame.homeScore();
            Integer awayScore = collectedGame.awayScore();
            GameResult result = collectedGame.result();
            if (shouldPreserveExistingFinalResult(game, collectedGame)) {
                homeScore = game.getHomeScore();
                awayScore = game.getAwayScore();
                result = game.getResult();
            }
            Team winnerTeam = determineWinner(result, homeTeam, awayTeam);
            boolean terminalDataChanged = isTerminal(previous.status())
                    && terminalDataChanged(
                            previous,
                            collectedGame,
                            homeTeam,
                            awayTeam,
                            homeScore,
                            awayScore,
                            result
                    );
            LocalTime gameTime = collectedGame.gameTime() == null
                    ? game.getGameTime()
                    : collectedGame.gameTime();
            String stadium = collectedGame.stadium() == null
                    || collectedGame.stadium().isBlank()
                    ? game.getStadium()
                    : collectedGame.stadium();

            game.updateCollected(
                    collectedGame.externalGameId(),
                    collectedGame.season(),
                    collectedGame.gameDate(),
                    gameTime,
                    homeTeam,
                    awayTeam,
                    stadium,
                    collectedGame.status(),
                    homeScore,
                    awayScore,
                    winnerTeam,
                    result,
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
                    terminalDataChanged,
                    collectedGame.finalScoreConfirmed()
            );
        }

        Team winnerTeam = determineWinner(
                collectedGame.result(),
                homeTeam,
                awayTeam
        );
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
                false,
                collectedGame.finalScoreConfirmed()
        );
    }

    private void validateConfirmedFinalScore(CollectedGame collectedGame) {
        if (!collectedGame.finalScoreConfirmed()) {
            return;
        }
        if (collectedGame.status() != GameStatus.FINISHED
                || collectedGame.homeScore() == null
                || collectedGame.awayScore() == null
                || collectedGame.result() == null
                || resultOf(
                        collectedGame.homeScore(),
                        collectedGame.awayScore()
                ) != collectedGame.result()) {
            throw new IllegalArgumentException(
                    "Confirmed final score is incomplete or inconsistent"
            );
        }
    }

    private boolean shouldPreserveExistingFinalResult(
            Game existing,
            CollectedGame collectedGame
    ) {
        return collectedGame.status() == GameStatus.FINISHED
                && !collectedGame.finalScoreConfirmed()
                && existing.getStatus() == GameStatus.FINISHED
                && existing.getHomeScore() != null
                && existing.getAwayScore() != null
                && existing.getResult() != null;
    }

    private GameResult resultOf(int homeScore, int awayScore) {
        if (homeScore > awayScore) {
            return GameResult.HOME_WIN;
        }
        if (homeScore < awayScore) {
            return GameResult.AWAY_WIN;
        }
        return GameResult.DRAW;
    }

    private Optional<Game> findExistingGame(
            CollectedGame collectedGame,
            Team homeTeam,
            Team awayTeam
    ) {
        Optional<Game> existingGame = gameRepository.findByExternalGameId(
                collectedGame.externalGameId()
        );
        if (existingGame.isPresent()) {
            return existingGame;
        }

        if (collectedGame.gameTime() != null) {
            existingGame = gameRepository
                    .findByGameDateAndGameTimeAndHomeTeamIdAndAwayTeamId(
                            collectedGame.gameDate(),
                            collectedGame.gameTime(),
                            homeTeam.getId(),
                            awayTeam.getId()
                    );
            if (existingGame.isPresent()) {
                return existingGame;
            }
        }

        List<Game> matchupCandidates = gameRepository
                .findByGameDateAndHomeTeamIdAndAwayTeamIdOrderByGameTimeAsc(
                        collectedGame.gameDate(),
                        homeTeam.getId(),
                        awayTeam.getId()
                );
        if (matchupCandidates.size() == 1
                && matchupCandidates.getFirst().getExternalGameId() == null) {
            return Optional.of(matchupCandidates.getFirst());
        }
        boolean containsLegacyCandidate = matchupCandidates.stream()
                .anyMatch(game -> game.getExternalGameId() == null);
        if (containsLegacyCandidate) {
            throw new IllegalStateException(
                    "externalGameId가 없는 동일 날짜/대진 후보를 안전하게 식별할 수 없습니다: "
                            + collectedGame.gameDate()
                            + " "
                            + collectedGame.awayTeamCode()
                            + "-"
                            + collectedGame.homeTeamCode()
            );
        }
        return Optional.empty();
    }

    private void validateStatusTransition(
            GameStatus previousStatus,
            GameStatus collectedStatus
    ) {
        boolean regressedFromInProgress = previousStatus
                == GameStatus.IN_PROGRESS
                && collectedStatus == GameStatus.SCHEDULED;
        boolean regressedFromTerminal = isTerminal(previousStatus)
                && !isTerminal(collectedStatus);
        if (regressedFromInProgress || regressedFromTerminal) {
            throw new IllegalStateException(
                    "KBO 경기 상태가 역방향으로 변경되어 기존 데이터를 유지합니다: "
                            + previousStatus
                            + " -> "
                            + collectedStatus
            );
        }
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
            Team awayTeam,
            Integer homeScore,
            Integer awayScore,
            GameResult result
    ) {
        return previous.status() != collected.status()
                || previous.result() != result
                || !Objects.equals(previous.homeScore(), homeScore)
                || !Objects.equals(previous.awayScore(), awayScore)
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
