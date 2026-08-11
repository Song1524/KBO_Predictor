# logistic-v1 offline training

`logistic-v1` is trained offline with scikit-learn. Spring never starts or
calls Python; it loads the exported JSON coefficients and performs the same
standardization and softmax calculation in Java.

## Dataset export

Export the leakage-safe dataset through the authenticated admin endpoint:

```text
GET /api/admin/predictions/dataset/csv?from=2023-03-01&to=2026-08-01
```

Save it as `ml/data/historical-2023-2026.csv`. Derived CSV files and `.venv`
are ignored by Git.

## Reproduce training

```powershell
py -3.12 -m venv ml/.venv
ml/.venv/Scripts/python.exe -m pip install -r ml/requirements.txt
ml/.venv/Scripts/python.exe ml/train_logistic.py `
  --dataset ml/data/historical-2023-2026.csv `
  --artifact ml/artifacts/logistic-v1.json `
  --report ml/artifacts/logistic-v1-report.json
```

The script uses 2023-2024 for training, 2025 only for hyperparameter
selection, then refits the frozen candidate on 2023-2025 and evaluates 2026
once as the final test. Missing values use medians fitted on the applicable
training split before `StandardScaler` is fitted.
