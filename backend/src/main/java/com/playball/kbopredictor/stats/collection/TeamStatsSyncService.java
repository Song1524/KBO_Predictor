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
public class TeamStatsSyncService {

    private final TeamStatsCollector collector;
    private final TeamStatSnapshotWriter writer;
    private final Clock clock;

    public TeamStatsSyncResponse syncToday() {
        return sync(LocalDate.now(clock));
    }

    TeamStatsSyncResponse sync(LocalDate statDate) {
        LocalDateTime startedAt = LocalDateTime.now(clock);

        // 외부 HTTP 호출과 DB 트랜잭션을 분리한다. 세 응답이 모두 정상인 경우에만 저장을 시작한다.
        List<CollectedTeamStat> collected = collector.collect();

        int inserted = 0;
        int updated = 0;
        List<String> errors = new ArrayList<>();
        for (CollectedTeamStat teamStat : collected) {
            try {
                TeamStatWriteResult result = writer.upsert(
                        teamStat,
                        statDate.getYear(),
                        statDate,
                        LocalDateTime.now(clock)
                );
                if (result.inserted()) {
                    inserted++;
                } else {
                    updated++;
                }
            } catch (RuntimeException exception) {
                errors.add(teamStat.teamCode() + ": " + safeMessage(exception));
                log.warn(
                        "KBO 팀 통계 단건 저장 실패: statDate={}, teamCode={}, error={}",
                        statDate,
                        teamStat.teamCode(),
                        exception.getMessage(),
                        exception
                );
            }
        }

        LocalDateTime finishedAt = LocalDateTime.now(clock);
        TeamStatsSyncResponse response = new TeamStatsSyncResponse(
                statDate,
                collected.size(),
                inserted,
                updated,
                errors.size(),
                List.copyOf(errors),
                startedAt,
                finishedAt
        );
        log.info(
                "KBO 팀 통계 동기화 완료: statDate={}, sourceTeams={}, inserted={}, updated={}, failed={}, elapsedMs={}",
                statDate,
                collected.size(),
                inserted,
                updated,
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
