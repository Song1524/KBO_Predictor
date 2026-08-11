package com.playball.kbopredictor.game.service;

import com.playball.kbopredictor.game.dto.GameResponse;
import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.dto.GameOddsResponse;
import com.playball.kbopredictor.prediction.dto.SystemPredictionResponse;
import com.playball.kbopredictor.prediction.service.GameOddsService;
import com.playball.kbopredictor.prediction.service.SystemPredictionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GameService {

    private final GameRepository gameRepository;
    private final SystemPredictionService systemPredictionService;
    private final GameOddsService gameOddsService;

    @Transactional
    public List<GameResponse> getGamesByDate(LocalDate gameDate) {
        List<Game> games = gameRepository.findByGameDateOrderByGameTimeAsc(gameDate);
        List<Long> gameIds = games.stream().map(Game::getId).toList();
        Map<Long, SystemPredictionResponse> predictions =
                systemPredictionService.getPredictionsByGameIds(gameIds);
        Map<Long, GameOddsResponse> odds = gameOddsService.getOddsForGames(games);

        return games.stream()
                .map(game -> GameResponse.from(
                        game,
                        predictions.get(game.getId()),
                        odds.get(game.getId())
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

        return GameResponse.from(
                game,
                prediction,
                gameOddsService.getOddsByGameId(gameId)
        );
    }
}
