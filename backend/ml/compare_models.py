from __future__ import annotations

import argparse
import csv
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

import numpy as np
from sklearn.metrics import average_precision_score, roc_auc_score

from logistic_pipeline import (
    FEATURES,
    OUTCOMES,
    artifact_predict,
    baseline_v1_probabilities,
    evaluate_probabilities,
    load_dataset,
)


ML_ROOT = Path(__file__).resolve().parent
DEFAULT_DATASET = ML_ROOT / "data" / "historical-2023-2026.csv"
DEFAULT_ARTIFACT = ML_ROOT / "artifacts" / "logistic-v1.json"
BOOTSTRAP_SEED = 20260814


@dataclass(frozen=True)
class DatasetMetadata:
    coverage: np.ndarray
    available_feature_count: np.ndarray

    def subset(self, mask: np.ndarray) -> "DatasetMetadata":
        return DatasetMetadata(
            coverage=self.coverage[mask],
            available_feature_count=self.available_feature_count[mask],
        )


def main() -> None:
    parser = argparse.ArgumentParser(
        description=(
            "Compare baseline-v1 and frozen logistic-v1 on identical "
            "leakage-safe historical feature rows."
        )
    )
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--artifact", type=Path, default=DEFAULT_ARTIFACT)
    parser.add_argument(
        "--scope",
        choices=("final-test", "all"),
        default="final-test",
        help=(
            "final-test is the only unbiased model comparison. all also emits "
            "descriptive train/validation season rows."
        ),
    )
    parser.add_argument(
        "--bootstrap-samples",
        type=int,
        default=10_000,
        help="Paired bootstrap repetitions for metric-difference intervals.",
    )
    args = parser.parse_args()
    report = compare(
        args.dataset,
        args.artifact,
        scope=args.scope,
        bootstrap_samples=args.bootstrap_samples,
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))


def compare(
    dataset_path: Path,
    artifact_path: Path,
    *,
    scope: str = "final-test",
    bootstrap_samples: int = 10_000,
) -> dict[str, Any]:
    if bootstrap_samples < 0:
        raise ValueError("bootstrap_samples must be non-negative.")
    artifact = json.loads(artifact_path.read_text(encoding="utf-8"))
    full_dataset = load_dataset(dataset_path)
    full_metadata = load_metadata(dataset_path)
    verify_integrity(full_dataset, full_metadata, artifact, dataset_path)

    final_test = artifact["untouchedFinalTest"]
    final_mask = (
        (full_dataset.seasons == int(final_test["season"]))
        & (full_dataset.game_dates >= str(final_test["from"]))
        & (full_dataset.game_dates <= str(final_test["to"]))
    )
    if int(np.sum(final_mask)) != int(final_test["sampleCount"]):
        raise ValueError(
            "Dataset final-test rows do not match artifact untouchedFinalTest metadata."
        )
    selected_mask = (
        final_mask if scope == "final-test"
        else np.ones(len(full_dataset.outcomes), dtype=bool)
    )
    dataset = full_dataset.subset(selected_mask)
    metadata = full_metadata.subset(selected_mask)

    baseline = baseline_v1_probabilities(dataset.features)
    logistic = artifact_predict(artifact, dataset.features)
    models = {
        "baseline-v1": baseline,
        "logistic-v1": logistic,
    }
    result = {
        "evaluationPolicy": {
            "scope": scope,
            "unbiasedDecisionScope": "2026_UNTOUCHED_FINAL_TEST",
            "sourceType": "HISTORICAL_RECONSTRUCTION_NOT_OPERATIONAL_HISTORY",
            "sameRowsAndFeaturesForBothModels": True,
            "probabilities": "UNROUNDED_ENGINE_OUTPUT",
            "coverageDefinition": "available logistic core features / 6",
        },
        "integrity": integrity_report(
            full_dataset, artifact, dataset_path, final_mask
        ),
        "evaluationDataset": dataset_summary(dataset, metadata),
        "overall": {
            name: model_report(dataset.outcomes, probabilities)
            for name, probabilities in models.items()
        },
        "pairedComparison": paired_comparison(
            dataset.outcomes,
            baseline,
            logistic,
            bootstrap_samples,
        ),
        "draw": {
            "actualRate": number(np.mean(dataset.outcomes == "DRAW")),
            **{
                name: draw_report(dataset.outcomes, probabilities)
                for name, probabilities in models.items()
            },
        },
        "homeAwayBias": {
            name: bias_report(dataset.outcomes, probabilities)
            for name, probabilities in models.items()
        },
        "calibration": {
            name: calibration_report(dataset.outcomes, probabilities)
            for name, probabilities in models.items()
        },
        "bySeason": season_reports(full_dataset, full_metadata, artifact),
        "byFeatureCoverage": coverage_reports(
            dataset, metadata, artifact
        ),
        "examples": examples(dataset, metadata, baseline, logistic),
        "limitations": [
            "The CSV was exported from HISTORICAL_INTERNAL_GAMES snapshots; it is not evidence that an operational prediction existed before each game.",
            "Historical rows reconstruct features from strictly earlier game dates and do not contain batting average, team ERA, starter ERA, starter WHIP, team names, or pitcher names.",
            "Starting-pitcher impact and low-coverage behavior cannot be estimated from this dataset.",
            "Only the 2026 untouched final test is valid for a promotion decision; 2023-2025 season metrics are descriptive because those seasons participated in training or selection.",
        ],
    }
    return result


