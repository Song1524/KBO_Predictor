from __future__ import annotations

import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from compare_models import (  # noqa: E402
    bootstrap_interval,
    calibration_bins,
    draw_report,
)


class CompareModelsTest(unittest.TestCase):
    def test_calibration_includes_probability_one_in_last_bin(self) -> None:
        report = calibration_bins(
            np.asarray([0.0, 0.1, 0.999, 1.0]),
            np.asarray([False, False, True, True]),
        )

        self.assertEqual(1, report["bins"][0]["count"])
        self.assertEqual(1, report["bins"][1]["count"])
        self.assertEqual(2, report["bins"][9]["count"])
        self.assertEqual(4, sum(row["count"] for row in report["bins"]))

    def test_draw_report_does_not_invent_precision_without_draw_predictions(self) -> None:
        outcomes = np.asarray(["DRAW", "HOME_WIN"])
        probabilities = np.asarray([
            [0.4, 0.2, 0.4],
            [0.2, 0.1, 0.7],
        ])

        report = draw_report(outcomes, probabilities)

        self.assertEqual(0, report["top1PredictedDrawCount"])
        self.assertIsNone(report["top1Precision"])
        self.assertEqual(0.0, report["top1Recall"])
        self.assertEqual(1.0, report["drawVsRestRocAuc"])

    def test_zero_bootstrap_samples_returns_no_interval(self) -> None:
        interval = bootstrap_interval(
            np.asarray([1.0, -1.0]),
            np.random.default_rng(1),
            0,
        )

        self.assertIsNone(interval)


if __name__ == "__main__":
    unittest.main()
