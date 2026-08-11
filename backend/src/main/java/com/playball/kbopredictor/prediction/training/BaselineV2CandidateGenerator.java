package com.playball.kbopredictor.prediction.training;

import com.playball.kbopredictor.prediction.engine.BaselineV2Parameters;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

@Component
public class BaselineV2CandidateGenerator {

    private static final double[] SEASON = {0.10, 0.15, 0.20, 0.25, 0.30, 0.35};
    private static final double[] RECENT_5 = {0.05, 0.10, 0.15, 0.20, 0.25};
    private static final double[] RECENT_10 = {0.03, 0.06, 0.09, 0.12, 0.15, 0.18};
    private static final double[] RUN_5 = {0.05, 0.10, 0.15, 0.20, 0.25};
    private static final double[] RUN_10 = {0.03, 0.06, 0.09, 0.12, 0.15, 0.18};
    private static final double[] VENUE = {0.05, 0.10, 0.15, 0.20, 0.25};
    private static final double[] HOME_ADVANTAGE = {0.00, 0.02, 0.04, 0.06, 0.08};
    private static final double[] LOGISTIC = {0.80, 1.10, 1.40, 1.70, 2.00, 2.30};
    private static final double[] DRAW_MIN = {0.015, 0.020, 0.025, 0.030, 0.040, 0.050};
    private static final double[] DRAW_MAX = {0.025, 0.035, 0.050, 0.070, 0.090, 0.120};
    private static final double[] DRAW_EXPONENT = {0.50, 1.00, 1.50, 2.00, 3.00};
    private static final double[] COVERAGE_SHRINK = {0.25, 0.50, 0.75, 1.00};

    public List<BaselineV2Parameters> generate(int count, long seed) {
        if (count < 1 || count > 100_000) {
            throw new IllegalArgumentException(
                    "Candidate count must be between 1 and 100000."
            );
        }
        Random random = new Random(seed);
        Set<BaselineV2Parameters> candidates = new LinkedHashSet<>();
        while (candidates.size() < count) {
            double[] weights = normalize(new double[]{
                    pick(random, SEASON),
                    pick(random, RECENT_5),
                    pick(random, RECENT_10),
                    pick(random, RUN_5),
                    pick(random, RUN_10),
                    pick(random, VENUE)
            });
            double drawMin = pick(random, DRAW_MIN);
            double drawMax = pick(random, DRAW_MAX);
            if (drawMax < drawMin) {
                continue;
            }
            candidates.add(new BaselineV2Parameters(
                    weights[0], weights[1], weights[2],
                    weights[3], weights[4], weights[5],
                    pick(random, HOME_ADVANTAGE),
                    pick(random, LOGISTIC),
                    drawMin,
                    drawMax,
                    pick(random, DRAW_EXPONENT),
                    pick(random, COVERAGE_SHRINK)
            ));
        }
        return new ArrayList<>(candidates);
    }

    private double[] normalize(double[] values) {
        double total = 0.0;
        for (double value : values) {
            total += value;
        }
        for (int index = 0; index < values.length; index++) {
            values[index] = round(values[index] / total);
        }
        return values;
    }

    private double pick(Random random, double[] values) {
        return values[random.nextInt(values.length)];
    }

    private double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }
}
