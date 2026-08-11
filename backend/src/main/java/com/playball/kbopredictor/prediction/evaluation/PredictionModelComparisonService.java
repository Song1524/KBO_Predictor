package com.playball.kbopredictor.prediction.evaluation;

import com.playball.kbopredictor.prediction.engine.*;
import com.playball.kbopredictor.prediction.entity.PredictionOutcome;
import com.playball.kbopredictor.prediction.training.BaselineV2TrainingResult;
import com.playball.kbopredictor.prediction.training.BaselineV2TrainingService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

@Service
public class PredictionModelComparisonService {

    private final HistoricalModelDatasetService datasetService;
    private final PredictionMetricsCalculator metricsCalculator;
    private final PredictionEngine baselineV1;
    private final PredictionEngine baselineV2;
    private final BaselineV1ModelProperties v1Properties;
    private final BaselineV2ModelProperties v2Properties;
    private final ActivePredictionEngine activeEngine;
    private final BaselineV2TrainingService trainingService;
    private final BaselineV2ProbabilityModel probabilityModel;

    public PredictionModelComparisonService(
            HistoricalModelDatasetService datasetService,
            PredictionMetricsCalculator metricsCalculator,
            @Qualifier("baselinePredictionEngine") PredictionEngine baselineV1,
            @Qualifier("baselineV2PredictionEngine") PredictionEngine baselineV2,
            BaselineV1ModelProperties v1Properties,
            BaselineV2ModelProperties v2Properties,
            ActivePredictionEngine activeEngine,
            BaselineV2TrainingService trainingService,
            BaselineV2ProbabilityModel probabilityModel
    ) {
        this.datasetService = datasetService;
        this.metricsCalculator = metricsCalculator;
        this.baselineV1 = baselineV1;
        this.baselineV2 = baselineV2;
        this.v1Properties = v1Properties;
        this.v2Properties = v2Properties;
        this.activeEngine = activeEngine;
        this.trainingService = trainingService;
        this.probabilityModel = probabilityModel;
    }

    public ModelComparisonResponse compare(boolean includeWalkForward) {
        return compare(
                v2Properties.getTrainingFrom(),
                v2Properties.getTrainingTo(),
                v2Properties.getValidationFrom(),
                v2Properties.getValidationTo(),
                includeWalkForward
        );
    }

    public ModelComparisonResponse compare(
            LocalDate trainingFrom,
            LocalDate trainingTo,
            LocalDate validationFrom,
            LocalDate validationTo,
            boolean includeWalkForward
    ) {
        validatePeriods(trainingFrom, trainingTo, validationFrom, validationTo);
        List<HistoricalModelSample> training = datasetService.load(
                trainingFrom, trainingTo
        );
        List<HistoricalModelSample> validation = datasetService.load(
                validationFrom, validationTo
        );
        if (training.isEmpty() || validation.isEmpty()) {
            throw new IllegalArgumentException("Training and validation games are required.");
        }
        OutcomePrior prior = prior(training);
        List<ModelEvaluationMetrics> trainingMetrics = evaluateModels(
                training, prior, baselineV2, "baseline-v2"
        );
        List<ModelEvaluationMetrics> validationMetrics = evaluateModels(
                validation, prior, baselineV2, "baseline-v2"
        );
        return new ModelComparisonResponse(
                activeEngine.activeModel(),
                recommendation(validationMetrics),
                BaselineV1ConfigurationResponse.from(v1Properties),
                BaselineV2ConfigurationResponse.from(v2Properties),
                trainingFrom,
                trainingTo,
                training.size(),
                validationFrom,
                validationTo,
                validation.size(),
                trainingMetrics,
                validationMetrics,
                drawAnalysis(training),
                includeWalkForward
                        ? walkForward(trainingFrom, trainingTo,
                        validationFrom, validationTo)
                        : List.of()
        );
    }

    private List<ModelEvaluationMetrics> evaluateModels(
            List<HistoricalModelSample> samples,
            OutcomePrior trainingPrior,
            PredictionEngine v2Engine,
            String v2Name
    ) {
        return List.of(
                metricsCalculator.evaluate(
                        "always-home", samples,
                        ignored -> trainingPrior.alwaysHome()
                ),
                metricsCalculator.evaluate(
                        "season-win-rate", samples,
                        sample -> seasonWinRate(sample, trainingPrior.draw())
                ),
                metricsCalculator.evaluate(
                        "baseline-v1", samples,
                        sample -> ModelProbabilities.from(
                                baselineV1.predict(sample.features())
                        )
                ),
                metricsCalculator.evaluate(
                        v2Name, samples,
                        sample -> ModelProbabilities.from(
                                v2Engine.predict(sample.features())
                        )
                )
        );
    }