def load_metadata(path: Path) -> DatasetMetadata:
    coverage: list[float] = []
    available: list[int] = []
    with path.open("r", encoding="utf-8", newline="") as source:
        for row in csv.DictReader(source):
            coverage.append(float(row["featureCoverage"]) / 100.0)
            available.append(int(row["availableFeatureCount"]))
    return DatasetMetadata(
        coverage=np.asarray(coverage, dtype=float),
        available_feature_count=np.asarray(available, dtype=int),
    )


def verify_integrity(dataset, metadata, artifact, dataset_path: Path) -> None:
    if len(dataset.outcomes) != len(metadata.coverage):
        raise ValueError("Dataset and metadata row counts differ.")
    digest = hashlib.sha256(dataset_path.read_bytes()).hexdigest()
    if digest.lower() != str(artifact["trainingDataSha256"]).lower():
        raise ValueError("Dataset SHA-256 does not match the frozen artifact.")
    if list(artifact["features"]) != FEATURES:
        raise ValueError("Artifact feature order differs from training code.")
    if list(artifact["classes"]) != OUTCOMES:
        raise ValueError("Artifact class order differs from training code.")
    if len(set(int(value) for value in dataset.game_ids)) != len(dataset.game_ids):
        raise ValueError("Dataset contains duplicate game IDs.")
    if not np.all(metadata.available_feature_count >= 0):
        raise ValueError("availableFeatureCount cannot be negative.")
    if not np.all((metadata.coverage >= 0.0) & (metadata.coverage <= 1.0)):
        raise ValueError("featureCoverage must be between 0 and 1.")


def integrity_report(dataset, artifact, dataset_path: Path, final_mask) -> dict[str, Any]:
    final_training = artifact["finalTraining"]
    final_test = artifact["untouchedFinalTest"]
    return {
        "datasetSha256": hashlib.sha256(dataset_path.read_bytes()).hexdigest(),
        "artifactTrainingDataSha256": artifact["trainingDataSha256"],
        "datasetHashMatchesArtifact": True,
        "duplicateGameIdCount": int(
            len(dataset.game_ids) - len(set(int(value) for value in dataset.game_ids))
        ),
        "artifactFinalTrainingTo": final_training["trainedTo"],
        "artifactFinalTestFrom": final_test["from"],
        "strictChronologicalTrainTestBoundary": (
            str(final_training["trainedTo"]) < str(final_test["from"])
        ),
        "finalTestRowCount": int(np.sum(final_mask)),
        "actualResultUsedAsFeature": False,
    }


def dataset_summary(dataset, metadata: DatasetMetadata) -> dict[str, Any]:
    return {
        "sampleCount": int(len(dataset.outcomes)),
        "from": str(min(dataset.game_dates)),
        "to": str(max(dataset.game_dates)),
        "seasons": sorted(int(value) for value in np.unique(dataset.seasons)),
        "classDistribution": {
            outcome: int(np.sum(dataset.outcomes == outcome))
            for outcome in OUTCOMES
        },
        "coverageDistribution": {
            percent_label(value): int(np.sum(np.isclose(metadata.coverage, value)))
            for value in sorted(float(item) for item in np.unique(metadata.coverage))
        },
    }


