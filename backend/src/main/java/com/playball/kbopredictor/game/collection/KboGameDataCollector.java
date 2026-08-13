package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.GameStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class KboGameDataCollector implements GameDataCollector {

    private static final ZoneId KBO_ZONE = ZoneId.of("Asia/Seoul");

    private final KboScheduleClient scheduleClient;
    private final KboScheduleParser scheduleParser;
    private final OfficialGameResultSource officialGameResultSource;
    private final OfficialFinalScoreParser officialFinalScoreParser;
    private final Map<YearMonth, String> completedMonthCache =
            new ConcurrentHashMap<>();

    @Override
    public GameCollectionBatch collect(LocalDate date) {
        String response = fetchSchedule(YearMonth.from(date));
        return confirmFinishedScores(scheduleParser.parse(response, date), date);
    }

    @Override
    public Map<LocalDate, GameCollectionBatch> collectDates(
            List<LocalDate> dates
    ) {
        if (dates.isEmpty()) {
            return Map.of();
        }

        YearMonth targetMonth = YearMonth.from(dates.getFirst());
        boolean includesAnotherMonth = dates.stream()
                .map(YearMonth::from)
                .anyMatch(month -> !month.equals(targetMonth));
        if (includesAnotherMonth) {
            throw new IllegalArgumentException(
                    "한 번의 KBO 일정 요청은 같은 연월의 날짜만 처리할 수 있습니다."
            );
        }

        String response = fetchSchedule(targetMonth);
        Map<LocalDate, GameCollectionBatch> batches = new LinkedHashMap<>();
        for (LocalDate date : dates.stream().distinct().toList()) {
            batches.put(
                    date,
                    confirmFinishedScores(scheduleParser.parse(response, date), date)
            );
        }
        return batches;
    }

    private GameCollectionBatch confirmFinishedScores(
            GameCollectionBatch scheduleBatch,
            LocalDate targetDate
    ) {
        boolean requiresOfficialStatusCheck = scheduleBatch.games().stream()
                .anyMatch(game -> game.status() == GameStatus.FINISHED
                        || game.status() == GameStatus.IN_PROGRESS);
        if (!requiresOfficialStatusCheck) {
            return scheduleBatch;
        }

        OfficialFinalScoreBatch scoreBatch = officialFinalScoreParser.parse(
                officialGameResultSource.fetchGameList(targetDate),
                targetDate
        );
        List<String> errors = new ArrayList<>(scheduleBatch.errors());
        errors.addAll(scoreBatch.errors());
        List<CollectedGame> games = scheduleBatch.games().stream()
                .map(game -> confirmFinishedScore(
                        game,
                        scoreBatch.scoresByExternalGameId(),
                        scoreBatch.statesByExternalGameId(),
                        errors
                ))
                .toList();
        long finishedCount = games.stream()
                .filter(game -> game.status() == GameStatus.FINISHED)
                .count();
        long confirmedCount = games.stream()
                .filter(CollectedGame::finalScoreConfirmed)
                .count();
        log.info(
                "KBO GameCenter final score verification: targetDate={}, finished={}, confirmed={}, unresolved={}",
                targetDate,
                finishedCount,
                confirmedCount,
                Math.max(0, finishedCount - confirmedCount)
        );

        return new GameCollectionBatch(
                scheduleBatch.sourceRowCount(),
                games,
                List.copyOf(errors)
        );
    }

    private CollectedGame confirmFinishedScore(
            CollectedGame game,
            Map<String, OfficialFinalScore> officialScores,
            Map<String, OfficialGameState> officialStates,
            List<String> errors
    ) {
        String externalGameId = game.externalGameId()
                .toUpperCase(Locale.ROOT);
        OfficialGameState officialState = officialStates.get(externalGameId);
        if (officialState != null && !teamsMatch(game, officialState)) {
            errors.add(game.externalGameId()
                    + ": GameCenter team codes do not match the schedule");
            return game;
        }

        GameStatus effectiveStatus = officialState == null
                ? game.status()
                : officialState.status();
        if (effectiveStatus == GameStatus.SCHEDULED) {
            return withStatus(game, GameStatus.SCHEDULED, null, null);
        }
        if (effectiveStatus == GameStatus.IN_PROGRESS) {
            return withStatus(
                    game,
                    GameStatus.IN_PROGRESS,
                    game.awayScore(),
                    game.homeScore()
            );
        }
        if (effectiveStatus == GameStatus.CANCELLED) {
            return withStatus(game, GameStatus.CANCELLED, null, null);
        }
        if (effectiveStatus != GameStatus.FINISHED) {
            return game;
        }

        OfficialFinalScore score = officialScores.get(externalGameId);
        if (score == null) {
            errors.add(game.externalGameId()
                    + ": final score is not confirmed by KBO GameCenter");
            return withoutUnconfirmedFinalScore(game, effectiveStatus);
        }
        if (!score.awayTeamCode().equalsIgnoreCase(game.awayTeamCode())
                || !score.homeTeamCode().equalsIgnoreCase(game.homeTeamCode())) {
            errors.add(game.externalGameId()
                    + ": GameCenter team codes do not match the schedule");
            return withoutUnconfirmedFinalScore(game, effectiveStatus);
        }

        return new CollectedGame(
                game.externalGameId(),
                game.season(),
                game.gameDate(),
                game.gameTime(),
                game.awayTeamCode(),
                game.homeTeamCode(),
                game.stadium(),
                effectiveStatus,
                score.awayScore(),
                score.homeScore(),
                score.result(),
                true,
                game.cancelReason()
        );
    }

    private boolean teamsMatch(
            CollectedGame game,
            OfficialGameState officialState
    ) {
        return officialState.awayTeamCode()
                .equalsIgnoreCase(game.awayTeamCode())
                && officialState.homeTeamCode()
                .equalsIgnoreCase(game.homeTeamCode());
    }

    private CollectedGame withStatus(
            CollectedGame game,
            GameStatus status,
            Integer awayScore,
            Integer homeScore
    ) {
        return new CollectedGame(
                game.externalGameId(),
                game.season(),
                game.gameDate(),
                game.gameTime(),
                game.awayTeamCode(),
                game.homeTeamCode(),
                game.stadium(),
                status,
                awayScore,
                homeScore,
                null,
                false,
                status == GameStatus.CANCELLED
                        ? game.cancelReason()
                        : null
        );
    }

    private CollectedGame withoutUnconfirmedFinalScore(
            CollectedGame game,
            GameStatus status
    ) {
        return new CollectedGame(
                game.externalGameId(),
                game.season(),
                game.gameDate(),
                game.gameTime(),
                game.awayTeamCode(),
                game.homeTeamCode(),
                game.stadium(),
                status,
                null,
                null,
                null,
                false,
                game.cancelReason()
        );
    }

    private String fetchSchedule(YearMonth targetMonth) {
        if (targetMonth.isBefore(YearMonth.now(KBO_ZONE))) {
            return completedMonthCache.computeIfAbsent(
                    targetMonth,
                    scheduleClient::fetchSchedule
            );
        }
        return scheduleClient.fetchSchedule(targetMonth);
    }
}