    private List<WalkForwardFoldResponse> walkForward(
            LocalDate trainingFrom,
            LocalDate trainingTo,
            LocalDate finalValidationFrom,
            LocalDate finalValidationTo
    ) {
        List<Period> periods = new ArrayList<>();
        YearMonth firstMonth = YearMonth.from(trainingFrom);
        YearMonth cursor = firstMonth.plusMonths(1);
        while (!cursor.atDay(1).isAfter(trainingTo)) {
            LocalDate foldTrainingTo = cursor.atDay(1).minusDays(1);
            LocalDate evaluationFrom = cursor.atDay(1);
            LocalDate evaluationTo = min(cursor.atEndOfMonth(), trainingTo);
            if (!evaluationFrom.isAfter(evaluationTo)) {
                periods.add(new Period(
                        trainingFrom, foldTrainingTo,
                        evaluationFrom, evaluationTo
                ));
            }
            cursor = cursor.plusMonths(1);
        }
        periods.add(new Period(
                trainingFrom, trainingTo,
                finalValidationFrom, finalValidationTo
        ));

        List<WalkForwardFoldResponse> folds = new ArrayList<>();
        for (Period period : periods) {
            BaselineV2TrainingResult trained = trainingService.train(
                    period.trainingFrom(),
                    period.trainingTo(),
                    v2Properties.getSearchCandidateCount(),
                    v2Properties.getSearchSeed()
            );
            List<HistoricalModelSample> evaluation = datasetService.load(
                    period.evaluationFrom(), period.evaluationTo()
            );
            if (evaluation.isEmpty()) {
                continue;
            }
            OutcomePrior foldPrior = prior(datasetService.load(
                    period.trainingFrom(), period.trainingTo()
            ));
            BaselineV2Parameters selected = trained.optimization().parameters();
            PredictionEngine trainedEngine = features -> {
                HistoricalModelSample wrapper = new HistoricalModelSample(
                        features.gameId(), features.gameDate(), features,
                        PredictionOutcome.HOME_WIN
                );
                ModelProbabilities probability = trainingService.probabilities(
                        wrapper, selected
                );
                return engineResult(probability);
            };
            folds.add(new WalkForwardFoldResponse(
                    period.trainingFrom(),
                    period.trainingTo(),
                    trained.trainingGameCount(),
                    period.evaluationFrom(),
                    period.evaluationTo(),
                    evaluation.size(),
                    trained.optimization().candidateCount(),
                    selected,
                    evaluateModels(
                            evaluation,
                            foldPrior,
                            trainedEngine,
                            "baseline-v2-fold"
                    )
            ));
        }
        return folds;
    }

    private PredictionEngineResult engineResult(ModelProbabilities probability) {
        BigDecimal home = percentage(probability.home());
        BigDecimal draw = percentage(probability.draw());
        BigDecimal away = new BigDecimal("100.00").subtract(home).subtract(draw);
        return new PredictionEngineResult(
                home, draw, away,
                probability.predictedOutcome(),
                "baseline-v2-fold",
                BigDecimal.ONE,
                List.of("Walk-forward train-only parameters")
        );
    }

    private ModelProbabilities seasonWinRate(
            HistoricalModelSample sample,
            double drawPrior
    ) {
        BigDecimal homeRate = sample.features().home().seasonWinRate();
        BigDecimal awayRate = sample.features().away().seasonWinRate();
        if (homeRate == null || awayRate == null) {
            return ModelProbabilities.of(
                    (1.0 - drawPrior) / 2.0,
                    drawPrior,
                    (1.0 - drawPrior) / 2.0,
                    PredictionOutcome.HOME_WIN
            );
        }
        double difference = homeRate.doubleValue() - awayRate.doubleValue();
        double homeShare = 1.0 / (1.0 + Math.exp(-4.0 * difference));
        PredictionOutcome predicted = difference >= 0.0
                ? PredictionOutcome.HOME_WIN
                : PredictionOutcome.AWAY_WIN;
        return ModelProbabilities.of(
                (1.0 - drawPrior) * homeShare,
                drawPrior,
                (1.0 - drawPrior) * (1.0 - homeShare),
                predicted
        );
    }

    private OutcomePrior prior(List<HistoricalModelSample> samples) {
        int home = count(samples, PredictionOutcome.HOME_WIN);
        int draw = count(samples, PredictionOutcome.DRAW);
        int away = count(samples, PredictionOutcome.AWAY_WIN);
        double denominator = samples.size() + 3.0;
        return new OutcomePrior(
                (home + 1.0) / denominator,
                (draw + 1.0) / denominator,
                (away + 1.0) / denominator
        );
    }

    private DrawSignalAnalysisResponse drawAnalysis(
            List<HistoricalModelSample> training
    ) {
        List<SignalRow> draw = signalRows(training, true);
        List<SignalRow> nonDraw = signalRows(training, false);
        StrengthDistributionResponse drawStrength = distribution(draw);
        StrengthDistributionResponse nonDrawStrength = distribution(nonDraw);
        String conclusion = drawStrength.averageAbsoluteStrength()
                .compareTo(nonDrawStrength.averageAbsoluteStrength()) < 0
                ? "Train 데이터에서는 DRAW 경기의 strength가 조금 더 가깝지만 표본이 적어 일반화 근거가 약합니다."
                : "Train 데이터에서는 DRAW 경기가 더 비슷한 strength를 보이지 않아 구분 신호가 확인되지 않았습니다.";
        return new DrawSignalAnalysisResponse(
                "TRAIN_ONLY",
                drawStrength,
                nonDrawStrength,
                differences(draw),
                differences(nonDraw),
                conclusion
        );
    }

