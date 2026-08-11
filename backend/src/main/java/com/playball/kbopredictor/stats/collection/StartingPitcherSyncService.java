package com.playball.kbopredictor.stats.collection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class StartingPitcherSyncService {

    private final StartingPitcherCollector collector;
    private final StartingPitcherWriter writer;
    private final Clock clock;

    public StartingPitcherSyncResponse syncToday() {
        return sync(LocalDate.now(clock));
    }

    public StartingPitcherSyncResponse sync(LocalDate gameDate) {
        LocalDateTime startedAt = LocalDateTime.now(clock);

        // 게임 목록과 선수 상세 조회를 모두 끝낸 후 짧은 단건 DB 트랜잭션으로 저장한다.
        StartingPitcherCollectionBatch batch = collector.collect(gameDate);

        int inserted = 0;
        int updated = 0;
        int statsSaved = 0;
        List<String> errors = new ArrayList<>(batch.errors());
        LocalDate statDate = LocalDate.now(clock);
        for (CollectedStartingPitcher pitcher : batch.pitchers()) {
            try {
                StartingPitcherWriteResult result = writer.upsert(
                        pitcher,
                        statDate,
                        LocalDateTime.now(clock)
                );
                if (result.inserted()) {
                    inserted++;
                } else {
                    updated++;
                }
                if (result.pitcherStatSaved()) {
                    statsSaved++;
                }
            } catch (RuntimeException exception) {
                errors.add(
                        pitcher.externalGameId()
                                + "/" + pitcher.side()
                                + ": " + safeMessage(exception)
                );
                log.warn(
                        "KBO 선발투수 단건 저장 실패: gameDate={}, externalGameId={}, side={}, error={}",
                        gameDate,
                        pitcher.externalGameId(),
                        pitcher.side(),
                        exception.getMessage(),
                        exception
                );
            }
        }

        LocalDateTime finishedAt = LocalDateTime.now(clock);
        StartingPitcherSyncResponse response = new StartingPitcherSyncResponse(
                gameDate,
                batch.sourceGameCount(),
                batch.pitchers().size(),
                inserted,
                updated,
                statsSaved,
                errors.size(),
                List.copyOf(errors),
                startedAt,
                finishedAt
        );
        log.info(
                "KBO 선발투수 동기화 완료: gameDate={}, sourceGames={}, pitchers={}, inserted={}, updated={}, pitcherStatsSaved={}, failed={}, elapsedMs={}",
                gameDate,
                response.sourceGameCount(),
                response.collectedPitcherCount(),
                inserted,
                updated,
                statsSaved,
                errors.size(),
                Duration.between(startedAt, finishedAt).toMillis()
        );
        return response;
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
