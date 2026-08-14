# baseline-v1 vs logistic-v1 모델 평가

평가일: 2026-08-14  
운영 모델 변경: 없음 (`app.prediction.active-model: baseline-v1` 유지)

## 결론 요약

판정은 **B. logistic-v1 운영 승격 후보**다. 즉시 승격을 뜻하지는 않는다.

2026년 미사용 최종 테스트 489경기에서 logistic-v1은 baseline-v1보다
Log Loss와 Brier Score가 모두 낮았고, paired bootstrap 95% 신뢰구간도
0 아래였다. 10% 구간 ECE도 HOME/DRAW/AWAY 모두 크게 낮았다. 이는 단순히
DRAW 평균값이 현실적으로 보인다는 이유가 아니라 전체 확률 품질과 calibration이
함께 개선됐다는 근거다.

다만 이 평가는 과거 경기 결과에서 경기일 이전 기록만 사용해 재구성한
`HISTORICAL_INTERNAL_GAMES` backtest다. 실제 운영 시각에 저장된
baseline/shadow `FINAL` history의 표본 수는 현재 실행 환경에 DB 인증정보가 없어
확인하지 못했다. 또한 historical dataset에는 팀 타율, 팀 ERA, 선발 ERA/WHIP가
없으므로 선발 데이터가 있는 실운영 조건까지 승격 근거가 확장되지는 않는다.

특히 logistic-v1의 DRAW 확률은 전체 평균 calibration은 좋지만 경기별 구분력은
없다. 실제 DRAW 경기의 평균 DRAW 확률이 비-DRAW 경기보다 오히려 조금 낮고,
DRAW-vs-rest ROC-AUC는 0.499다. 따라서 logistic-v1을 좋은 “DRAW 탐지 모델”로
해석하면 안 된다.

## 현재 두 모델 구조

### baseline-v1

- 현재 운영 모델이다. `ActivePredictionEngine`은 설정값
  `app.prediction.active-model: baseline-v1`을 읽는다.
- 수동 가중치 10개를 사용한다: 시즌 승률, 최근 5/10경기 승률, 최근 5/10경기
  득실, 홈/원정 승률, 팀 타율, 팀 ERA, 선발 ERA, 선발 WHIP.
- 사용 가능한 feature 가중치만 다시 정규화하며, coverage가 낮으면 strength를
  축소한다.
- 명시적 home advantage는 `+0.04`다.
- DRAW는 strength의 절댓값에 따라 5~12% 범위로 강제된다.
- HOME/AWAY share는 수동 strength에 logistic scale 1.8을 적용한다.

### logistic-v1

- 운영과 동일한 `PredictionFeatures`를 받지만 실제 artifact 입력은 다음 6개
  차이 feature뿐이다.
  - `seasonWinRateDiff`
  - `recent5WinRateDiff`
  - `recent10WinRateDiff`
  - `recent5RunDiff`
  - `recent10RunDiff`
  - `homeAwayWinRateDiff`
- 누락값은 최종 train 중앙값으로 대체하고 `StandardScaler`로 변환한 뒤,
  3-class multinomial softmax를 계산한다.
- scikit-learn을 런타임에서 실행하지 않는다. Java가 JSON artifact의 scaler,
  coefficient, intercept를 그대로 사용한다.
- 현재는 shadow history만 생성한다. 운영 예측이 먼저 성공하면 동일한
  `PredictionFeatures`, 동일한 `prediction_feature_snapshots.id`, 동일한
  `generatedAt`으로 logistic history를 저장한다.

### 생성·저장·비교 흐름

1. `SystemPredictionGenerationService`가 경기 전 feature를 구성한다.
2. `ActivePredictionEngine`이 baseline-v1 운영 예측을 만든다.
3. `SystemPredictionWriter`가 `system_predictions`의 현재값과
   `prediction_feature_snapshots`, operational history를 저장한다.
4. `ShadowPredictionService`가 같은 in-memory feature와 방금 저장한 snapshot ID로
   logistic-v1을 계산하고 artifact SHA-256과 함께 shadow history를 저장한다.