    private List<SignalRow> signalRows(
            List<HistoricalModelSample> samples,
            boolean draw
    ) {
        return samples.stream()
                .filter(sample -> (sample.actualOutcome() == PredictionOutcome.DRAW) == draw)
                .map(sample -> {
                    BaselineV2FeatureVector vector = trainingService.vector(sample);
                    BaselineV2Probability probability = probabilityModel.predict(
                            vector, v2Properties.toParameters()
                    );
                    return new SignalRow(
                            Math.abs(probability.strength()),
                            absolute(vector.seasonWinRateDifference(), 0.25),
                            absolute(vector.recent5WinRateDifference(), 0.25),
                            absolute(vector.recent10WinRateDifference(), 0.25),
                            absolute(vector.recent5RunDifference(), 4.0),
                            absolute(vector.recent10RunDifference(), 4.0),
                            absolute(vector.venueWinRateDifference(), 0.25)
                    );
                })
                .toList();
    }

    private StrengthDistributionResponse distribution(List<SignalRow> rows) {
        List<Double> values = rows.stream()
                .map(SignalRow::absoluteStrength)
                .sorted()
                .toList();
        if (values.isEmpty()) {
            return new StrengthDistributionResponse(0, null, null, null, null);
        }
        return new StrengthDistributionResponse(
                values.size(),
                decimal(values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0)),
                decimal(median(values)),
                decimal(values.getFirst()),
                decimal(values.getLast())
        );
    }

    private DrawFeatureDifferenceResponse differences(List<SignalRow> rows) {
        return new DrawFeatureDifferenceResponse(
                average(rows, SignalRow::season),
                average(rows, SignalRow::recent5),
                average(rows, SignalRow::recent10),
                average(rows, SignalRow::run5),
                average(rows, SignalRow::run10),
                average(rows, SignalRow::venue)
        );
    }

    private BigDecimal average(
            List<SignalRow> rows,
            java.util.function.ToDoubleFunction<SignalRow> extractor
    ) {
        return rows.isEmpty() ? null : decimal(
                rows.stream().mapToDouble(extractor).average().orElse(0.0)
        );
    }

    private double median(List<Double> values) {
        int middle = values.size() / 2;
        return values.size() % 2 == 0
                ? (values.get(middle - 1) + values.get(middle)) / 2.0
                : values.get(middle);
    }

    private double absolute(Double normalized, double scale) {
        return normalized == null ? 0.0 : Math.abs(normalized) * scale;
    }

    private String recommendation(List<ModelEvaluationMetrics> metrics) {
        ModelEvaluationMetrics v1 = find(metrics, "baseline-v1");
        ModelEvaluationMetrics v2 = find(metrics, "baseline-v2");
        boolean improved = v2.logLoss().compareTo(v1.logLoss()) < 0
                && v2.brierScore().compareTo(v1.brierScore()) <= 0
                && v2.accuracy().compareTo(v1.accuracy()) >= 0;
        return improved
                ? "VALIDATION_SUPPORTS_V2_REVIEW_BEFORE_ACTIVATION"
                : "KEEP_BASELINE_V1";
    }

    private ModelEvaluationMetrics find(
            List<ModelEvaluationMetrics> metrics,
            String model
    ) {
        return metrics.stream()
                .filter(value -> value.model().equals(model))
                .findFirst()
                .orElseThrow();
    }

    private int count(
            List<HistoricalModelSample> samples,
            PredictionOutcome outcome
    ) {
        return (int) samples.stream()
                .filter(sample -> sample.actualOutcome() == outcome)
                .count();
    }

    private BigDecimal percentage(double probability) {
        return BigDecimal.valueOf(probability * 100.0)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private LocalDate min(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    private void validatePeriods(
            LocalDate trainingFrom,
            LocalDate trainingTo,
            LocalDate validationFrom,
            LocalDate validationTo
    ) {
        if (trainingFrom == null || trainingTo == null
                || validationFrom == null || validationTo == null
                || trainingFrom.isAfter(trainingTo)
                || validationFrom.isAfter(validationTo)
                || !trainingTo.isBefore(validationFrom)) {
            throw new IllegalArgumentException(
                    "Validation must be strictly later than the training period."
            );
        }
    }

    private record OutcomePrior(double home, double draw, double away) {
        private ModelProbabilities alwaysHome() {
            return ModelProbabilities.of(
                    home, draw, away, PredictionOutcome.HOME_WIN
            );
        }
    }

    private record Period(
            LocalDate trainingFrom,
            LocalDate trainingTo,
            LocalDate evaluationFrom,
            LocalDate evaluationTo
    ) {
    }

    private record SignalRow(
            double absoluteStrength,
            double season,
            double recent5,
            double recent10,
            double run5,
            double run10,
            double venue
    ) {
    }
}
