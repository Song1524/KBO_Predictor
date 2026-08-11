package com.playball.kbopredictor.prediction.backfill;

import com.playball.kbopredictor.game.collection.GameSyncResponse;
import com.playball.kbopredictor.game.collection.GameSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class HistoricalGameDataSynchronizer {

    private static final int KBO_SEASON_FIRST_MONTH = 3;
    private static final int KBO_SEASON_LAST_MONTH = 10;

    private final GameSyncService gameSyncService;

    public HistoricalGameSyncSummary syncSeasonThrough(LocalDate through) {
        return syncRange(
                LocalDate.of(through.getYear(), 1, 1),
                through
        );
    }

    public HistoricalGameSyncSummary syncRange(
            LocalDate from,
            LocalDate to
    ) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException(
                    "Invalid historical game sync period."
            );
        }
        List<String> errors = new ArrayList<>();
        int requestedMonths = 0;
        int successfulMonths = 0;
        int inserted = 0;
        int updated = 0;

        YearMonth month = YearMonth.from(from);
        YearMonth lastMonth = YearMonth.from(to);
        while (!month.isAfter(lastMonth)) {
            if (!isRegularSeasonMonth(month)) {
                month = month.plusMonths(1);
                continue;
            }
            requestedMonths++;
            LocalDate firstDate = month.equals(YearMonth.from(from))
                    ? from
                    : month.atDay(1);
            LocalDate lastDate = month.equals(lastMonth)
                    ? to
                    : month.atEndOfMonth();
            List<LocalDate> dates = firstDate.datesUntil(lastDate.plusDays(1))
                    .toList();
            try {
                List<GameSyncResponse> responses = gameSyncService.syncDates(dates);
                successfulMonths++;
                inserted += responses.stream()
                        .mapToInt(GameSyncResponse::insertedCount)
                        .sum();
                updated += responses.stream()
                        .mapToInt(GameSyncResponse::updatedCount)
                        .sum();
                responses.stream()
                        .flatMap(response -> response.errors().stream())
                        .forEach(errors::add);
            } catch (RuntimeException exception) {
                String message = month + ": " + safeMessage(exception);
                errors.add(message);
                log.warn(
                        "Historical KBO month sync failed; continuing: month={}, error={}",
                        month,
                        safeMessage(exception)
                );
            }
            month = month.plusMonths(1);
        }
        return new HistoricalGameSyncSummary(
                true,
                requestedMonths,
                successfulMonths,
                requestedMonths - successfulMonths,
                inserted,
                updated,
                errors
        );
    }

    private boolean isRegularSeasonMonth(YearMonth month) {
        return month.getMonthValue() >= KBO_SEASON_FIRST_MONTH
                && month.getMonthValue() <= KBO_SEASON_LAST_MONTH;
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
