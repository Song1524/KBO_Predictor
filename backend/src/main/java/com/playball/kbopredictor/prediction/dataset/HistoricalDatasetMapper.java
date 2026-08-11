package com.playball.kbopredictor.prediction.dataset;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.engine.LogisticFeatureValues;
import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import com.playball.kbopredictor.prediction.history.PredictionFeatureSnapshot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class HistoricalDatasetMapper {

    public static final int CORE_FEATURE_COUNT = 6;

    public HistoricalMlDatasetRow toRow(PredictionFeatureSnapshot snapshot) {
        PredictionFeatures features = snapshot.toPredictionFeatures();
        Map<String, Double> values = LogisticFeatureValues.from(features);
        BigDecimal season = decimal(values.get(
                LogisticFeatureValues.SEASON_WIN_RATE_DIFF
        ));
        BigDecimal recent5 = decimal(values.get(
                LogisticFeatureValues.RECENT_5_WIN_RATE_DIFF
        ));
        BigDecimal recent10 = decimal(values.get(
                LogisticFeatureValues.RECENT_10_WIN_RATE_DIFF
        ));
        BigDecimal recent5Runs = decimal(values.get(
                LogisticFeatureValues.RECENT_5_RUN_DIFF
        ));
        BigDecimal recent10Runs = decimal(values.get(
                LogisticFeatureValues.RECENT_10_RUN_DIFF
        ));
        BigDecimal venue = decimal(values.get(
                LogisticFeatureValues.HOME_AWAY_WIN_RATE_DIFF
        ));
        int available = (int) Stream.of(
                season,
                recent5,
                recent10,
                recent5Runs,
                recent10Runs,
                venue
        ).filter(value -> value != null).count();

        return new HistoricalMlDatasetRow(
                snapshot.getGame().getId(),
                snapshot.getGame().getSeason(),
                snapshot.getGame().getGameDate(),
                season,
                recent5,
                recent10,
                recent5Runs,
                recent10Runs,
                venue,
                available,
                BigDecimal.valueOf(available)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(
                                BigDecimal.valueOf(CORE_FEATURE_COUNT),
                                2,
                                RoundingMode.HALF_UP
                        ),
                PredictionOutcome.valueOf(
                        snapshot.getGame().getResult().name()
                )
        );
    }

    private BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
