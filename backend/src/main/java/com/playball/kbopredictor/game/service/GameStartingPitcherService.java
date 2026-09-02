package com.playball.kbopredictor.game.service;

import com.playball.kbopredictor.game.dto.GameStartingPitchersResponse;
import com.playball.kbopredictor.game.dto.StartingPitcherDetailResponse;
import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.stats.entity.PitcherStat;
import com.playball.kbopredictor.stats.entity.StartingPitcher;
import com.playball.kbopredictor.stats.entity.StartingPitcherSide;
import com.playball.kbopredictor.stats.repository.PitcherStatRepository;
import com.playball.kbopredictor.stats.repository.StartingPitcherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GameStartingPitcherService {

    private final GameRepository gameRepository;
    private final StartingPitcherRepository startingPitcherRepository;
    private final PitcherStatRepository pitcherStatRepository;

    public GameStartingPitchersResponse getByGameId(Long gameId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "경기를 찾을 수 없습니다."
                ));
        LocalDateTime gameStartAt = LocalDateTime.of(
                game.getGameDate(),
                game.getGameTime() == null ? LocalTime.MIN : game.getGameTime()
        );

        Map<StartingPitcherSide, StartingPitcher> startingPitchers =
                new EnumMap<>(StartingPitcherSide.class);
        for (StartingPitcher startingPitcher
                : startingPitcherRepository.findByGameIdInWithPlayer(List.of(gameId))) {
            startingPitchers.putIfAbsent(
                    startingPitcher.getSide(),
                    startingPitcher
            );
        }

        return new GameStartingPitchersResponse(
                gameId,
                detail(startingPitchers.get(StartingPitcherSide.HOME), game, gameStartAt),
                detail(startingPitchers.get(StartingPitcherSide.AWAY), game, gameStartAt)
        );
    }

    private StartingPitcherDetailResponse detail(
            StartingPitcher startingPitcher,
            Game game,
            LocalDateTime gameStartAt
    ) {
        if (startingPitcher == null) {
            return null;
        }

        PitcherStat stat = null;
        if (startingPitcher.getFirstCollectedAt().isBefore(gameStartAt)) {
            stat = pitcherStatRepository
                    .findTopByPlayerIdAndStatDateLessThanEqualAndCollectedAtBeforeOrderByStatDateDescCollectedAtDesc(
                            startingPitcher.getPlayer().getId(),
                            game.getGameDate(),
                            gameStartAt
                    )
                    .orElse(null);
        }
        return StartingPitcherDetailResponse.from(startingPitcher, stat);
    }
}