def model_report(outcomes: np.ndarray, probabilities: np.ndarray) -> dict[str, Any]:
    metrics = evaluate_probabilities(outcomes, probabilities)
    return {
        "sampleCount": metrics["sampleCount"],
        "accuracy": number(metrics["accuracy"]),
        "logLoss": number(metrics["logLoss"]),
        "brierScore": number(metrics["brierScore"]),
        "macroF1": number(metrics["macroF1"]),
        "averageMaxProbability": number(metrics["averageMaxProbability"]),
        "perClass": rounded_tree(metrics["perClass"]),
        "confusionMatrix": metrics["confusionMatrix"],
    }


def paired_comparison(
    outcomes: np.ndarray,
    baseline: np.ndarray,
    logistic: np.ndarray,
    bootstrap_samples: int,
) -> dict[str, Any]:
    encoded = one_hot(outcomes)
    actual_indexes = np.asarray([OUTCOMES.index(value) for value in outcomes])
    row_indexes = np.arange(len(outcomes))
    loss_rows = {
        "accuracy": (
            np.argmax(logistic, axis=1) == actual_indexes
        ).astype(float) - (
            np.argmax(baseline, axis=1) == actual_indexes
        ).astype(float),
        "logLoss": -np.log(np.maximum(logistic[row_indexes, actual_indexes], 1e-15))
        + np.log(np.maximum(baseline[row_indexes, actual_indexes], 1e-15)),
        "brierScore": np.sum((logistic - encoded) ** 2, axis=1)
        - np.sum((baseline - encoded) ** 2, axis=1),
    }
    rng = np.random.default_rng(BOOTSTRAP_SEED)
    return {
        metric: {
            "logisticMinusBaseline": number(float(np.mean(rows))),
            "pairedBootstrap95PercentCI": bootstrap_interval(
                rows, rng, bootstrap_samples
            ),
            "direction": "HIGHER_IS_BETTER" if metric == "accuracy"
            else "LOWER_IS_BETTER",
        }
        for metric, rows in loss_rows.items()
    }


def bootstrap_interval(
    rows: np.ndarray,
    rng: np.random.Generator,
    repetitions: int,
) -> list[float] | None:
    if repetitions == 0 or len(rows) == 0:
        return None
    means = np.empty(repetitions, dtype=float)
    for start in range(0, repetitions, 1_000):
        size = min(1_000, repetitions - start)
        indexes = rng.integers(0, len(rows), size=(size, len(rows)))
        means[start:start + size] = np.mean(rows[indexes], axis=1)
    return [
        number(float(np.quantile(means, 0.025))),
        number(float(np.quantile(means, 0.975))),
    ]


def draw_report(outcomes: np.ndarray, probabilities: np.ndarray) -> dict[str, Any]:
    draw_index = OUTCOMES.index("DRAW")
    values = probabilities[:, draw_index]
    actual_draw = outcomes == "DRAW"
    predicted_draw = np.argmax(probabilities, axis=1) == draw_index
    true_positive = int(np.sum(actual_draw & predicted_draw))
    predicted_count = int(np.sum(predicted_draw))
    actual_count = int(np.sum(actual_draw))
    return {
        "averageProbability": number(float(np.mean(values))),
        "averageOnActualDraw": optional_average(values[actual_draw]),
        "averageOnNonDraw": optional_average(values[~actual_draw]),
        "standardDeviation": number(float(np.std(values))),
        "minimum": number(float(np.min(values))),
        "percentile25": number(float(np.quantile(values, 0.25))),
        "median": number(float(np.median(values))),
        "percentile75": number(float(np.quantile(values, 0.75))),
        "maximum": number(float(np.max(values))),
        "top1PredictedDrawCount": predicted_count,
        "top1Precision": None if predicted_count == 0
        else number(true_positive / predicted_count),
        "top1Recall": None if actual_count == 0
        else number(true_positive / actual_count),
        "drawVsRestRocAuc": None if actual_count == 0 or actual_count == len(outcomes)
        else number(float(roc_auc_score(actual_draw, values))),
        "drawVsRestAveragePrecision": None if actual_count == 0
        else number(float(average_precision_score(actual_draw, values))),
    }


