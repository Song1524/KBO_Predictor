package com.playball.kbopredictor.prediction.training;

import com.playball.kbopredictor.prediction.engine.BaselineV2Parameters;
import com.playball.kbopredictor.prediction.engine.BaselineV2Probability;
import com.playball.kbopredictor.prediction.engine.BaselineV2ProbabilityModel;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
@RequiredArgsConstructor
public class BaselineV2ParameterOptimizer {

    private static final double MIN_PROBABILITY = 1.0e-15;
    private static final double EPSILON = 1.0e-12;

    private final BaselineV2ProbabilityModel probabilityModel;

    public BaselineV2OptimizationResult optimize(
            List<BaselineV2TrainingSample> samples,
            List<BaselineV2Parameters> candidates
    ) {
        if (samples.isEmpty() || candidates.isEmpty()) {
            throw new IllegalArgumentException("Training samples and candidates are required.");
        }
        CandidateScore best = null;
        for (BaselineV2Parameters candidate : candidates) {
            CandidateScore score = score(samples, candidate);
            if (best == null || score.betterThan(best)) {
                best = score;
            }
        }
        return new BaselineV2OptimizationResult(
                best.parameters(),
                candidates.size(),
                decimal(best.logLoss(), 9),
                decimal(best.brier(), 9),
                decimal(best.accuracy() * 100.0, 4)
        );
    }

    private CandidateScore score(
            List<BaselineV2TrainingSample> samples,
            BaselineV2Parameters parameters
    ) {
        double logLoss = 0.0;
        double brier = 0.0;
        int correct = 0;
        for (BaselineV2TrainingSample sample : samples) {
            BaselineV2Probability probability = probabilityModel.predict(
                    sample.features(), parameters
            );
            PredictionOutcome actual = sample.actualOutcome();
            double actualProbability = switch (actual) {
                case HOME_WIN -> probability.home();
                case DRAW -> probability.draw();
                case AWAY_WIN -> probability.away();
            };
            logLoss += -Math.log(Math.max(MIN_PROBABILITY, actualProbability));
            brier += square(probability.home() - indicator(actual, PredictionOutcome.HOME_WIN))
                    + square(probability.draw() - indicator(actual, PredictionOutcome.DRAW))
                    + square(probability.away() - indicator(actual, PredictionOutcome.AWAY_WIN));
            if (predicted(probability) == actual) {
                correct++;
            }
        }
        return new CandidateScore(
                parameters,
                logLoss / samples.size(),
                brier / samples.size(),
                (double) correct / samples.size()
        );
    }

    private PredictionOutcome predicted(BaselineV2Probability probability) {
        if (probability.draw() > probability.home()
                && probability.draw() >= probability.away()) {
            return PredictionOutcome.DRAW;
        }
        return probability.home() >= probability.away()
                ? PredictionOutcome.HOME_WIN
                : PredictionOutcome.AWAY_WIN;
    }

    private double indicator(PredictionOutcome actual, PredictionOutcome target) {
        return actual == target ? 1.0 : 0.0;
    }

    private double square(double value) {
        return value * value;
    }

    private BigDecimal decimal(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private record CandidateScore(
            BaselineV2Parameters parameters,
            double logLoss,
            double brier,
            double accuracy
    ) {
        private boolean betterThan(CandidateScore other) {
            if (logLoss < other.logLoss - EPSILON) {
                return true;
            }
            if (Math.abs(logLoss - other.logLoss) > EPSILON) {
                return false;
            }
            if (brier < other.brier - EPSILON) {
                return true;
            }
            if (Math.abs(brier - other.brier) > EPSILON) {
                return false;
            }
            return accuracy > other.accuracy;
        }
    }
}