5. 마감 뒤 `SystemPredictionFinalizationService`는 마지막 operational/shadow
   history를 `FINAL`로 복사한다. 마감 뒤 feature를 다시 계산하지 않는다.
6. 실제 결과는 `games.result`의 `HOME_WIN`, `DRAW`, `AWAY_WIN`과 연결된다.
7. `ShadowEvaluationService`는 두 모델 모두 `FINAL`, 경기는 `FINISHED`, 결과 존재,
   snapshot ID 동일, logistic artifact hash 동일인 교집합만 평가한다.

`system_predictions`는 경기별 현재 운영 예측 하나를 보유한다.
`system_prediction_histories`는 모델·source·stage별 불변 평가 이력이고,
`prediction_feature_snapshots`는 예측 당시 입력값이다.

관리자 API/UI에는 다음 경로가 있다.

- `GET /api/admin/predictions/shadow/evaluation?from=...&to=...`
- `GET /api/admin/predictions/models/comparison/{gameId}`
- 관리자 화면의 “경기별 모델 비교”, “Shadow 성능” 카드

기존 온라인 shadow 평가는 Accuracy, Log Loss, Brier, Macro F1, confusion matrix,
DRAW 평균 등을 계산하지만 시즌/coverage/calibration bin은 제공하지 않는다.
이번 작업에서는 운영 API를 확장하지 않고 읽기 전용 오프라인 명령으로 보완했다.

## logistic-v1 학습 구조

artifact는 임시 상수가 아니라 로컬 dataset에서 재현 가능하게 학습된 모델이다.

- 모델 생성 시각: 2026-08-11T06:01:10Z
- Python 3.12.13, scikit-learn 1.7.2
- 알고리즘: multinomial Logistic Regression, lbfgs, L2 regularization
- 후보: `C ∈ {0.01, 0.1, 1, 10}` × `class_weight ∈ {none, balanced}`
- 선택 기준: validation Log Loss → Brier → Accuracy 사전순
- 선택값: `C=0.1`, `class_weight=none`
- 누락값 처리: train 중앙값
- scaling: train에서 fit한 `StandardScaler`
- random split 없음, 시즌 시간 순서 split

| 구간 | 기간 | 경기 | HOME | DRAW | AWAY |
|---|---:|---:|---:|---:|---:|
| 후보 train | 2023-04-02~2024-10-01 | 1,430 | 731 (51.12%) | 22 (1.54%) | 677 (47.34%) |
| validation | 2025-03-23~2025-10-04 | 715 | 354 (49.51%) | 22 (3.08%) | 339 (47.41%) |
| 최종 train | 2023-04-02~2025-10-04 | 2,145 | 1,085 (50.58%) | 44 (2.05%) | 1,016 (47.37%) |
| untouched test | 2026-03-29~2026-08-01 | 489 | 241 (49.28%) | 12 (2.45%) | 236 (48.26%) |

`balanced` 후보는 DRAW recall을 올렸지만 validation Log Loss가 약 1.08로,
선택된 unweighted 모델의 0.812보다 크게 나빠졌다. 현재 모델은 class imbalance를
별도 가중하지 않는다.

표준화 이후 coefficient는 다음과 같다. 각 열은 해당 class logit의 계수다.

| feature | AWAY_WIN | DRAW | HOME_WIN |
|---|---:|---:|---:|
| seasonWinRateDiff | -0.100957 | 0.072923 | 0.028034 |
| recent5WinRateDiff | -0.053836 | 0.073829 | -0.019993 |
| recent10WinRateDiff | 0.058713 | -0.132546 | 0.073833 |
| recent5RunDiff | -0.112778 | 0.127261 | -0.014483 |
| recent10RunDiff | 0.073968 | -0.119016 | 0.045048 |
| homeAwayWinRateDiff | 0.077199 | -0.068356 | -0.008842 |
| intercept | 1.030763 | -2.127730 | 1.096967 |

## 평가 데이터

승격 판단에는 artifact가 전혀 보지 않은 2026년 489경기만 사용했다.

