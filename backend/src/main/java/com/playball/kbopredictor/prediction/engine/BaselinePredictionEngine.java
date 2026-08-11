package com.playball.kbopredictor.prediction.engine;

import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import com.playball.kbopredictor.prediction.feature.StartingPitcherFeatures;
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
public class BaselinePredictionEngine implements PredictionEngine {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100.00");

    private final BaselineV1ModelProperties properties;

    @Override
    public PredictionEngineResult predict(PredictionFeatures features) {
        TeamPredictionFeatures home = features.home();
        TeamPredictionFeatures away = features.away();
        List<Factor> factors = new ArrayList<>();

        addHigherBetter(
                factors,
                properties.getSeasonWinRateWeight(),
                home.seasonWinRate(),
                away.seasonWinRate(),
                properties.getWinRateDifferenceScale(),
                winnerReason(home, away, "시즌 승률")
        );
        addHigherBetter(
                factors,
                properties.getRecent5WinRateWeight(),
                home.recent5WinRate(),
                away.recent5WinRate(),
                properties.getWinRateDifferenceScale(),
                winnerReason(home, away, "최근 5경기 승률")
        );
        addHigherBetter(
                factors,
                properties.getRecent10WinRateWeight(),
                home.recent10WinRate(),
                away.recent10WinRate(),
                properties.getWinRateDifferenceScale(),
                winnerReason(home, away, "최근 10경기 승률")
        );
        addRunFactor(
                factors,
                properties.getRecent5RunDiffWeight(),
                home.recent5AvgRuns(),
                home.recent5AvgRunsAllowed(),
                away.recent5AvgRuns(),
                away.recent5AvgRunsAllowed(),
                "최근 5경기 득실 균형",
                home,
                away
        );
        addRunFactor(
                factors,
                properties.getRecent10RunDiffWeight(),
                home.recent10AvgRuns(),
                home.recent10AvgRunsAllowed(),
                away.recent10AvgRuns(),
                away.recent10AvgRunsAllowed(),
                "최근 10경기 득실 균형",
                home,
                away
        );
        addHigherBetter(
                factors,
                properties.getVenueWinRateWeight(),
                home.venueWinRate(),
                away.venueWinRate(),
                properties.getWinRateDifferenceScale(),
                new ReasonPair(
                        home.teamName() + "의 홈 승률이 상대 원정 승률보다 높습니다.",
                        away.teamName() + "의 원정 승률이 상대 홈 승률보다 높습니다."
                )
        );
        addHigherBetter(
                factors,
                properties.getBattingAverageWeight(),
                home.battingAverage(),
                away.battingAverage(),
                properties.getBattingAverageDifferenceScale(),
                winnerReason(home, away, "팀 타율")
        );
        addLowerBetter(
                factors,
                properties.getTeamEraWeight(),
                home.era(),
                away.era(),
                properties.getTeamEraDifferenceScale(),
                lowerReason(home, away, "팀 평균자책점")
        );
        addPitcherFactors(factors, home, away);

        double availableWeight = factors.stream()
                .mapToDouble(Factor::weight)
                .sum();
        double weightedDifference = factors.stream()
                .mapToDouble(Factor::weightedDifference)
                .sum();
        double normalizedStrength = availableWeight == 0.0
                ? 0.0
                : weightedDifference / availableWeight;
        double coverageReliability = Math.min(
                1.0,
                availableWeight / properties.getFullStrengthCoverage()
        );
        double adjustedStrength = clamp(
                normalizedStrength * coverageReliability
                        + properties.getHomeAdvantage(),
                -1.0,
                1.0
        );

        double draw = properties.getDrawMinProbability()
                + (properties.getDrawMaxProbability()
                - properties.getDrawMinProbability())
                * (1.0 - Math.abs(adjustedStrength));
        double decisive = 1.0 - draw;
        double homeShare = 1.0
                / (1.0 + Math.exp(-properties.getLogisticScale() * adjustedStrength));
        BigDecimal drawProbability = percent(draw);
        BigDecimal homeProbability = percent(decisive * homeShare);
        BigDecimal awayProbability = ONE_HUNDRED
                .subtract(homeProbability)
                .subtract(drawProbability)
                .setScale(2, RoundingMode.HALF_UP);

        PredictionOutcome predictedOutcome = maxOutcome(
                homeProbability,
                drawProbability,
                awayProbability
        );
        return new PredictionEngineResult(
                homeProbability,
                drawProbability,
                awayProbability,
                predictedOutcome,
                properties.getModelVersion(),
                BigDecimal.valueOf(availableWeight)
                        .setScale(3, RoundingMode.HALF_UP),
                buildReasons(factors, availableWeight, home, away)
        );
    }

