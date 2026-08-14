# 운영 baseline-v1 vs logistic-v1 FINAL 평가

## 목적

관리자 read-only report는 실제 운영 당시 저장된 `FINAL` prediction만 사용해
`baseline-v1`과 `logistic-v1`을 비교한다. Historical reconstruction 결과와
운영 shadow 결과는 섞지 않는다.

- Historical unseen test: `backend/ml/compare_models.py`
- Operational stored FINAL: `GET /api/admin/predictions/shadow/evaluation`

이 report를 실행해도 prediction, snapshot, 경기, 배당, 사용자 예측, 정산 데이터는
생성하거나 수정하지 않는다. 서비스와 repository query 모두 read-only다.

## 포함 조건

요청 기간 안에서 다음 조건을 모두 만족한 경기만 지표에 포함한다.

1. baseline history: `model_version=baseline-v1`, `source=OPERATIONAL`,
   `stage=FINAL`
2. logistic history: `model_version=logistic-v1`, `source=SHADOW`,
   `stage=FINAL`
3. 경기 상태 `FINISHED`, 실제 결과 존재
4. 두 history가 동일한 non-null `feature_snapshot_id` 사용
5. snapshot `generation_method=OPERATIONAL_PREGAME`
6. snapshot `feature_as_of`와 양쪽 prediction `generated_at`이 경기 시작보다 이전
7. logistic history의 `model_artifact_hash`가 현재 배포 artifact SHA-256과 일치

`CANCELLED` 및 한쪽 prediction만 존재하는 경기는 포함되지 않는다. Snapshot,
pregame cutoff, artifact 검증에서 제외된 교집합 후보 수는 응답에 별도로 제공한다.

## 관리자 report

관리자 화면의 **Shadow 성능** 카드에서 기간을 선택하고 “Shadow 평가 조회”를
누른다. 화면에는 다음이 표시된다.

- baseline/logistic의 eligible FINAL 수와 최종 공통 평가 경기 수
- 실제 HOME/DRAW/AWAY 수
- Accuracy, Log Loss, Brier Score
- logistic-baseline paired difference와 가능한 경우 bootstrap 95% CI
- class별 실제 발생률, 평균 예측 확률, 10-bin ECE
- snapshot/artifact/pregame audit 제외 수
- advisory sample-size gate까지 필요한 공통 경기와 DRAW 수

전체 10-bin calibration 자료와 confusion matrix는 API JSON에 포함된다.

```http
GET /api/admin/predictions/shadow/evaluation?from=2026-08-01&to=2026-10-31
```

관리자 세션이 필요하다. 이 endpoint는 `GET`이며 평가 대상 history를 읽기만 한다.

## 지표 정의

Historical 평가와 같은 정의를 사용한다.

- Accuracy: 가장 높은 확률의 class가 실제 결과와 같은 비율
- Log Loss: 실제 class 확률의 음의 로그 평균, 최소 확률 `1e-15`
- Multiclass Brier: 세 class에 대한 squared error 합의 경기 평균
- Calibration: HOME/DRAW/AWAY 각각 0~10%, ..., 90~100% bin
- ECE: bin별 `|평균 확률 - 실제 발생률|`의 표본 가중 평균
- Paired difference: 각 경기에서 `logistic metric - baseline metric`
- Paired bootstrap: 동일 경기 쌍을 복원추출하는 10,000회 deterministic bootstrap

Operational report는 DB에 실제 저장된 소수 둘째 자리 percentage를 그대로
0~1 확률로 변환한다. Historical script는 artifact의 unrounded engine output을
사용하므로 두 결과를 한 표본처럼 합치지 않는다.

공통 FINAL이 30경기 미만이면 bootstrap CI는 `null`이며
`bootstrapRepetitions=0`이다. 이는 점 추정값을 숨기지 않으면서 작은 표본의 CI를
과해석하지 않기 위한 최소 조건이다.

## 표본 크기 안내

API의 sample-size 판정은 통계적 보장을 선언하는 기준이 아니라 운영 검토용
advisory gate다.

- bootstrap 최소: 공통 FINAL 30경기
- 승격 검토 표본 gate: 공통 FINAL 200경기 이상 및 각 class 10경기 이상
- gate를 통과해도 Log Loss/Brier paired CI와 class calibration을 다시 확인해야 함

KBO DRAW 비율이 낮으므로 실제 승격 검토 시점은 전체 200경기보다 DRAW 10경기
조건이 더 늦게 충족될 수 있다. 관리 화면의 `additionalCommonGamesNeeded`와
`additionalDrawsNeeded` 중 더 늦게 충족되는 시점에 정식 재평가한다. 그전에도
공통 FINAL 50경기가 추가될 때마다 추세 점검을 권장한다.

## Historical benchmark와 분리

2026 unseen historical test는 다음과 같다.

| metric | baseline-v1 | logistic-v1 | logistic-baseline |
|---|---:|---:|---:|
| Accuracy | 52.97% | 55.62% | +2.66%p |
| Log Loss | 0.890382 | 0.788279 | -0.102103 |
| Brier Score | 0.573096 | 0.519594 | -0.053502 |

이 수치는 operational endpoint 응답에 합산하지 않는다. Historical은 승격 후보를
선별하는 backtest이고, operational FINAL은 실제 운영 조건에서 그 결과가 재현되는지
확인하는 별도 검증 단계다.