- 기간: 2026-03-29~2026-08-01
- 실제 결과가 있는 정규 FINISHED 경기만 포함
- class: HOME 241, DRAW 12, AWAY 236
- 두 모델에 완전히 동일한 489개 row와 feature를 입력
- CSV SHA-256:
  `d42fe5eae01c491461ec9475920d6bad3c0c0d84616ac5cc627ed34b0c0b4d78`
- artifact의 `trainingDataSha256`과 일치
- game ID 중복 0
- 484경기는 core feature 6/6, 5경기는 5/6

2021~2022 데이터는 없으며 추정하지 않았다. 2023~2025는 존재하지만 학습 또는
선택에 참여했으므로 시즌 추세 확인용으로만 제시한다.

## 데이터 누수 점검

코드 경로에서 결과/미래 feature 누수는 발견하지 못했다.

운영 feature 조회는 다음 조건을 함께 적용한다.

- team/pitcher stat: `stat_date <= gameDate`
- team/pitcher stat: `collected_at < gameStartAt`
- 선발 발표: `first_collected_at < gameStartAt`
- 운영 snapshot: 실제 예측 생성 시각을 `feature_as_of`로 저장
- 마감 뒤 finalization: 기존 마지막 snapshot을 복사하며 재계산하지 않음

Historical evaluation은 수집 통계 테이블을 사용하지 않는다. 같은 시즌의
`FINISHED` 경기 중 `gameDate < target.gameDate`인 결과만으로 feature를 재구성한다.
따라서 경기 당일 결과, 미래 경기, 목표 경기 결과가 feature에 들어가지 않는다.
같은 날 더블헤더의 앞 경기 결과도 제외하므로 시간 관점에서는 오히려 보수적이다.
snapshot의 `feature_as_of`는 `gameStartAt - 1초`다.

`actualResult`는 CSV label에만 있으며 Python `FEATURES` 목록과 Java
`PredictionFeatures`에는 없다. scaler/imputer는 selection 시 2023~2024만,
최종 artifact에서는 2023~2025만 fit했다. 2026 test는 후보 선택에 사용되지 않았다.

남는 한계는 historical snapshot의 `created_at`이 경기 후 backfill 시각일 수 있다는
점이다. 입력값 자체는 이전 경기일만 보지만, 당시 원천 데이터의 수집시각을 보존한
archival snapshot은 아니다. 따라서 이번 결과를 genuine online shadow history라고
표현하지 않는다.

## 전체 성능 비교

| metric | baseline-v1 | logistic-v1 | logistic - baseline |
|---|---:|---:|---:|
| Top-1 Accuracy | 52.97% | 55.62% | +2.66%p, 95% CI [-1.23, +6.54] |
| Multiclass Log Loss | 0.890382 | 0.788279 | -0.102103, 95% CI [-0.143903, -0.061419] |
| Multiclass Brier | 0.573096 | 0.519594 | -0.053502, 95% CI [-0.082687, -0.025346] |

Accuracy 증가는 불확실하지만, 확률 품질의 핵심인 Log Loss와 Brier 개선은 paired
bootstrap에서 모두 0을 넘지 않는다. logistic-v1의 평균 최대 확률은 52.63%로
baseline-v1의 62.92%보다 낮다. baseline-v1의 과도한 확신을 완화한 결과가 Log
Loss/Brier 개선의 중요한 부분이다.

## DRAW 비교

| 항목 | 실제/baseline-v1 | logistic-v1 |
|---|---:|---:|
| 실제 DRAW 비율 | 2.454% | 2.454% |
| 평균 DRAW probability | 8.754% | 2.122% |
| 실제 DRAW 경기 평균 | 8.178% | 2.091% |
| 비-DRAW 경기 평균 | 8.768% | 2.123% |
| DRAW probability 범위 | 5.000~11.994% | 1.168~3.640% |
| 표준편차 | 1.930%p | 0.487%p |
| DRAW top-1 예측 수 | 0 | 0 |
| DRAW recall | 0% | 0% |
| DRAW-vs-rest ROC-AUC | 0.412 | 0.499 |
| DRAW-vs-rest Average Precision | 0.0267 | 0.0260 |

