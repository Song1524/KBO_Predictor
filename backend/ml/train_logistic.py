from __future__ import annotations

import argparse
from pathlib import Path

from logistic_pipeline import run_training


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Train logistic-v1 without exposing the 2026 final test to model selection."
    )
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument(
        "--artifact",
        type=Path,
        default=Path("ml/artifacts/logistic-v1.json"),
    )
    parser.add_argument(
        "--report",
        type=Path,
        default=Path("ml/artifacts/logistic-v1-report.json"),
    )
    args = parser.parse_args()
    artifact, report = run_training(args.dataset, args.artifact, args.report)
    selected = report["selected"]
    logistic_test = report["logisticFinalTestDetails"]
    print(
        f"selected C={selected['C']} class_weight={selected['classWeight']} "
        f"validation_log_loss={selected['validation']['logLoss']:.6f}"
    )
    print(
        f"final artifact={args.artifact} classes={artifact['classes']} "
        f"test_accuracy={logistic_test['accuracy']:.6f} "
        f"test_log_loss={logistic_test['logLoss']:.6f}"
    )


if __name__ == "__main__":
    main()