    private void addRunFactor(
            List<Factor> factors,
            double weight,
            BigDecimal homeRuns,
            BigDecimal homeAllowed,
            BigDecimal awayRuns,
            BigDecimal awayAllowed,
            String label,
            TeamPredictionFeatures home,
            TeamPredictionFeatures away
    ) {
        if (homeRuns == null || homeAllowed == null
                || awayRuns == null || awayAllowed == null) {
            return;
        }
        double homeNet = homeRuns.subtract(homeAllowed).doubleValue();
        double awayNet = awayRuns.subtract(awayAllowed).doubleValue();
        add(
                factors,
                weight,
                (homeNet - awayNet) / properties.getRunDifferenceScale(),
                winnerReason(home, away, label)
        );
    }

    private void addPitcherFactors(
            List<Factor> factors,
            TeamPredictionFeatures home,
            TeamPredictionFeatures away
    ) {
        StartingPitcherFeatures homePitcher = home.startingPitcher();
        StartingPitcherFeatures awayPitcher = away.startingPitcher();
        if (homePitcher == null || awayPitcher == null) {
            return;
        }
        addLowerBetter(
                factors,
                properties.getStarterEraWeight(),
                homePitcher.era(),
                awayPitcher.era(),
                properties.getStarterEraDifferenceScale(),
                new ReasonPair(
                        home.teamName() + " 선발 " + homePitcher.playerName()
                                + "의 ERA가 상대 선발보다 낮습니다.",
                        away.teamName() + " 선발 " + awayPitcher.playerName()
                                + "의 ERA가 상대 선발보다 낮습니다."
                )
        );
        addLowerBetter(
                factors,
                properties.getStarterWhipWeight(),
                homePitcher.whip(),
                awayPitcher.whip(),
                properties.getStarterWhipDifferenceScale(),
                new ReasonPair(
                        home.teamName() + " 선발 " + homePitcher.playerName()
                                + "의 WHIP가 상대 선발보다 낮습니다.",
                        away.teamName() + " 선발 " + awayPitcher.playerName()
                                + "의 WHIP가 상대 선발보다 낮습니다."
                )
        );
    }

    private void addHigherBetter(
            List<Factor> factors,
            double weight,
            BigDecimal homeValue,
            BigDecimal awayValue,
            double scale,
            ReasonPair reasons
    ) {
        if (homeValue == null || awayValue == null) {
            return;
        }
        add(
                factors,
                weight,
                (homeValue.doubleValue() - awayValue.doubleValue()) / scale,
                reasons
        );
    }

    private void addLowerBetter(
            List<Factor> factors,
            double weight,
            BigDecimal homeValue,
            BigDecimal awayValue,
            double scale,
            ReasonPair reasons
    ) {
        if (homeValue == null || awayValue == null) {
            return;
        }
        add(
                factors,
                weight,
                (awayValue.doubleValue() - homeValue.doubleValue()) / scale,
                reasons
        );
    }

    private void add(
            List<Factor> factors,
            double weight,
            double normalizedDifference,
            ReasonPair reasons
    ) {
        double difference = clamp(normalizedDifference, -1.0, 1.0);
        factors.add(new Factor(
                weight,
                weight * difference,
                difference >= 0 ? reasons.home() : reasons.away()
        ));
    }

    private List<String> buildReasons(
            List<Factor> factors,
            double coverage,
            TeamPredictionFeatures home,
            TeamPredictionFeatures away
    ) {
        List<String> reasons = new ArrayList<>();
        factors.stream()
                .filter(factor -> Math.abs(factor.weightedDifference()) >= 0.003)
                .sorted(Comparator.comparingDouble(
                        factor -> -Math.abs(factor.weightedDifference())
                ))
                .limit(properties.getMaxReasons())
                .map(Factor::reason)
                .forEach(reasons::add);

        if (coverage < properties.getLowDataCoverage()) {
            reasons.add("경기 전 데이터가 일부 부족해 확인 가능한 지표만으로 보수적으로 계산했습니다.");
        }
        if (reasons.isEmpty()) {
            reasons.add(home.teamName() + "와 " + away.teamName()
                    + "의 주요 지표가 비슷해 중립에 가까운 확률을 계산했습니다.");
        }
        return List.copyOf(reasons.stream().limit(5).toList());
    }

    private ReasonPair winnerReason(
            TeamPredictionFeatures home,
            TeamPredictionFeatures away,
            String label
    ) {
        return new ReasonPair(
                home.teamName() + "가 " + label + "에서 우세합니다.",
                away.teamName() + "가 " + label + "에서 우세합니다."
        );
    }

    private ReasonPair lowerReason(
            TeamPredictionFeatures home,
            TeamPredictionFeatures away,
            String label
    ) {
        return new ReasonPair(
                home.teamName() + "의 " + label + "이 상대보다 낮습니다.",
                away.teamName() + "의 " + label + "이 상대보다 낮습니다."
        );
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

    private record Factor(
            double weight,
            double weightedDifference,
            String reason
    ) {
    }

    private record ReasonPair(String home, String away) {
    }
}