baseline-v1은 DRAW를 실제보다 평균 6.30%p 과대평가한다. logistic-v1은 평균
기준으로 0.33%p 과소평가하여 훨씬 잘 맞는다. 그러나 두 모델 모두 실제 DRAW
경기에 더 높은 DRAW 확률을 주지 못했다. logistic-v1의 AUC 0.499는 base rate
주변 확률을 출력할 뿐 경기별 DRAW 위험을 구분하지 못한다는 강한 신호다.

## 시즌별 결과

2023~2025 logistic 수치는 최종 artifact가 해당 시즌을 학습한 뒤의 in-sample 또는
selection 재사용 값이므로 승격 근거로 사용하면 안 된다.

| 시즌 | 역할 | 경기 | 실제 DRAW | Accuracy B/L | Log Loss B/L | Brier B/L | 평균 DRAW B/L |
|---|---|---:|---:|---:|---:|---:|---:|
| 2023 | train, 설명용 | 715 | 1.68% | 54.41/54.83% | 0.8645/0.7619 | 0.5553/0.5124 | 9.05/2.05% |
| 2024 | train, 설명용 | 715 | 1.40% | 51.75/52.03% | 0.8684/0.7570 | 0.5616/0.5119 | 9.17/2.09% |
| 2025 | selection+final train, 설명용 | 715 | 3.08% | 52.03/53.29% | 0.8961/0.8040 | 0.5738/0.5242 | 8.81/2.02% |
| 2026 | untouched test | 489 | 2.45% | 52.97/55.62% | 0.8904/0.7883 | 0.5731/0.5196 | 8.75/2.12% |

## Feature Coverage별 결과

여기서 coverage는 두 모델이 공유한 logistic core feature 6개의 가용 비율이다.
baseline-v1의 운영 `featureCoverage`는 가중치 합이며 정의가 다르므로 직접 같은
축으로 섞지 않았다.

| coverage | 경기 | Accuracy B/L | Log Loss B/L | Brier B/L |
|---|---:|---:|---:|---:|
| 0.8 이상, 1.0 미만 | 5 | 60.00/60.00% | 0.8470/0.7018 | 0.5529/0.4888 |
| 1.0 | 484 | 52.89/55.58% | 0.8908/0.7892 | 0.5733/0.5199 |

0.8 미만 표본은 0개다. 5경기 구간은 통계 판단에 너무 작다. 따라서 데이터가
부족할 때 어느 모델이 안정적인지, coverage가 높아질수록 logistic이 좋아지는지,
baseline fallback이 더 나은 구간이 있는지는 이 데이터로 결론낼 수 없다.

Historical builder는 팀 타율, 팀 ERA, 선발 ERA/WHIP를 모두 null로 둔다.
그러므로 선발투수 데이터 유무에 따른 성능 변화도 측정할 수 없다.

## HOME / AWAY 편향

| 모델 | 평균 HOME | 실제 HOME | 차이 | 평균 AWAY | 실제 AWAY | 차이 |
|---|---:|---:|---:|---:|---:|---:|
| baseline-v1 | 46.50% | 49.28% | -2.78%p | 44.75% | 48.26% | -3.52%p |
| logistic-v1 | 50.84% | 49.28% | +1.56%p | 47.03% | 48.26% | -1.23%p |

baseline-v1은 DRAW에 확률을 과도하게 배분해 HOME과 AWAY를 모두 낮게 평가한다.
logistic-v1은 HOME을 약간 높게, AWAY를 약간 낮게 평가하지만 편향 크기는 작다.

## Calibration

10% probability bin 기준 Expected Calibration Error는 다음과 같다.
빈 bin까지 포함한 전체 calibration 자료는 재현 명령의 JSON 출력에 있다.

| class | baseline-v1 ECE | logistic-v1 ECE |
|---|---:|---:|
| HOME_WIN | 14.11%p | 1.93%p |
| DRAW | 6.30%p | 0.33%p |
| AWAY_WIN | 13.87%p | 1.23%p |

logistic-v1의 확률 범위는 HOME/AWAY가 주로 40~60%, DRAW가 모두 0~10%에
몰려 있다. ECE 개선은 분명하지만 확률 범위가 좁아서 높은 confidence 구간의
calibration은 아직 검증되지 않았다.

