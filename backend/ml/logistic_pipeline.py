from __future__ import annotations

import csv
import hashlib
import json
import math
import platform
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

import numpy as np
import sklearn
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    accuracy_score,
    confusion_matrix,
    log_loss,
    precision_recall_fscore_support,
)
from sklearn.preprocessing import StandardScaler


FEATURES = [
    "seasonWinRateDiff",
    "recent5WinRateDiff",
    "recent10WinRateDiff",
    "recent5RunDiff",
    "recent10RunDiff",
    "homeAwayWinRateDiff",
]
OUTCOMES = ["AWAY_WIN", "DRAW", "HOME_WIN"]
C_CANDIDATES = [0.01, 0.1, 1.0, 10.0]
CLASS_WEIGHT_CANDIDATES: list[str | None] = [None, "balanced"]
RANDOM_STATE = 20260811


@dataclass(frozen=True)
class Dataset:
    game_ids: np.ndarray
    seasons: np.ndarray
    game_dates: np.ndarray
    features: np.ndarray
    outcomes: np.ndarray
    source_sha256: str

    def subset(self, mask: np.ndarray) -> "Dataset":
        return Dataset(
            game_ids=self.game_ids[mask],
            seasons=self.seasons[mask],
            game_dates=self.game_dates[mask],
            features=self.features[mask],
            outcomes=self.outcomes[mask],
            source_sha256=self.source_sha256,
        )


@dataclass
class Preprocessor:
    imputer: SimpleImputer
    scaler: StandardScaler

    def transform(self, values: np.ndarray) -> np.ndarray:
        return self.scaler.transform(self.imputer.transform(values))


@dataclass
class TrainedLogistic:
    preprocessor: Preprocessor
    model: LogisticRegression

    def predict_proba(self, values: np.ndarray) -> np.ndarray:
        return self.model.predict_proba(self.preprocessor.transform(values))


def load_dataset(path: Path) -> Dataset:
    raw = path.read_bytes()
    rows: list[dict[str, str]] = []
    with path.open("r", encoding="utf-8", newline="") as source:
        reader = csv.DictReader(source)
        missing_columns = set(FEATURES + [
            "gameId", "season", "gameDate", "actualResult"
        ]) - set(reader.fieldnames or [])
        if missing_columns:
            raise ValueError(f"Dataset columns are missing: {sorted(missing_columns)}")
        rows.extend(reader)
    if not rows:
        raise ValueError("Historical ML dataset is empty.")

    features = np.asarray([
        [parse_optional_float(row[name]) for name in FEATURES]
        for row in rows
    ], dtype=float)
    outcomes = np.asarray([row["actualResult"] for row in rows], dtype=str)
    unknown = sorted(set(outcomes) - set(OUTCOMES))
    if unknown:
        raise ValueError(f"Unknown outcomes: {unknown}")
    return Dataset(
        game_ids=np.asarray([int(row["gameId"]) for row in rows]),
        seasons=np.asarray([int(row["season"]) for row in rows]),
        game_dates=np.asarray([row["gameDate"] for row in rows], dtype=str),
        features=features,
        outcomes=outcomes,
        source_sha256=hashlib.sha256(raw).hexdigest(),
    )


def split_by_season(dataset: Dataset) -> tuple[Dataset, Dataset, Dataset]:
    expected = {2023, 2024, 2025, 2026}
    actual = set(int(value) for value in np.unique(dataset.seasons))
    if actual != expected:
        raise ValueError(f"Expected seasons {sorted(expected)}, got {sorted(actual)}")
    train = dataset.subset(np.isin(dataset.seasons, [2023, 2024]))
    validation = dataset.subset(dataset.seasons == 2025)
    test = dataset.subset(dataset.seasons == 2026)
    if not (
        np.max(train.seasons) < np.min(validation.seasons)
        < np.min(test.seasons)
    ):
        raise AssertionError("Season split is not strictly chronological.")
    return train, validation, test


def fit_preprocessor(train_features: np.ndarray) -> Preprocessor:
    imputer = SimpleImputer(strategy="median", keep_empty_features=True)
    imputed = imputer.fit_transform(train_features)
    scaler = StandardScaler()
    scaler.fit(imputed)
    return Preprocessor(imputer=imputer, scaler=scaler)


