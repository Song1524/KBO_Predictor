from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

import numpy as np

ML_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ML_ROOT))

from logistic_pipeline import FEATURES, artifact_predict  # noqa: E402


class ArtifactInferenceTest(unittest.TestCase):
    def test_exported_softmax_matches_python_verification_samples(self) -> None:
        artifact = json.loads(
            (ML_ROOT / "artifacts" / "logistic-v1.json")
            .read_text(encoding="utf-8")
        )
        rows = np.asarray([
            [
                np.nan if sample["features"][name] is None
                else sample["features"][name]
                for name in FEATURES
            ]
            for sample in artifact["verificationSamples"]
        ], dtype=float)

        actual = artifact_predict(artifact, rows)
        expected = np.asarray([
            [sample["probabilities"][name] for name in artifact["classes"]]
            for sample in artifact["verificationSamples"]
        ])

        np.testing.assert_allclose(actual, expected, rtol=0.0, atol=1.0e-12)


if __name__ == "__main__":
    unittest.main()
