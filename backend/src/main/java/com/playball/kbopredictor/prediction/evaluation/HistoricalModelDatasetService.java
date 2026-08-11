package com.playball.kbopredictor.prediction.evaluation;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.history.PredictionFeatureSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HistoricalModelDatasetService {

    private final PredictionFeatureSnapshotRepository snapshotRepository;

    public List<HistoricalModelSample> load(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("Invalid historical dataset period.");
        }
        return snapshotRepository.findEvaluationSnapshots(from, to).stream()
                .map(snapshot -> new HistoricalModelSample(
                        snapshot.getGame().getId(),
                        snapshot.getGame().getGameDate(),
                        snapshot.toPredictionFeatures(),
                        PredictionOutcome.valueOf(
                                snapshot.getGame().getResult().name()
                        )
                ))
                .toList();
    }
}
