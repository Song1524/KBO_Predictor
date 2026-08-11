package com.playball.kbopredictor.prediction.training;

import com.playball.kbopredictor.prediction.engine.*;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.evaluation.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BaselineV2TrainingService {

    private final HistoricalModelDatasetService datasetService;
    private final BaselineV2CandidateGenerator candidateGenerator;
    private final BaselineV2ParameterOptimizer optimizer;
    private final BaselineV2ProbabilityModel probabilityModel;
    private final PredictionMetricsCalculator metricsCalculator;
    private final BaselineV2ModelProperties properties;

    public BaselineV2TrainingResult train() {
        return train(
                properties.getTrainingFrom(),
                properties.getTrainingTo(),
                properties.getSearchCandidateCount(),
                properties.getSearchSeed()
        );
    }

    public BaselineV2TrainingResult train(
            LocalDate from,
            LocalDate to,
            int candidateCount,
            long seed
    ) {
        List<HistoricalModelSample> samples = datasetService.load(from, to);
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("No training games in the requested period.");
        }
        List<BaselineV2TrainingSample> trainingSamples = samples.stream()
                .map(this::trainingSample)
                .toList();
        BaselineV2OptimizationResult result = optimizer.optimize(
                trainingSamples,
                candidateGenerator.generate(candidateCount, seed)
        );
        ModelEvaluationMetrics metrics = metricsCalculator.evaluate(
                "baseline-v2-trained",
                samples,
                sample -> probabilities(sample, result.parameters())
        );
        return new BaselineV2TrainingResult(
                from,
                to,
                samples.size(),
                count(samples, PredictionOutcome.HOME_WIN),
                count(samples, PredictionOutcome.DRAW),
                count(samples, PredictionOutcome.AWAY_WIN),
                properties.getObjective(),
                seed,
                result,
                metrics
        );
    }

    public ModelProbabilities probabilities(
            HistoricalModelSample sample,
            BaselineV2Parameters parameters
    ) {
        BaselineV2Probability result = probabilityModel.predict(
                vector(sample), parameters
        );
        return ModelProbabilities.of(
                result.home(), result.draw(), result.away(), null
        );
    }

    public BaselineV2FeatureVector vector(HistoricalModelSample sample) {
        return BaselineV2FeatureVector.from(
                sample.features(),
                properties.getWinRateDifferenceScale(),
                properties.getRunDifferenceScale()
        );
    }

    private BaselineV2TrainingSample trainingSample(HistoricalModelSample sample) {
        return new BaselineV2TrainingSample(
                sample.gameDate(),
                vector(sample),
                sample.actualOutcome()
        );
    }

    private int count(
            List<HistoricalModelSample> samples,
            PredictionOutcome outcome
    ) {
        return (int) samples.stream()
                .filter(sample -> sample.actualOutcome() == outcome)
                .count();
    }
}