def bias_report(outcomes: np.ndarray, probabilities: np.ndarray) -> dict[str, Any]:
    report: dict[str, Any] = {}
    for outcome in ("HOME_WIN", "AWAY_WIN"):
        index = OUTCOMES.index(outcome)
        predicted = float(np.mean(probabilities[:, index]))
        actual = float(np.mean(outcomes == outcome))
        report[outcome] = {
            "averageProbability": number(predicted),
            "actualRate": number(actual),
            "probabilityMinusActual": number(predicted - actual),
        }
    return report


def calibration_report(
    outcomes: np.ndarray,
    probabilities: np.ndarray,
) -> dict[str, Any]:
    return {
        outcome: calibration_bins(
            probabilities[:, index], outcomes == outcome
        )
        for index, outcome in enumerate(OUTCOMES)
    }


def calibration_bins(
    probabilities: np.ndarray,
    actual: np.ndarray,
) -> dict[str, Any]:
    bins = []
    weighted_gap = 0.0
    for index in range(10):
        lower = index / 10.0
        upper = (index + 1) / 10.0
        mask = (
            (probabilities >= lower)
            & (probabilities <= upper if index == 9 else probabilities < upper)
        )
        count = int(np.sum(mask))
        average_probability = None if count == 0 else float(np.mean(probabilities[mask]))
        actual_rate = None if count == 0 else float(np.mean(actual[mask]))
        if count:
            weighted_gap += count * abs(average_probability - actual_rate)
        bins.append({
            "range": f"{index * 10}-{(index + 1) * 10}%",
            "count": count,
            "averageProbability": optional_number(average_probability),
            "actualRate": optional_number(actual_rate),
            "gap": None if count == 0 else number(
                average_probability - actual_rate
            ),
        })
    return {
        "expectedCalibrationError": number(
            weighted_gap / len(probabilities) if len(probabilities) else 0.0
        ),
        "bins": bins,
    }


def season_reports(full_dataset, full_metadata, artifact) -> list[dict[str, Any]]:
    reports = []
    logistic_full = artifact_predict(artifact, full_dataset.features)
    baseline_full = baseline_v1_probabilities(full_dataset.features)
    final_test_season = int(artifact["untouchedFinalTest"]["season"])
    for season in sorted(int(value) for value in np.unique(full_dataset.seasons)):
        mask = full_dataset.seasons == season
        subset = full_dataset.subset(mask)
        role = (
            "UNTOUCHED_FINAL_TEST" if season == final_test_season
            else "TRAIN_OR_SELECTION_DESCRIPTIVE_ONLY"
        )
        reports.append({
            "season": season,
            "role": role,
            "sampleCount": int(np.sum(mask)),
            "actualDrawRate": number(float(np.mean(subset.outcomes == "DRAW"))),
            "featureCoverage": {
                "average": number(float(np.mean(full_metadata.coverage[mask]))),
                "minimum": number(float(np.min(full_metadata.coverage[mask]))),
            },
            "baseline-v1": compact_model_report(
                subset.outcomes, baseline_full[mask]
            ),
            "logistic-v1": compact_model_report(
                subset.outcomes, logistic_full[mask]
            ),
        })
    return reports


def compact_model_report(
    outcomes: np.ndarray,
    probabilities: np.ndarray,
) -> dict[str, Any]:
    metrics = model_report(outcomes, probabilities)
    return {
        "accuracy": metrics["accuracy"],
        "logLoss": metrics["logLoss"],
        "brierScore": metrics["brierScore"],
        "averageDrawProbability": number(
            float(np.mean(probabilities[:, OUTCOMES.index("DRAW")]))
        ),
    }


