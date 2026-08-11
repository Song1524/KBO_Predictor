from __future__ import annotations

import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from logistic_pipeline import (  # noqa: E402
    FEATURES,
    Dataset,
    fit_preprocessor,
    select_candidate,
    split_by_season,
)


class LogisticPipelineTest(unittest.TestCase):
    def test_split_is_chronological_and_2026_is_test_only(self) -> None:
        dataset = self.dataset()

        train, validation, test = split_by_season(dataset)

        self.assertEqual({2023, 2024}, set(train.seasons))
        self.assertEqual({2025}, set(validation.seasons))
        self.assertEqual({2026}, set(test.seasons))
        self.assertLess(max(train.seasons), min(validation.seasons))
        self.assertLess(max(validation.seasons), min(test.seasons))

    def test_imputer_and_scaler_fit_train_only(self) -> None:
        train = np.asarray([
            [1.0, np.nan, 2.0, 3.0, 4.0, 5.0],
            [3.0, 4.0, 4.0, 5.0, 6.0, 7.0],
        ])
        validation_with_extreme_values = np.asarray([
            [1000.0, 1000.0, 1000.0, 1000.0, 1000.0, 1000.0]
        ])

        preprocessor = fit_preprocessor(train)
        preprocessor.transform(validation_with_extreme_values)

        np.testing.assert_allclose(
            preprocessor.imputer.statistics_,
            [2.0, 4.0, 3.0, 4.0, 5.0, 6.0],
        )
        np.testing.assert_allclose(
            preprocessor.scaler.mean_,
            [2.0, 4.0, 3.0, 4.0, 5.0, 6.0],
        )

    def test_selection_uses_only_validation_metric_fields(self) -> None:
        candidates = [
            {
                "C": 0.1,
                "classWeight": "none",
                "validation": {
                    "logLoss": 0.8,
                    "brierScore": 0.5,
                    "accuracy": 0.5,
                },
                "test": {"logLoss": 0.1},
            },
            {
                "C": 1.0,
                "classWeight": "none",
                "validation": {
                    "logLoss": 0.7,
                    "brierScore": 0.6,
                    "accuracy": 0.4,
                },
                "test": {"logLoss": 9.0},
            },
        ]

        selected = select_candidate(candidates)

        self.assertEqual(1.0, selected["C"])

    def test_actual_result_is_not_a_feature(self) -> None:
        self.assertNotIn("actualResult", FEATURES)
        self.assertEqual(6, len(FEATURES))

    def dataset(self) -> Dataset:
        seasons = np.asarray([2023, 2024, 2025, 2026])
        return Dataset(
            game_ids=np.arange(1, 5),
            seasons=seasons,
            game_dates=np.asarray([
                "2023-04-01",
                "2024-04-01",
                "2025-04-01",
                "2026-04-01",
            ]),
            features=np.zeros((4, 6)),
            outcomes=np.asarray([
                "HOME_WIN", "AWAY_WIN", "DRAW", "HOME_WIN"
            ]),
            source_sha256="test",
        )


if __name__ == "__main__":
    unittest.main()