## 모델이 크게 다르게 판단한 실제 경기 예시

Historical CSV에는 team/pitcher 이름과 원시 팀 타율·ERA가 없으므로 game ID,
날짜와 공통 feature 차이만 제시한다. 없는 값을 추정하지 않았다. 확률 순서는
HOME/DRAW/AWAY다.

| 유형 | 경기 | 실제 | baseline-v1 | logistic-v1 | 주요 공통 feature |
|---|---|---|---|---|---|
| 가장 비슷 | #46, 2026-04-04 | AWAY | 59.19/9.52/31.29 | 63.91/2.86/33.23 | season +0.233, recent5 +0.050, recent10 +0.233, run5 +0.60, run10 +1.66, venue -1.000 |
| 가장 다름·logistic만 적중 | #48, 2026-04-05 | HOME | 14.29/5.28/80.43 | 49.75/3.20/47.05 | season -0.404, recent5 -0.400, recent10 -0.404, run5 -4.40, run10 -4.57, venue -1.000 |
| baseline만 적중 | #43, 2026-04-04 | AWAY | 17.60/6.31/76.09 | 52.95/3.45/43.60 | season -0.300, recent5 -0.150, recent10 -0.300, run5 -2.60, run10 -3.33, venue -1.000 |
| 실제 DRAW | #140, 2026-04-26 | DRAW | 75.04/6.54/18.42 | 52.65/1.56/45.79 | season +0.196, recent5 +0.200, recent10 +0.300, run5 +1.20, run10 +2.70, venue +0.315 |

모든 예시의 공통 feature coverage는 1.0이다.

## 최종 판단

**B. logistic-v1 운영 승격 후보**로 분류한다.

근거는 다음 세 가지가 동시에 충족된 것이다.

1. 미사용 2026 test에서 Log Loss가 0.102 낮고 paired 95% CI 전체가 0 아래다.
2. Brier Score가 0.0535 낮고 paired 95% CI 전체가 0 아래다.
3. 10-bin ECE가 HOME, DRAW, AWAY 모두 크게 낮다.

그러나 “후보”까지만 추천한다. 운영 승격 전에는 실제 운영 시각에 저장된
baseline/shadow 공통 `FINAL` history로 같은 개선이 반복되는지 확인해야 한다.
현재 결과만으로 logistic-v1이 DRAW 경기를 찾아낸다고 말할 수 없으며, 오히려
DRAW 구분력은 거의 없다.

## 다음 작업 추천

1. **실제 online shadow 교집합 평가**: DB 인증 가능한 환경에서 동일 snapshot,
   동일 artifact hash, `FINAL`, `FINISHED` 조건의 표본 수와 기간을 먼저 확정한다.
   운영 승격은 그 교집합에서 Log Loss/Brier/calibration 개선이 재현될 때만 검토한다.
2. **운영 snapshot 기반 리포트 확장**: 시즌, shared coverage, 선발 데이터 유무,
   class별 10-bin calibration을 읽기 전용으로 산출한다. history는 수정하지 않는다.
3. **DRAW discrimination 개선 연구**: naive `class_weight=balanced`는 전체 확률
   품질을 훼손했으므로 그대로 채택하지 않는다. 시간순 out-of-fold calibration,
   별도 DRAW risk feature/모델, rare-event 평가를 검토한다.
4. **historical as-of feature 보강**: 수집시각이 보존된 팀 타율/ERA/선발 ERA/WHIP
   archival snapshot이 확보될 때만 dataset에 추가하고 재학습한다. 미래 현재값으로
   과거 feature를 backfill하지 않는다.

## 재현 명령

`backend` 디렉터리에서 실행한다.

```powershell
ml/.venv/Scripts/python.exe ml/compare_models.py
```

전체 시즌의 설명용 결과도 JSON에 포함하려면 다음을 사용한다.

```powershell
ml/.venv/Scripts/python.exe ml/compare_models.py --scope all
```

테스트:

```powershell
ml/.venv/Scripts/python.exe -m unittest discover -s ml/tests -v
```
