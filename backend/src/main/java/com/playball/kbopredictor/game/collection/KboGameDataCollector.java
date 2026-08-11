package com.playball.kbopredictor.game.collection;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class KboGameDataCollector implements GameDataCollector {

    private static final ZoneId KBO_ZONE = ZoneId.of("Asia/Seoul");

    private final KboScheduleClient scheduleClient;
    private final KboScheduleParser scheduleParser;
    private final Map<YearMonth, String> completedMonthCache =
            new ConcurrentHashMap<>();

    @Override
    public GameCollectionBatch collect(LocalDate date) {
        String response = fetchSchedule(YearMonth.from(date));
        return scheduleParser.parse(response, date);
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
            batches.put(date, scheduleParser.parse(response, date));
        }
        return batches;
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
