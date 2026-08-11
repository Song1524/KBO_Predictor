package com.playball.kbopredictor.prediction.dataset;

import com.playball.kbopredictor.prediction.history.PredictionFeatureSnapshot;
import com.playball.kbopredictor.prediction.history.PredictionFeatureSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HistoricalMlDatasetService {

    private final PredictionFeatureSnapshotRepository snapshotRepository;
    private final HistoricalDatasetMapper mapper;

    public HistoricalMlDatasetResponse load(LocalDate from, LocalDate to) {
        validate(from, to);
        List<PredictionFeatureSnapshot> snapshots =
                snapshotRepository.findEvaluationSnapshots(from, to);
        List<HistoricalMlDatasetRow> rows = snapshots.stream()
                .map(mapper::toRow)
                .filter(row -> row.availableFeatureCount() > 0)
                .toList();
        return new HistoricalMlDatasetResponse(
                from,
                to,
                snapshots.size(),
                rows.size(),
                snapshots.size() - rows.size(),
                rows
        );
    }

    public String toCsv(LocalDate from, LocalDate to) {
        HistoricalMlDatasetResponse dataset = load(from, to);
        StringBuilder csv = new StringBuilder(
                "gameId,season,gameDate,seasonWinRateDiff,"
                        + "recent5WinRateDiff,recent10WinRateDiff,"
                        + "recent5RunDiff,recent10RunDiff,"
                        + "homeAwayWinRateDiff,availableFeatureCount,"
                        + "featureCoverage,actualResult\n"
        );
        for (HistoricalMlDatasetRow row : dataset.rows()) {
            csv.append(row.gameId()).append(',')
                    .append(row.season()).append(',')
                    .append(row.gameDate()).append(',')
                    .append(value(row.seasonWinRateDiff())).append(',')
                    .append(value(row.recent5WinRateDiff())).append(',')
                    .append(value(row.recent10WinRateDiff())).append(',')
                    .append(value(row.recent5RunDiff())).append(',')
                    .append(value(row.recent10RunDiff())).append(',')
                    .append(value(row.homeAwayWinRateDiff())).append(',')
                    .append(row.availableFeatureCount()).append(',')
                    .append(row.featureCoverage()).append(',')
                    .append(row.actualResult()).append('\n');
        }
        return csv.toString();
    }

    private String value(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private void validate(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("Invalid dataset period.");
        }
    }
}
