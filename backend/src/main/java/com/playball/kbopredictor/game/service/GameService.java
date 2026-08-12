package com.playball.kbopredictor.game.service;

import com.playball.kbopredictor.game.dto.GameResponse;
import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.dto.GameOddsResponse;
import com.playball.kbopredictor.prediction.dto.SystemPredictionResponse;
import com.playball.kbopredictor.prediction.service.GameOddsService;
import com.playball.kbopredictor.prediction.service.SystemPredictionService;
import com.playball.kbopredictor.stats.entity.StartingPitcher;
import com.playball.kbopredictor.stats.entity.StartingPitcherSide;
import com.playball.kbopredictor.stats.repository.StartingPitcherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GameService {

    private final GameRepository gameRepository;
    private final SystemPredictionService systemPredictionService;
    private final GameOddsService gameOddsService;
    private final StartingPitcherRepository startingPitcherRepository;

    @Transactional
    public List<GameResponse> getGamesByDate(LocalDate gameDate) {
        List<Game> games = gameRepository.findByGameDateOrderByGameTimeAsc(gameDate);
        List<Long> gameIds = games.stream().map(Game::getId).toList();
        Map<Long, SystemPredictionResponse> predictions =
                systemPredictionService.getPredictionsByGameIds(gameIds);
        Map<Long, GameOddsResponse> odds = gameOddsService.getOddsForGames(games);
        Map<Long, Map<StartingPitcherSide, StartingPitcher>> startingPitchers =
                getStartingPitchersByGameId(gameIds);

        return games.stream()
                .map(game -> GameResponse.from(
                        game,
                        predictions.get(game.getId()),
                        odds.get(game.getId()),
                        startingPitcher(startingPitchers, game.getId(), StartingPitcherSide.HOME),
                        startingPitcher(startingPitchers, game.getId(), StartingPitcherSide.AWAY)
                ))
                .toList();
    }

    @Transactional
    public GameResponse getGame(Long gameId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "경기를 찾을 수 없습니다."
                ));

        SystemPredictionResponse prediction = null;
        try {
            prediction = systemPredictionService.getPredictionByGameId(gameId);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() != HttpStatus.NOT_FOUND.value()) {
                throw exception;
            }
        }
        Map<Long, Map<StartingPitcherSide, StartingPitcher>> startingPitchers =
                getStartingPitchersByGameId(List.of(gameId));

        return GameResponse.from(
                game,
                prediction,
                gameOddsService.getOddsByGameId(gameId),
                startingPitcher(startingPitchers, gameId, StartingPitcherSide.HOME),
                startingPitcher(startingPitchers, gameId, StartingPitcherSide.AWAY)
        );
    }

    private Map<Long, Map<StartingPitcherSide, StartingPitcher>> getStartingPitchersByGameId(
            List<Long> gameIds
    ) {
        if (gameIds.isEmpty()) {
            return Map.of();
        }
        return startingPitcherRepository.findByGameIdInWithPlayer(gameIds)
                .stream()
                .collect(Collectors.groupingBy(
                        value -> value.getGame().getId(),
                        Collectors.toMap(
                                StartingPitcher::getSide,
                                Function.identity(),
                                (first, ignored) -> first,
                                () -> new EnumMap<>(StartingPitcherSide.class)
                        )
                ));
    }

    private StartingPitcher startingPitcher(
            Map<Long, Map<StartingPitcherSide, StartingPitcher>> startingPitchers,
            Long gameId,
            StartingPitcherSide side
    ) {
        return startingPitchers.getOrDefault(gameId, Map.of()).get(side);
    }
}
