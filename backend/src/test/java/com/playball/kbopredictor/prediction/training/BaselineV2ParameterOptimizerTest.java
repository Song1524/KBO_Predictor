package com.playball.kbopredictor.prediction.training;

import com.playball.kbopredictor.prediction.engine.BaselineV2FeatureVector;
import com.playball.kbopredictor.prediction.engine.BaselineV2Parameters;
import com.playball.kbopredictor.prediction.engine.BaselineV2ProbabilityModel;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineV2ParameterOptimizerTest {

    @Test
    void sameSeedDataAndSearchSizeProduceSameParameters() {
        BaselineV2CandidateGenerator generator =
                new BaselineV2CandidateGenerator();
        BaselineV2ParameterOptimizer optimizer =
                new BaselineV2ParameterOptimizer(
                        new BaselineV2ProbabilityModel()
                );
        List<BaselineV2TrainingSample> samples = List.of(
                sample(0.6, PredictionOutcome.HOME_WIN),
                sample(-0.6, PredictionOutcome.AWAY_WIN),
                sample(0.0, PredictionOutcome.DRAW)
        );
        List<BaselineV2Parameters> firstCandidates = generator.generate(
                200, 20260811L
        );
        List<BaselineV2Parameters> secondCandidates = generator.generate(
                200, 20260811L
        );

        BaselineV2OptimizationResult first = optimizer.optimize(
                samples, firstCandidates
        );
        BaselineV2OptimizationResult second = optimizer.optimize(
                samples, secondCandidates
        );

        assertThat(firstCandidates).isEqualTo(secondCandidates);
        assertThat(first).isEqualTo(second);
    }

    private BaselineV2TrainingSample sample(
            double strength,
            PredictionOutcome outcome
    ) {
        return new BaselineV2TrainingSample(
                LocalDate.of(2026, 6, 1),
                new BaselineV2FeatureVector(
                        strength, strength, strength,
                        strength, strength, strength
                ),
                outcome
        );
    }
}
