package com.playball.kbopredictor.prediction.engine;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import com.playball.kbopredictor.prediction.feature.TeamPredictionFeatures;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
public class BaselineV2PredictionCalculator {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100.00");

    private final BaselineV2ProbabilityModel probabilityModel;

    public PredictionEngineResult predict(
            PredictionFeatures features,
            BaselineV2Parameters parameters,
            String modelVersion,
            double winRateDifferenceScale,
            double runDifferenceScale,
            int maxReasons
    ) {
        TeamPredictionFeatures home = features.home();
        TeamPredictionFeatures away = features.away();
        List<Factor> factors = new ArrayList<>();

        addHigherBetter(factors, parameters.seasonWinRateWeight(),
                home.seasonWinRate(), away.seasonWinRate(),
                winRateDifferenceScale, home, away, "시즌 승률");
        addHigherBetter(factors, parameters.recent5WinRateWeight(),
                home.recent5WinRate(), away.recent5WinRate(),
                winRateDifferenceScale, home, away, "최근 5경기 승률");
        addHigherBetter(factors, parameters.recent10WinRateWeight(),
                home.recent10WinRate(), away.recent10WinRate(),
                winRateDifferenceScale, home, away, "최근 10경기 승률");
        addRunFactor(factors, parameters.recent5RunDiffWeight(),
                home.recent5AvgRuns(), home.recent5AvgRunsAllowed(),
                away.recent5AvgRuns(), away.recent5AvgRunsAllowed(),
                runDifferenceScale, home, away, "최근 5경기 득실 균형");
        addRunFactor(factors, parameters.recent10RunDiffWeight(),
                home.recent10AvgRuns(), home.recent10AvgRunsAllowed(),
                away.recent10AvgRuns(), away.recent10AvgRunsAllowed(),
                runDifferenceScale, home, away, "최근 10경기 득실 균형");
        addHigherBetter(factors, parameters.venueWinRateWeight(),
                home.venueWinRate(), away.venueWinRate(),
                winRateDifferenceScale, home, away, "홈/원정 승률");

        BaselineV2Probability probability = probabilityModel.predict(
                BaselineV2FeatureVector.from(
                        features,
                        winRateDifferenceScale,
                        runDifferenceScale
                ),
                parameters
        );

        BigDecimal drawProbability = percent(probability.draw());
        BigDecimal homeProbability = percent(probability.home());
        BigDecimal awayProbability = ONE_HUNDRED
                .subtract(homeProbability)
                .subtract(drawProbability)
                .setScale(2, RoundingMode.HALF_UP);

        return new PredictionEngineResult(
                homeProbability,
                drawProbability,
                awayProbability,
                maxOutcome(homeProbability, drawProbability, awayProbability),
                modelVersion,
                BigDecimal.valueOf(probability.featureCoverage())
                        .setScale(3, RoundingMode.HALF_UP),
                reasons(factors, probability.featureCoverage(),
                        home, away, maxReasons)
        );
    }

    private void addRunFactor(
            List<Factor> factors,
            double weight,
            BigDecimal homeRuns,
            BigDecimal homeAllowed,
            BigDecimal awayRuns,
            BigDecimal awayAllowed,
            double scale,
            TeamPredictionFeatures home,
            TeamPredictionFeatures away,
            String label
    ) {
        if (homeRuns == null || homeAllowed == null
                || awayRuns == null || awayAllowed == null) {
            return;
        }
        double difference = homeRuns.subtract(homeAllowed).doubleValue()
                - awayRuns.subtract(awayAllowed).doubleValue();
        add(factors, weight, difference / scale, home, away, label);
    }

    private void addHigherBetter(
            List<Factor> factors,
            double weight,
            BigDecimal homeValue,
            BigDecimal awayValue,
            double scale,
            TeamPredictionFeatures home,
            TeamPredictionFeatures away,
            String label
    ) {
        if (homeValue == null || awayValue == null) {
            return;
        }
        add(factors, weight,
                (homeValue.doubleValue() - awayValue.doubleValue()) / scale,
                home, away, label);
    }

    private void add(
            List<Factor> factors,
            double weight,
            double normalizedDifference,
            TeamPredictionFeatures home,
            TeamPredictionFeatures away,
            String label
    ) {
        if (weight <= 0.0) {
            return;
        }
        double difference = clamp(normalizedDifference, -1.0, 1.0);
        String leadingTeam = difference >= 0.0
                ? home.teamName()
                : away.teamName();
        factors.add(new Factor(
                weight,
                weight * difference,
                leadingTeam + "이(가) " + label + "에서 우세합니다."
        ));
    }

    private List<String> reasons(
            List<Factor> factors,
            double coverage,
            TeamPredictionFeatures home,
            TeamPredictionFeatures away,
            int maxReasons
    ) {
        List<String> reasons = new ArrayList<>();
        factors.stream()
                .filter(factor -> Math.abs(factor.weightedDifference()) >= 0.003)
                .sorted(Comparator.comparingDouble(
                        factor -> -Math.abs(factor.weightedDifference())))
                .limit(maxReasons)
                .map(Factor::reason)
                .forEach(reasons::add);
        if (coverage < 1.0) {
            reasons.add("사용 가능한 경기 전 지표만으로 계산해 불확실성을 축소 반영했습니다.");
        }
        if (reasons.isEmpty()) {
            reasons.add(home.teamName() + "과(와) " + away.teamName()
                    + "의 경기 전 지표가 비슷해 중립적인 확률에 가깝습니다.");
        }
        return List.copyOf(reasons.stream().limit(5).toList());
    }

    private BigDecimal percent(double probability) {
        return BigDecimal.valueOf(probability * 100.0)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private PredictionOutcome maxOutcome(
            BigDecimal home,
            BigDecimal draw,
            BigDecimal away
    ) {
        if (draw.compareTo(home) > 0 && draw.compareTo(away) >= 0) {
            return PredictionOutcome.DRAW;
        }
        return home.compareTo(away) >= 0
                ? PredictionOutcome.HOME_WIN
                : PredictionOutcome.AWAY_WIN;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Factor(double weight, double weightedDifference, String reason) {
    }
}