def fit_logistic(
    train: Dataset,
    c_value: float,
    class_weight: str | None,
) -> TrainedLogistic:
    preprocessor = fit_preprocessor(train.features)
    transformed = preprocessor.transform(train.features)
    model = LogisticRegression(
        C=c_value,
        class_weight=class_weight,
        solver="lbfgs",
        max_iter=5000,
        random_state=RANDOM_STATE,
    )
    model.fit(transformed, train.outcomes)
    if list(model.classes_) != OUTCOMES:
        raise AssertionError(
            f"Unexpected sklearn class order: {list(model.classes_)}"
        )
    return TrainedLogistic(preprocessor=preprocessor, model=model)


def evaluate_probabilities(
    outcomes: np.ndarray,
    probabilities: np.ndarray,
    classes: Iterable[str] = OUTCOMES,
    predicted_outcomes: np.ndarray | None = None,
) -> dict[str, Any]:
    class_list = list(classes)
    predicted = (
        np.asarray(class_list)[np.argmax(probabilities, axis=1)]
        if predicted_outcomes is None
        else predicted_outcomes
    )
    precision, recall, f1, support = precision_recall_fscore_support(
        outcomes,
        predicted,
        labels=class_list,
        zero_division=0,
    )
    encoded = np.zeros_like(probabilities)
    class_index = {name: index for index, name in enumerate(class_list)}
    for row_index, outcome in enumerate(outcomes):
        encoded[row_index, class_index[outcome]] = 1.0
    per_class = {
        name: {
            "precision": float(precision[index]),
            "recall": float(recall[index]),
            "f1": float(f1[index]),
            "support": int(support[index]),
        }
        for index, name in enumerate(class_list)
    }
    return {
        "sampleCount": int(len(outcomes)),
        "accuracy": float(accuracy_score(outcomes, predicted)),
        "logLoss": float(log_loss(outcomes, probabilities, labels=class_list)),
        "brierScore": float(np.mean(np.sum((probabilities - encoded) ** 2, axis=1))),
        "macroF1": float(np.mean(f1)),
        "averageMaxProbability": float(np.mean(np.max(probabilities, axis=1))),
        "perClass": per_class,
        "confusionMatrix": {
            "labels": class_list,
            "rowsActualColumnsPredicted": confusion_matrix(
                outcomes,
                predicted,
                labels=class_list,
            ).astype(int).tolist(),
        },
    }


