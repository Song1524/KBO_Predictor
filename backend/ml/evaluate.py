from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from logistic_pipeline import artifact_predict, evaluate_probabilities, load_dataset


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Re-evaluate a frozen logistic-v1 JSON artifact on the 2026 final test."
    )
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    args = parser.parse_args()

    artifact = json.loads(args.artifact.read_text(encoding="utf-8"))
    dataset = load_dataset(args.dataset)
    test = dataset.subset(dataset.seasons == 2026)
    probabilities = artifact_predict(artifact, test.features)
    metrics = evaluate_probabilities(
        test.outcomes,
        probabilities,
        artifact["classes"],
    )
    print(json.dumps(metrics, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
