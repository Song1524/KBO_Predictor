package com.playball.kbopredictor.prediction.engine;

import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import com.playball.kbopredictor.prediction.feature.TeamPredictionFeatures;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

public final class LogisticFeatureValues {

    public static final String SEASON_WIN_RATE_DIFF = "seasonWinRateDiff";
    public static final String RECENT_5_WIN_RATE_DIFF = "recent5WinRateDiff";
    public static final String RECENT_10_WIN_RATE_DIFF = "recent10WinRateDiff";
    public static final String RECENT_5_RUN_DIFF = "recent5RunDiff";
    public static final String RECENT_10_RUN_DIFF = "recent10RunDiff";
    public static final String HOME_AWAY_WIN_RATE_DIFF = "homeAwayWinRateDiff";

    public static final List<String> SUPPORTED_FEATURES = List.of(
            SEASON_WIN_RATE_DIFF,
            RECENT_5_WIN_RATE_DIFF,
            RECENT_10_WIN_RATE_DIFF,
            RECENT_5_RUN_DIFF,
            RECENT_10_RUN_DIFF,
            HOME_AWAY_WIN_RATE_DIFF
    );

    private LogisticFeatureValues() {
    }

    public static Map<String, Double> from(PredictionFeatures features) {
        TeamPredictionFeatures home = features.home();
        TeamPredictionFeatures away = features.away();
        Map<String, Double> values = new LinkedHashMap<>();
        values.put(
                SEASON_WIN_RATE_DIFF,
                difference(home.seasonWinRate(), away.seasonWinRate())
        );
        values.put(
                RECENT_5_WIN_RATE_DIFF,
                difference(home.recent5WinRate(), away.recent5WinRate())
        );
        values.put(
                RECENT_10_WIN_RATE_DIFF,
                difference(home.recent10WinRate(), away.recent10WinRate())
        );
        values.put(RECENT_5_RUN_DIFF, runDifference(home, away, true));
        values.put(RECENT_10_RUN_DIFF, runDifference(home, away, false));
        values.put(
                HOME_AWAY_WIN_RATE_DIFF,
                difference(home.venueWinRate(), away.venueWinRate())
        );
        return Collections.unmodifiableMap(values);
    }

    private static Double runDifference(
            TeamPredictionFeatures home,
            TeamPredictionFeatures away,
            boolean recent5
    ) {
        BigDecimal homeRuns = recent5
                ? home.recent5AvgRuns()
                : home.recent10AvgRuns();
        BigDecimal homeAllowed = recent5
                ? home.recent5AvgRunsAllowed()
                : home.recent10AvgRunsAllowed();
        BigDecimal awayRuns = recent5
                ? away.recent5AvgRuns()
                : away.recent10AvgRuns();
        BigDecimal awayAllowed = recent5
                ? away.recent5AvgRunsAllowed()
                : away.recent10AvgRunsAllowed();
        if (homeRuns == null || homeAllowed == null
                || awayRuns == null || awayAllowed == null) {
            return null;
        }
        return homeRuns.subtract(homeAllowed)
                .subtract(awayRuns.subtract(awayAllowed))
                .doubleValue();
    }

    private static Double difference(BigDecimal home, BigDecimal away) {
        return home == null || away == null
                ? null
                : home.subtract(away).doubleValue();
    }
}