def candidate_search(
    train: Dataset,
    validation: Dataset,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    results: list[dict[str, Any]] = []
    for c_value in C_CANDIDATES:
        for class_weight in CLASS_WEIGHT_CANDIDATES:
            trained = fit_logistic(train, c_value, class_weight)
            metrics = evaluate_probabilities(
                validation.outcomes,
                trained.predict_proba(validation.features),
                trained.model.classes_,
            )
            results.append({
                "C": c_value,
                "classWeight": class_weight or "none",
                "validation": metrics,
            })
    selected = select_candidate(results)
    return selected, results


def select_candidate(results: list[dict[str, Any]]) -> dict[str, Any]:
    if not results:
        raise ValueError("At least one validation result is required.")
    return min(
        results,
        key=lambda result: (
            result["validation"]["logLoss"],
            result["validation"]["brierScore"],
            -result["validation"]["accuracy"],
        ),
    )


def run_training(
    dataset_path: Path,
    artifact_path: Path,
    report_path: Path,
) -> tuple[dict[str, Any], dict[str, Any]]:
    dataset = load_dataset(dataset_path)
    train, validation, test = split_by_season(dataset)

    # Hyperparameter selection has no access to the 2026 test Dataset.
    selected, candidates = candidate_search(train, validation)
    selected_c = float(selected["C"])
    selected_class_weight = (
        None if selected["classWeight"] == "none" else "balanced"
    )

    final_training_mask = np.isin(dataset.seasons, [2023, 2024, 2025])
    final_training = dataset.subset(final_training_mask)
    final_model = fit_logistic(
        final_training,
        selected_c,
        selected_class_weight,
    )
    test_probabilities = final_model.predict_proba(test.features)
    logistic_test = evaluate_probabilities(
        test.outcomes,
        test_probabilities,
        final_model.model.classes_,
    )
    comparisons = final_test_comparisons(test, final_model, logistic_test)
    artifact = build_artifact(
        dataset,
        train,
        validation,
        final_training,
        test,
        final_model,
        selected,
        test_probabilities,
    )
    report = {
        "modelVersion": "logistic-v1",
        "dataset": dataset_summary(dataset),
        "splits": {
            "train": dataset_summary(train),
            "validation": dataset_summary(validation),
            "finalTest": dataset_summary(test),
            "finalTraining": dataset_summary(final_training),
        },
        "selectionPolicy": "2025 validation only; lexicographic Log Loss, Brier Score, then Accuracy",
        "candidateCount": len(candidates),
        "candidates": candidates,
        "selected": selected,
        "finalTestComparisons": comparisons,
        "logisticFinalTestDetails": logistic_test,
        "leakageGuards": {
            "scalerFitSeasonsDuringSelection": [2023, 2024],
            "validationTransformOnly": True,
            "testUsedDuringSelection": False,
            "finalScalerFitSeasons": [2023, 2024, 2025],
            "actualResultFeature": False,
        },
    }
    write_json(artifact_path, artifact)
    write_json(report_path, report)
    return artifact, report


def build_artifact(
    dataset: Dataset,
    selection_train: Dataset,
    validation: Dataset,
    final_training: Dataset,
    test: Dataset,
    trained: TrainedLogistic,
    selected: dict[str, Any],
    test_probabilities: np.ndarray,
) -> dict[str, Any]:
    sample_indexes = sorted(set([0, len(test.outcomes) // 2, len(test.outcomes) - 1]))
    verification = []
    for index in sample_indexes:
        verification.append({
            "gameId": int(test.game_ids[index]),
            "features": {
                name: optional_number(test.features[index, feature_index])
                for feature_index, name in enumerate(FEATURES)
            },
            "probabilities": {
                class_name: float(test_probabilities[index, class_index])
                for class_index, class_name in enumerate(trained.model.classes_)
            },
        })
    return {
        "modelVersion": "logistic-v1",
        "algorithm": "scikit-learn LogisticRegression (multinomial softmax, lbfgs)",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "pythonVersion": platform.python_version(),
        "scikitLearnVersion": sklearn.__version__,
        "trainingDataSha256": dataset.source_sha256,
        "selection": {
            "trainedFrom": str(min(selection_train.game_dates)),
            "trainedTo": str(max(selection_train.game_dates)),
            "validationFrom": str(min(validation.game_dates)),
            "validationTo": str(max(validation.game_dates)),
            "C": float(selected["C"]),
            "classWeight": selected["classWeight"],
            "objective": "LOG_LOSS_THEN_BRIER_THEN_ACCURACY",
        },
        "finalTraining": {
            "trainedFrom": str(min(final_training.game_dates)),
            "trainedTo": str(max(final_training.game_dates)),
            "seasons": [2023, 2024, 2025],
            "sampleCount": int(len(final_training.outcomes)),
        },
        "untouchedFinalTest": {
            "from": str(min(test.game_dates)),
            "to": str(max(test.game_dates)),
            "season": 2026,
            "sampleCount": int(len(test.outcomes)),
        },
        "features": FEATURES,
        "classes": [str(value) for value in trained.model.classes_],
        "missingValuePolicy": "median fitted on final training seasons only",
        "imputer": {
            "strategy": "median",
            "statistics": trained.preprocessor.imputer.statistics_.astype(float).tolist(),
        },
        "scaler": {
            "mean": trained.preprocessor.scaler.mean_.astype(float).tolist(),
            "scale": trained.preprocessor.scaler.scale_.astype(float).tolist(),
        },
        "coefficients": trained.model.coef_.astype(float).tolist(),
        "intercepts": trained.model.intercept_.astype(float).tolist(),
        "verificationSamples": verification,
    }


def final_test_comparisons(
    test: Dataset,
    logistic: TrainedLogistic,
    logistic_metrics: dict[str, Any],
) -> list[dict[str, Any]]:
    model_probabilities = [
        (
            "always-home",
            always_home_probabilities(len(test.outcomes)),
            np.full(len(test.outcomes), "HOME_WIN"),
        ),
        (
            "season-win-rate",
            season_win_rate_probabilities(test.features),
            season_win_rate_predictions(test.features),
        ),
        ("baseline-v1", baseline_v1_probabilities(test.features), None),
    ]
    results = [
        {
            "model": name,
            **evaluate_probabilities(
                test.outcomes,
                probabilities,
                predicted_outcomes=predicted,
            ),
        }
        for name, probabilities, predicted in model_probabilities
    ]
    results.append({"model": "logistic-v1", **logistic_metrics})
    return results


def always_home_probabilities(sample_count: int) -> np.ndarray:
    # OUTCOMES order: AWAY_WIN, DRAW, HOME_WIN
    return np.tile(np.asarray([0.47, 0.03, 0.50]), (sample_count, 1))


def season_win_rate_probabilities(features: np.ndarray) -> np.ndarray:
    probabilities = np.empty((len(features), 3), dtype=float)
    for index, difference in enumerate(features[:, 0]):
        if np.isnan(difference):
            probabilities[index] = [0.47, 0.03, 0.50]
            continue
        home_share = 1.0 / (1.0 + math.exp(-4.0 * float(difference)))
        probabilities[index] = [
            0.97 * (1.0 - home_share),
            0.03,
            0.97 * home_share,
        ]
    return probabilities


def season_win_rate_predictions(features: np.ndarray) -> np.ndarray:
    return np.asarray([
        "HOME_WIN" if np.isnan(difference) or difference >= 0.0
        else "AWAY_WIN"
        for difference in features[:, 0]
    ])


def baseline_v1_probabilities(features: np.ndarray) -> np.ndarray:
    weights = np.asarray([0.18, 0.10, 0.07, 0.12, 0.08, 0.10])
    scales = np.asarray([0.25, 0.25, 0.25, 4.0, 4.0, 0.25])
    probabilities = np.empty((len(features), 3), dtype=float)
    for row_index, row in enumerate(features):
        present = ~np.isnan(row)
        available_weight = float(np.sum(weights[present]))
        if available_weight == 0.0:
            normalized_strength = 0.0
        else:
            normalized = np.clip(row[present] / scales[present], -1.0, 1.0)
            normalized_strength = float(
                np.sum(weights[present] * normalized) / available_weight
            )
        reliability = min(1.0, available_weight / 0.50)
        strength = float(np.clip(normalized_strength * reliability + 0.04, -1.0, 1.0))
        draw = 0.05 + (0.12 - 0.05) * (1.0 - abs(strength))
        home_share = 1.0 / (1.0 + math.exp(-1.8 * strength))
        probabilities[row_index] = [
            (1.0 - draw) * (1.0 - home_share),
            draw,
            (1.0 - draw) * home_share,
        ]
    return probabilities


def artifact_predict(
    artifact: dict[str, Any],
    raw_features: np.ndarray,
) -> np.ndarray:
    statistics = np.asarray(artifact["imputer"]["statistics"], dtype=float)
    means = np.asarray(artifact["scaler"]["mean"], dtype=float)
    scales = np.asarray(artifact["scaler"]["scale"], dtype=float)
    coefficients = np.asarray(artifact["coefficients"], dtype=float)
    intercepts = np.asarray(artifact["intercepts"], dtype=float)
    imputed = np.where(np.isnan(raw_features), statistics, raw_features)
    standardized = (imputed - means) / scales
    scores = standardized @ coefficients.T + intercepts
    scores -= np.max(scores, axis=1, keepdims=True)
    exponentials = np.exp(scores)
    return exponentials / np.sum(exponentials, axis=1, keepdims=True)


def dataset_summary(dataset: Dataset) -> dict[str, Any]:
    distribution = {
        outcome: int(np.sum(dataset.outcomes == outcome))
        for outcome in OUTCOMES
    }
    missing = {
        name: int(np.sum(np.isnan(dataset.features[:, index])))
        for index, name in enumerate(FEATURES)
    }
    return {
        "sampleCount": int(len(dataset.outcomes)),
        "from": str(min(dataset.game_dates)),
        "to": str(max(dataset.game_dates)),
        "seasons": sorted(int(value) for value in np.unique(dataset.seasons)),
        "classDistribution": distribution,
        "missingFeatureCounts": missing,
    }


def parse_optional_float(value: str) -> float:
    return np.nan if value is None or value.strip() == "" else float(value)


def optional_number(value: float) -> float | None:
    return None if np.isnan(value) else float(value)


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n",
        encoding="utf-8",
    )