def coverage_reports(dataset, metadata, artifact) -> list[dict[str, Any]]:
    baseline = baseline_v1_probabilities(dataset.features)
    logistic = artifact_predict(artifact, dataset.features)
    groups = (
        ("coverage < 0.3", metadata.coverage < 0.3),
        (
            "0.3 <= coverage < 0.6",
            (metadata.coverage >= 0.3) & (metadata.coverage < 0.6),
        ),
        (
            "0.6 <= coverage < 0.8",
            (metadata.coverage >= 0.6) & (metadata.coverage < 0.8),
        ),
        ("0.8 <= coverage < 1.0", (metadata.coverage >= 0.8) & (metadata.coverage < 1.0)),
        ("coverage = 1.0", np.isclose(metadata.coverage, 1.0)),
    )
    reports = []
    for label, mask in groups:
        count = int(np.sum(mask))
        reports.append({
            "range": label,
            "sampleCount": count,
            "baseline-v1": None if count == 0 else compact_model_report(
                dataset.outcomes[mask], baseline[mask]
            ),
            "logistic-v1": None if count == 0 else compact_model_report(
                dataset.outcomes[mask], logistic[mask]
            ),
        })
    return reports


def examples(
    dataset,
    metadata: DatasetMetadata,
    baseline: np.ndarray,
    logistic: np.ndarray,
) -> list[dict[str, Any]]:
    actual_indexes = np.asarray([OUTCOMES.index(value) for value in dataset.outcomes])
    baseline_predicted = np.argmax(baseline, axis=1)
    logistic_predicted = np.argmax(logistic, axis=1)
    distance = np.sum(np.abs(baseline - logistic), axis=1)
    definitions = (
        (
            "MODELS_MOST_SIMILAR",
            baseline_predicted == logistic_predicted,
            False,
        ),
        ("MODELS_MOST_DIFFERENT", np.ones(len(distance), dtype=bool), True),
        (
            "BASELINE_ONLY_CORRECT",
            (baseline_predicted == actual_indexes)
            & (logistic_predicted != actual_indexes),
            True,
        ),
        (
            "LOGISTIC_ONLY_CORRECT",
            (logistic_predicted == actual_indexes)
            & (baseline_predicted != actual_indexes),
            True,
        ),
        ("ACTUAL_DRAW", dataset.outcomes == "DRAW", True),
    )
    selected = []
    for category, mask, descending in definitions:
        candidates = np.flatnonzero(mask)
        if len(candidates) == 0:
            continue
        values = distance[candidates]
        index = int(candidates[np.argmax(values) if descending else np.argmin(values)])
        selected.append(example_row(
            category, index, dataset, metadata, baseline, logistic, distance
        ))
    return selected


def example_row(
    category: str,
    index: int,
    dataset,
    metadata: DatasetMetadata,
    baseline: np.ndarray,
    logistic: np.ndarray,
    distance: np.ndarray,
) -> dict[str, Any]:
    return {
        "category": category,
        "gameId": int(dataset.game_ids[index]),
        "gameDate": str(dataset.game_dates[index]),
        "actualResult": str(dataset.outcomes[index]),
        "probabilityL1Distance": number(float(distance[index])),
        "baseline-v1": probability_row(baseline[index]),
        "logistic-v1": probability_row(logistic[index]),
        "features": {
            name: optional_number(value)
            for name, value in zip(FEATURES, dataset.features[index])
        },
        "availableFeatureCount": int(metadata.available_feature_count[index]),
        "featureCoverage": number(float(metadata.coverage[index])),
    }


def probability_row(probabilities: np.ndarray) -> dict[str, Any]:
    predicted_index = int(np.argmax(probabilities))
    return {
        "predictedOutcome": OUTCOMES[predicted_index],
        **{
            outcome: number(float(probabilities[index]))
            for index, outcome in enumerate(OUTCOMES)
        },
    }


def one_hot(outcomes: Iterable[str]) -> np.ndarray:
    values = list(outcomes)
    encoded = np.zeros((len(values), len(OUTCOMES)), dtype=float)
    for row, outcome in enumerate(values):
        encoded[row, OUTCOMES.index(str(outcome))] = 1.0
    return encoded


def optional_average(values: np.ndarray) -> float | None:
    return None if len(values) == 0 else number(float(np.mean(values)))


def optional_number(value: float | np.floating[Any] | None) -> float | None:
    if value is None or np.isnan(value):
        return None
    return number(float(value))


def number(value: float) -> float:
    return round(value, 6)


def rounded_tree(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: rounded_tree(item) for key, item in value.items()}
    if isinstance(value, list):
        return [rounded_tree(item) for item in value]
    if isinstance(value, float):
        return number(value)
    return value


def percent_label(value: float) -> str:
    return f"{value * 100.0:.2f}%"


if __name__ == "__main__":
    main()
