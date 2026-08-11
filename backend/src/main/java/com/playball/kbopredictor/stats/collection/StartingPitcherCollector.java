package com.playball.kbopredictor.stats.collection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class StartingPitcherCollector {

    private final OfficialStartingPitcherSource source;
    private final OfficialStartingPitcherParser parser;

    public StartingPitcherCollectionBatch collect(LocalDate date) {
        OfficialStartingPitcherParser.ParsedStartingPitcherList parsed =
                parser.parseGameList(source.fetchGameList(date), date);

        Map<String, CollectedPitcherSeasonStat> statCache = new HashMap<>();
        List<CollectedStartingPitcher> pitchers = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (StartingPitcherCandidate candidate : parsed.candidates()) {
            CollectedPitcherSeasonStat seasonStat = null;
            try {
                String cacheKey = candidate.kboPlayerId()
                        + ":" + candidate.season();
                if (!statCache.containsKey(cacheKey)) {
                    seasonStat = parser.parsePlayerDetail(
                            source.fetchPlayerDetail(candidate.kboPlayerId()),
                            candidate.season()
                    ).orElse(null);
                    if (seasonStat != null) {
                        statCache.put(cacheKey, seasonStat);
                    }
                } else {
                    seasonStat = statCache.get(cacheKey);
                }
            } catch (RuntimeException exception) {
                errors.add(candidate.kboPlayerId() + ": " + safeMessage(exception));
                log.warn(
                        "KBO 선발투수 시즌 기록 수집 실패, 선발 정보는 계속 저장: gameId={}, playerId={}, error={}",
                        candidate.externalGameId(),
                        candidate.kboPlayerId(),
                        exception.getMessage()
                );
            }

            pitchers.add(new CollectedStartingPitcher(
                    candidate.externalGameId(),
                    candidate.teamCode(),
                    candidate.side(),
                    candidate.kboPlayerId(),
                    candidate.playerName(),
                    candidate.season(),
                    seasonStat
            ));
        }
        return new StartingPitcherCollectionBatch(
                parsed.sourceGameCount(),
                List.copyOf(pitchers),
                List.copyOf(errors)
        );
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
