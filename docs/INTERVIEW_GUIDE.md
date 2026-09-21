# KBO Predictor 면접 대비 가이드

> 기준: 2026-09-21 현재 코드와 Flyway migration  
> 원칙: 구현하지 않은 기능을 답변에 추가하지 않는다. 운영 예측은 `baseline-v1`, 실제 ML `logistic-v1`은 shadow다.

## 1. 90초 프로젝트 설명

> KBO 공식 웹에서 경기 일정·상태·결과, 팀 성적, 선발투수 데이터를 수집하고 사용자가 가상 포인트로 경기 결과를 예측하는 서비스입니다. 경기 종료 후에는 공식 결과를 기준으로 배당을 확정하고, 비관적 잠금과 단일 트랜잭션으로 잔액과 포인트 원장을 함께 갱신합니다. 결과가 정정되는 경우를 위해 정산을 revision으로 관리하고 원 지급을 reversal history로 취소한 뒤 재정산할 수 있게 했습니다. 시스템 예측의 운영 기본값은 머신러닝이 아니라 10개 통계 feature를 가중 결합하는 `baseline-v1`이고, 별도의 다항 logistic regression은 같은 snapshot으로 shadow 평가합니다. 서비스는 Java 21/Spring Boot/MySQL/React로 구현했고 Flyway, Docker Compose, Nginx HTTPS, GitHub Actions와 AWS SSM을 통해 단일 EC2에 배포하도록 구성했습니다.

이 설명에서 질문이 들어올 가능성이 높은 단어는 “공식 결과”, “비관적 잠금”, “원장”, “revision”, “baseline”, “shadow”, “동일 snapshot”이다. 아래 내용을 본인 말로 설명할 수 있어야 한다.

---

## 2. 반드시 알고 있어야 할 핵심 개념

### 2.1 운영 예측과 ML의 구분

- 현재 구현: `application.yaml`의 active model은 `baseline-v1`. 10개 feature를 사람이 지정한 weight로 결합하고 sigmoid로 확률화한다.
- 이유: 설명 가능하고 데이터가 일부 빠져도 available weight/coverage로 동작한다.
- 대안: logistic regression, gradient boosting, neural network.
- 장점: 결정론적·설명 가능·운영 단순. 단점: weight가 학습된 최적값이 아니고 calibration 근거가 약하다.
- 실제 ML: `backend/ml`의 scikit-learn multinomial logistic regression. JSON artifact와 SHA 검증, Java 추론, shadow history가 구현되어 있다. 기본 운영은 아니다.

### 2.2 포인트 잔액과 원장

- 현재 구현: `users.point`는 현재 잔액, `point_histories`는 변경 사유와 `balance_after`를 기록한다.
- 이유: 매 요청마다 원장을 합산하지 않고 빠르게 잔액을 읽으면서도 감사·정산 취소 근거를 남기기 위해서다.
- 대안: 원장만 저장해 합산, event sourcing, DB trigger.
- 장점: 조회와 domain update가 단순. 단점: 두 표현이 불일치할 수 있어 reconciliation이 필요하지만 현재 자동 job은 없다.

### 2.3 비관적 잠금

- 현재 구현: 예측·정산 모두 game을 먼저 `PESSIMISTIC_WRITE`, point 변경 전에 user도 `PESSIMISTIC_WRITE`한다.
- 이유: 잔액 lost update와 odds pool/중복 정산 race를 transaction 안에서 직렬화한다.
- 대안: `@Version` optimistic lock+retry, atomic SQL update, Redis/queue.
- 장점: 충돌 처리와 correctness가 명확. 단점: 같은 경기 hot row, lock wait, scale 한계.

### 2.4 정산 revision

- 현재 구현: `game_settlements`에 game별 revision과 state를 남긴다. 잘못된 정산은 최신 revision만 rollback하고 지급 history에 reversal을 만든 뒤 결과를 수정해 새 revision으로 정산한다.
- 이유: 과거 row를 덮어쓰면 누가 언제 어떤 결과로 얼마를 지급했는지 사라진다.
- 대안: 기존 prediction/ledger를 직접 수정, compensating event stream.
- 장점: 추적·멱등성·랭킹 복구가 강하다. 단점: current active revision filter가 모든 조회에 필요하다.

### 2.5 point-in-time feature

- 현재 구현: 경기 시작 전 수집된 TeamStat/PitcherStat만 사용하고 feature snapshot과 prediction history를 저장한다.
- 이유: 경기 후 갱신된 통계를 과거 예측에 사용하면 미래 데이터 누출로 평가가 부풀려진다.
- 대안: online feature store, bitemporal table.
- 장점: 재현성·평가 신뢰성. 단점: snapshot 저장량과 별도 historical builder 복잡도.

### 2.6 session 인증과 CSRF

- 현재 구현: BCrypt+Spring Security+HttpSession, login 시 session 교체, `XSRF-TOKEN` cookie와 `X-XSRF-TOKEN` header.
- 이유: 브라우저 same-origin SPA이고 server-side logout/revocation이 단순하다.
- 대안: JWT access/refresh, OAuth2/OIDC.
- 장점: token rotation 구현 부담이 적다. 단점: container restart logout, scale-out 시 shared session store 필요.

### 2.7 Flyway와 Hibernate validate

- 현재 구현: schema 변경은 V1~V27 migration, 운영 시작 시 Flyway validate/migrate, Hibernate `ddl-auto=validate`.
- 이유: application ORM이 운영 schema를 임의 변경하지 않고 변경 순서를 재현한다.
- 대안: Hibernate update, 수동 SQL, Liquibase.
- 장점: audit/reproducibility. 단점: migration 호환성과 rollback 전략을 직접 관리해야 한다.

### 2.8 수집 transaction 분리

- 현재 구현: HTTP fetch/parsing은 긴 transaction 밖, 각 game/stat write는 `REQUIRES_NEW`.
- 이유: 외부 I/O 동안 DB lock을 잡지 않고 한 건 실패가 전체 batch를 취소하지 않게 한다.
- 대안: 전체 batch atomic transaction, staging table+merge, message queue.
- 장점: 부분 가용성과 짧은 transaction. 단점: batch 전체가 같은 시점에 완전하다는 보장은 없다.

### 2.9 ranking 계산

- 현재 구현: ranking table 없이 JDBC CTE/window function. current active settlement revision과 reward ledger를 결합한다.
- 이유: correction을 즉시 반영하고 별도 aggregate의 동기화 문제를 피한다.
- 대안: materialized ranking, Redis sorted set, event-driven aggregate.
- 장점: source-of-truth와 즉시 일치. 단점: 사용자/예측이 커지면 쿼리 비용 증가.

### 2.10 배포 방식

- 현재 구현: main push의 tests/build 후 GitHub OIDC로 AWS role을 얻고 SSM으로 EC2에서 `git reset`/Compose build/up/health check를 수행한다.
- 이유: SSH private key를 GitHub secret으로 두지 않고 단일 EC2 운영을 단순화한다.
- 대안: ECR immutable image+ECS, blue/green, Kubernetes.
- 장점: 구성 이해가 쉽고 keyless. 단점: CI 검증 image와 서버 build image가 동일 digest가 아니며 자동 rollback/HA가 없다.

---

## 3. 예상 면접 질문과 모범 답변

### Q1. 이 서비스의 핵심 backend 흐름을 한 문장으로 설명해 보세요.

**답변:** 공식 KBO 데이터를 snapshot으로 수집하고, 이를 이용해 시스템 예측을 만든 뒤 사용자 포인트 예측을 받아 공식 종료 결과로 배당·잔액·원장을 한 transaction에서 정산하는 흐름입니다. 핵심 연결은 `Game → SystemPrediction/UserPrediction → GameSettlement → PointHistory → Ranking`입니다.

**근거:** `GameSyncService`, `SystemPredictionGenerationService`, `UserPredictionService`, `PredictionSettlementService`, `RankingQueryRepository`.

**꼬리질문: 가장 복잡한 부분은 무엇이었나요?**  
정상 정산보다 결과 correction입니다. 이미 지급한 point를 단순 overwrite하지 않고 latest settlement rollback, reversal ledger, 결과 수정, 새 revision 재정산 순서로 제한했습니다.

### Q2. 왜 운영 예측을 머신러닝이라고 부르면 안 되나요?

**답변:** active model이 `baseline-v1`이고, feature weight와 scale이 `application.yaml`에 사람이 지정한 상수로 들어갑니다. sigmoid는 확률 형태로 변환하는 함수일 뿐 parameter를 학습했다는 뜻이 아닙니다. 따라서 “규칙/통계 기반 weighted baseline”이 정확합니다.

**꼬리질문: 실제 ML은 전혀 없나요?**  
scikit-learn multinomial logistic regression `logistic-v1`은 있습니다. 2023~2025 데이터로 학습한 JSON artifact를 Java가 추론하고 SHA를 확인하며 shadow history로 평가하지만 기본 운영 모델은 아닙니다.

### Q3. baseline-v1은 어떤 feature를 쓰나요?

**답변:** 시즌, 최근 5/10경기, venue 승률; 최근 5/10경기 득실; 팀 타율·ERA; 선발 ERA·WHIP의 10개 차이를 사용합니다. 각 값을 scale로 정규화·clamp하고 available weight로 가중 평균한 뒤 홈 이점과 coverage shrink를 적용합니다.

**꼬리질문: feature가 없으면 어떻게 하나요?**  
0이라는 관측값으로 취급하지 않고 factor를 제외합니다. 사용 가능한 weight 합으로 다시 나누고 coverage가 낮으면 strength를 축소합니다.

### Q4. 승리 확률과 무승부 확률은 어떻게 계산하나요?

**답변:** weighted strength의 절댓값이 작을수록 draw probability를 5~12% 범위에서 높입니다. 나머지 decisive probability를 `sigmoid(1.8×strength)` 비율로 home/away에 나눕니다. 마지막 rounding 잔차는 away에 반영해 합을 맞춥니다.

**꼬리질문: 이 확률이 calibrated됐나요?**  
baseline 확률은 calibration을 별도로 학습하지 않았습니다. 평가에서 log loss와 Brier를 계산하지만 calibration curve나 isotonic/Platt calibration은 구현되지 않았습니다.

### Q5. logistic-v1을 바로 운영에 쓰지 않은 이유는 무엇인가요?

**답변:** 코드가 자동 승격 이유를 명시하지는 않지만 현재 구조상 먼저 동일 snapshot의 shadow로 paired evaluation을 축적하도록 설계돼 있습니다. 고정 final test에서 accuracy는 baseline보다 높지만 draw 12건을 하나도 맞히지 못했고 live sample 안정성도 더 확인해야 합니다. 그래서 코드상 사실은 “shadow 비교 중”입니다.

**꼬리질문: 승격 기준은 있나요?**  
자동 minimum sample/metric gate는 아직 없습니다. 향후 log loss, Brier, accuracy, per-class recall, calibration을 기간별로 평가하고 관리자 승인과 rollback 가능한 version 전환이 필요합니다.

### Q6. 과거 경기 평가에서 미래 데이터 누출을 어떻게 막았나요?

**답변:** 운영 feature는 `collectedAt < gameStart`인 snapshot만 사용합니다. backfill은 `HistoricalPredictionFeatureBuilder`가 대상 경기보다 이전의 종료 경기로 recent form을 다시 계산합니다. 예측 당시 입력은 `PredictionFeatureSnapshot`으로 저장합니다.

**꼬리질문: `stat_date <= game_date`만으로 충분하지 않나요?**  
충분하지 않습니다. 같은 날짜라도 경기가 끝난 뒤 수집한 통계일 수 있으므로 수집 시각까지 제한해야 합니다.

### Q7. 시스템 예측을 왜 current table과 history table로 나눴나요?

**답변:** `system_predictions`는 화면에서 경기당 최신 값을 빠르게 읽기 위한 current projection이고, history는 provisional/final, operational/backtest/shadow, model version과 artifact hash를 남겨 재현·평가하기 위한 기록입니다.

**꼬리질문: current row만 versioning하면 안 되나요?**  
overwrite하면 당시 노출된 값과 모델 비교 sample을 잃습니다. 반대로 history만 사용하면 일반 조회 query가 복잡해집니다.

### Q8. 사용자 예측 요청의 transaction 범위를 설명해 보세요.

**답변:** `UserPredictionService.createPrediction()` 하나의 transaction 안에서 game과 user를 잠그고 검증, prediction insert, odds pool 갱신, balance 차감, point history 저장을 모두 수행합니다. `PointService`는 `MANDATORY`라 outer transaction 없이 point만 바꾸지 못합니다.

**꼬리질문: odds 저장 후 history 저장이 실패하면요?**  
같은 transaction이므로 prediction, odds, balance 모두 rollback됩니다.

### Q9. 중복 예측은 어떻게 막나요?

**답변:** service에서 user-game 존재 여부를 확인하고, DB에도 `UNIQUE(user_id,game_id)`가 있습니다. 동시에 통과한 race는 `saveAndFlush()`에서 unique violation이 발생하며 conflict로 변환합니다.

**꼬리질문: application check가 있는데 unique가 왜 필요한가요?**  
두 request가 check 시점에는 모두 없다고 볼 수 있으므로 DB의 atomic uniqueness가 최종 방어선입니다.

### Q10. 잔액 부족 race는 어떻게 막나요?

**답변:** `UserPointLockService`가 user row를 `PESSIMISTIC_WRITE`로 읽고 이미 persistence context에 있던 entity도 refresh합니다. 그래서 동시에 두 게임에 betting해도 두 번째 transaction은 첫 번째 commit 후 잔액을 기준으로 검증합니다.

**꼬리질문: optimistic locking은 왜 안 썼나요?**  
현재 코드는 retry loop 없이 충돌을 먼저 직렬화하는 비관적 전략을 선택했습니다. 충돌이 드물다면 `@Version`과 제한된 retry가 lock 시간을 줄이는 대안입니다.

### Q11. 왜 game을 user보다 먼저 잠그나요?

**답변:** odds pool과 경기 마감·정산을 한 기준으로 직렬화하고, 예측과 정산이 같은 lock order를 따르게 해 deadlock 가능성을 낮추기 위해서입니다. 정산 안에서도 user id 순서로 처리합니다.

**꼬리질문: 단점은요?**  
같은 경기의 모든 예측이 하나의 game row에 모여 처리량 병목이 됩니다.

### Q12. 배당은 어떻게 계산되나요?

**답변:** outcome별 누적 stake를 갖는 pari-mutuel 구조입니다. 선택 outcome의 pool 대비 전체 pool 비율을 배당으로 사용하고 최대 10.00으로 제한합니다. 마감 시 final odds를 고정하며 지급액은 `floor(stake × finalOdds)`입니다.

**꼬리질문: 원금이 별도로 반환되나요?**  
아닙니다. stake는 예측 때 차감되고, 적중 payout 자체에 원금 반환이 포함된 구조입니다.

### Q13. GET odds endpoint가 왜 기술부채인가요?

**답변:** `GameOddsService.getOddsByGameId()`는 game을 잠그고 odds row가 없으면 만들거나 마감 시 finalization할 수 있습니다. 즉 GET이 상태를 바꿀 수 있어 HTTP safe semantics와 캐시·관찰 가능성에 맞지 않습니다.

**꼬리질문: 어떻게 바꾸겠나요?**  
row 생성과 finalization은 prediction command/scheduler에서 보장하고 GET은 없으면 계산된 read DTO만 반환하도록 분리하겠습니다.

### Q14. 경기 종료 정산의 멱등성은 어떻게 보장하나요?

**답변:** game row lock 후 latest active settlement를 확인해 이미 SETTLED면 다시 지급하지 않습니다. DB의 `(game_id, revision)` unique와 `(prediction,type,settlement_revision)` history unique도 중복 commit을 막습니다.

**꼬리질문: scheduler가 두 번 실행되면요?**  
한 JVM에서도 중복 호출될 수 있지만 transaction lock과 unique가 business effect를 1회로 제한합니다. 다만 scheduler 실행 자체의 multi-instance 중복은 별도 분산 lock이 없습니다.

### Q15. 정산 중 세 번째 사용자에서 오류가 나면 앞의 두 사용자는 지급되나요?

**답변:** 지급되지 않습니다. 한 경기의 모든 정산이 `PredictionSettlementService`의 단일 transaction이어서 예외가 나면 settlement, prediction 상태, user balance, history가 모두 rollback됩니다. 이 atomicity는 recovery integration test에도 포함됩니다.

### Q16. 잘못된 경기 결과를 어떻게 수정하나요?

**답변:** 관리자만 latest SETTLED revision을 rollback합니다. original reward/refund history마다 reversal을 만들고 prediction을 PENDING으로 되돌립니다. 그 뒤 결과를 수정하고 expected rollback revision을 지정해 새 revision으로 재정산합니다.

**꼬리질문: 왜 결과를 바로 바꾸고 재정산하지 않나요?**  
기존 지급이 살아 있는 상태에서 새 지급이 생길 수 있고, 어느 결과를 기준으로 무엇을 취소했는지 추적이 어려워집니다.

### Q17. rollback 시 사용자가 지급 포인트를 이미 썼다면요?

**답변:** V23의 명시적 정책으로 settlement rollback history는 balance를 음수로 만들 수 있습니다. 기존 지급을 완전히 취소해 회계적 복구를 우선한 선택입니다.

**꼬리질문: 이것이 최선인가요?**  
정합성 관점에서는 설명 가능하지만 제품 정책이 부족합니다. 음수 상태 betting 차단, UI 안내, 운영 조정/상환 절차가 추가되어야 합니다.

### Q18. point history를 별도 table로 둔 이유는 무엇인가요?

**답변:** 잔액 숫자만으로는 signup bonus, betting, reward, refund, rollback의 원인과 순서를 복구할 수 없기 때문입니다. history에는 변경량, 변경 후 balance, game/prediction/settlement revision, reversal link를 둡니다.

**꼬리질문: User의 point만 바꾸면 안 되나요?**  
조회는 되지만 감사·오정산 취소·랭킹 손익 근거가 사라집니다.

**꼬리질문: 두 값이 불일치하면 어떻게 복구하나요?**  
현재 자동 reconciliation은 없습니다. 이것은 한계입니다. 마지막 history의 balance와 user point 비교, 원장 replay, 관리자 repair record를 추가해야 합니다.

### Q19. 일일 로그인 보너스 동시 요청은 어떻게 처리하나요?

**답변:** user row를 잠그고 Asia/Seoul 날짜의 bonus history 존재 여부를 확인합니다. DB에도 `(user_id, bonus_date, type)` unique가 있어 동시에 로그인해도 한 번만 지급됩니다. 통합 concurrency test가 있습니다.

### Q20. 랭킹에 rollback된 정산이 남지 않나요?

**답변:** 기간 랭킹 SQL은 current active `SETTLED` settlement revision에 연결된 prediction과 reward ledger만 집계합니다. correction 전 rollback revision은 제외되고 새 revision만 반영됩니다.

**꼬리질문: total ranking은 어떻게 다른가요?**  
total score는 현재 `users.point`입니다. 적중 수 등의 tie-break 통계는 활성 정산 기준으로 계산합니다.

### Q21. 주간/월간 랭킹의 점수는 무엇인가요?

**답변:** 승리는 `reward - stake`, 패배는 `-stake`, 취소 환불은 0인 기간 수익입니다. 기간은 Asia/Seoul 기준 주간 월요일~다음 월요일, 월간 월초~다음 월초입니다.

**꼬리질문: 적중률 분모에 환불이 들어가나요?**  
아니요. WON+LOST만 사용합니다.

### Q22. 왜 ranking을 JPA가 아니라 JDBC native SQL로 작성했나요?

**답변:** CTE, window function, conditional aggregate, current revision join이 핵심이라 object graph 조회보다 SQL 한 번이 표현과 성능 면에서 적합했습니다. 결과를 projection으로 매핑합니다.

**꼬리질문: 규모가 커지면요?**  
현재 on-demand query가 병목이 되면 settlement event로 ranking projection을 갱신하거나 Redis sorted set/materialized table을 사용하되 correction replay를 설계해야 합니다.

### Q23. 경기 일정의 중복 저장은 어떻게 막나요?

**답변:** `external_game_id` locked lookup을 우선하고 날짜/시각/홈/원정 natural key와 legacy candidate 순으로 기존 row를 찾습니다. DB에는 external id unique가 있습니다.

**꼬리질문: 완전히 안전한가요?**  
아닙니다. natural key는 index일 뿐 unique가 아니어서 external id가 없거나 바뀌는 예외에서 DB가 중복을 완전히 막지는 못합니다.

### Q24. live score를 보고 승패를 바로 만들지 않는 이유는 무엇인가요?

**답변:** 중단·우천·정정 등으로 display score가 최종 결과가 아닐 수 있기 때문입니다. parser가 공식 terminal 상태와 result 정보를 확인한 경우만 FINISHED result/winner를 확정합니다.

**꼬리질문: 이미 정산 후 공식 결과가 바뀌면요?**  
자동 correction하지 않고 관리자 rollback/correction/resettle 절차를 요구합니다.

### Q25. 팀 통계 수집에서 일부 팀만 성공하면 어떻게 되나요?

**답변:** 순위·타격·투구의 완전한 10팀 데이터를 확보해야 write합니다. `StandingService`도 최신 완전한 10팀 snapshot만 노출합니다. incomplete daily snapshot이 공식 순위처럼 보이는 것을 막기 위한 정책입니다.

### Q26. 외부 HTTP 요청을 transaction 안에서 하나요?

**답변:** bulk fetch와 parse는 transaction 밖에서 하고, write service가 game/team 단위의 짧은 `REQUIRES_NEW` transaction을 엽니다. 외부 응답 대기 동안 DB lock을 잡지 않습니다.

**꼬리질문: 단점은요?**  
하루 전체 sync가 원자적이지 않습니다. 일부 row만 성공할 수 있으므로 결과 summary와 재실행 가능성이 중요합니다.

### Q27. scheduler 중복 실행은 어떻게 막나요?

**답변:** 현재 `AtomicBoolean`로 한 JVM 내부 overlap을 막습니다. business write에는 lock/unique가 있지만 여러 backend instance의 scheduler 실행을 하나로 선출하지는 않습니다.

**꼬리질문: scale-out한다면요?**  
ShedLock with MySQL, DB lease, 또는 별도 worker/queue로 scheduler owner를 하나로 만들겠습니다.

### Q28. KBO 수집이 깨질 가능성이 높은 이유는 무엇인가요?

**답변:** 공식 웹의 HTML 또는 asmx 응답 안 HTML을 자체 regex/string parser로 해석하기 때문입니다. endpoint와 CSS/문자열 구조가 바뀌면 compile은 성공해도 parse가 실패할 수 있습니다.

**꼬리질문: 어떻게 감지하나요?**  
현재 fixture unit test는 있지만 live contract monitor/alert는 없습니다. 정기 sample fetch, minimum row count/schema contract, freshness metric과 alert를 추가하는 것이 좋습니다.

### Q29. 선발투수가 바뀌면 예측도 바뀌나요?

**답변:** V27과 `PredictionRefreshReason`을 통해 current prediction/snapshot에 starter player id를 저장하고, verification에서 identity 변경을 감지하면 stale prediction을 refresh합니다. 관련 migration, service, test는 commit `ad12ef1`에 함께 반영되어 있습니다.

**꼬리질문: GitHub에서도 확인 가능한가요?**  
현재 `main`과 `origin/main`에서 확인할 수 있습니다. 선발 최초 확보와 교체를 구분해 history reason을 남기며 경기 시작 전 stale prediction만 갱신합니다.

### Q30. 인증 방식은 JWT인가요?

**답변:** 아닙니다. `HttpSessionSecurityContextRepository`를 사용한 server-side session입니다. login 성공 후 기존 session을 invalidate하고 새 session에 SecurityContext를 명시적으로 저장합니다.

**꼬리질문: CSRF는 왜 필요한가요?**  
브라우저가 session cookie를 자동 전송하므로 공격 사이트의 요청도 cookie를 실을 수 있습니다. frontend가 별도 token cookie를 읽어 header로 보내도록 합니다.

### Q31. 비밀번호와 권한은 어떻게 처리하나요?

**답변:** local 가입 password는 BCrypt로 encode합니다. `KboUserDetailsService`가 ACTIVE user만 enabled로 반환하고 DB role 문자열을 `ROLE_` authority로 바꿉니다. `/api/admin/**`는 ADMIN만 접근합니다.

**꼬리질문: 보안상 빠진 것은요?**  
login rate limit/lockout, password reset, email verification, MFA는 구현되어 있지 않습니다. session store도 memory라 재시작 시 유지되지 않습니다.

### Q32. local migration에 test 계정이 있는데 운영 위험 아닌가요?

**답변:** V3가 알려진 local 계정을 넣지만 V11의 Flyway placeholder가 prod/test에서 제거합니다. local profile은 placeholder가 false라 개발 편의용으로 남습니다. 운영 노출을 막는 migration test도 있습니다.

**꼬리질문: 더 나은 방식은요?**  
공통 migration이 아니라 local-only seed mechanism이나 별도 dev data loader로 분리하면 의도가 더 명확합니다.

### Q33. Flyway를 왜 쓰고 Hibernate DDL update를 쓰지 않았나요?

**답변:** 운영 schema 변경 순서와 데이터 보정을 versioned SQL로 재현하기 위해서입니다. Hibernate는 `validate`만 하므로 entity-schema 불일치 시 시작을 실패시킵니다. `clean`도 disabled입니다.

**꼬리질문: V24~V26이 보여주는 것은요?**  
초기 schema 이후 core NOT NULL, FK, CHECK를 점진적으로 강화한 과정입니다. application validation만으로 부족한 invariant를 DB로 내렸습니다.

### Q34. 어떤 DB constraint가 핵심인가요?

**답변:** user-game prediction unique, game external id unique, game-settlement revision unique, prediction-type-revision ledger unique, reversal history unique, daily bonus unique가 멱등성 핵심입니다. FK와 point/score/odds CHECK도 있습니다.

**꼬리질문: 아직 없는 constraint는요?**  
system probabilities 범위와 합, status-result 조합, settlement count 합, game natural key unique는 없습니다.

### Q35. 커뮤니티는 단순 CRUD 외에 무엇이 있나요?

**답변:** soft delete, 한 단계 reply, reaction unique, report unique와 관리자 처리, pagination/popular post, write cooldown과 정규화 duplicate 차단이 있습니다. 동일 사용자의 동시 post/comment/reaction도 integration test합니다.

**꼬리질문: 신고 처리하면 게시물이 자동 삭제되나요?**  
아닙니다. report status 처리와 content moderation action이 자동 연결되지는 않습니다. admin은 일반 owner/admin delete policy로 삭제할 수 있지만 별도 제재 workflow는 없습니다.

### Q36. admin 작업은 감사 가능하게 남나요?

**답변:** `AdminOperationLoggingFilter`가 admin API의 method, path, status, elapsedMs를 application log에 남깁니다. 하지만 principal, payload, 변경 전후 상태를 DB audit table에 저장하지는 않습니다.

**꼬리질문: 정산 actor는요?**  
settlement에 source와 actor id scalar가 있지만 actor FK와 종합 audit log는 없습니다.

### Q37. 테스트에서 가장 강조할 만한 것은 무엇인가요?

**답변:** happy path 개수보다 failure mode를 검증한 점입니다. 동시 betting/정산, duplicate rollback, rollback partial failure atomicity, 3회 revision correction, ranking current revision, 미래 feature 누출, incomplete collection을 테스트합니다.

**꼬리질문: 전부 실제 MySQL인가요?**  
아닙니다. 일부 concurrency/integration은 H2 MySQL mode이고 application/Flyway 일부는 실제 MySQL profile입니다. CI는 MySQL 8.4를 띄웁니다. actual MySQL concurrency coverage 확대가 과제입니다.

### Q38. 현재 test 상태를 정확히 말해 보세요.

**답변:** 분석 환경에서 test source compile과 frontend production build는 성공했습니다. Gradle은 371개 중 361개가 통과했고 10개는 localhost MySQL 미실행 때문에 context가 뜨지 않았습니다. 프로젝트의 ML virtual environment에서는 Python test 8개가 모두 통과했습니다. 따라서 backend는 “모든 테스트 통과”라고 단정하지 않고 CI MySQL 환경의 green run을 제출 전에 확인해야 합니다.

### Q39. frontend test가 있나요?

**답변:** 없습니다. TypeScript type check와 Vite production build, Docker/Nginx config validation은 CI에 있지만 component/unit/E2E test는 미구현입니다.

**꼬리질문: 무엇부터 추가하겠나요?**  
가입/login CSRF, prediction deadline/잔액 오류, admin correction 순서, community 권한을 Playwright E2E로 먼저 묶겠습니다.

### Q40. 배포 파이프라인을 설명해 보세요.

**답변:** PR/main과 main push에서 MySQL 8.4 기반 backend test와 Node 22 frontend build를 병렬 수행합니다. main push이고 둘 다 성공하면 GitHub OIDC로 AWS role을 얻고 SSM command로 EC2의 source를 origin/main에 맞춘 뒤 Compose build/up과 backend/frontend health check를 합니다.

**꼬리질문: SSH key를 쓰나요?**  
workflow상 GitHub에서 직접 SSH하지 않고 OIDC+SSM을 사용합니다.

### Q41. 자동 rollback이 있나요?

**답변:** 없습니다. 배포 전에 현재 backend/frontend image를 `:rollback` tag로 보존하지만 health failure 시 자동 복원하는 command는 없습니다. 따라서 “rollback 준비”이지 “자동 rollback”은 아닙니다.

### Q42. 무중단 배포인가요?

**답변:** 그렇게 말할 근거가 없습니다. 단일 EC2에서 backend를 update하고 health 확인 후 frontend를 recreate합니다. blue/green이나 rolling replica가 없으므로 짧은 중단 가능성이 있습니다.

### Q43. 왜 AWS SSM을 선택했나요?

**답변:** workflow에 장기 SSH key를 두지 않고 IAM/OIDC 권한으로 대상 EC2에 명령을 전달할 수 있기 때문입니다. 명령 상태와 stdout/stderr도 polling합니다.

**꼬리질문: 현재 배포의 가장 큰 한계는요?**  
EC2에서 source를 다시 build해 CI 검증 artifact와 동일 digest가 아니고, 단일 host·container MySQL이라 HA와 backup 근거가 부족합니다.

### Q44. Nginx의 역할은 무엇인가요?

**답변:** React 정적 파일과 SPA fallback을 제공하고 `/api`를 backend로 reverse proxy하며 TLS termination, ACME challenge, asset cache, health endpoint를 담당합니다.

**꼬리질문: `/ws`는 사용하나요?**  
proxy 설정은 있지만 backend WebSocket 구현은 확인되지 않았습니다. 현재 사용 기능으로 소개하면 안 됩니다.

### Q45. 현재 가장 먼저 갚아야 할 기술부채는 무엇인가요?

**답변 예시:** point balance-ledger reconciliation과 alert를 우선하겠습니다. 돈은 아니지만 사용자 자산처럼 보이는 상태이고, 현재 transaction으로 정상 경로는 보호해도 운영 중 manual DB 변경이나 알려지지 않은 결함의 불일치를 탐지하지 못합니다. 그 다음은 KBO parser freshness/contract alert와 login rate limit입니다.

**꼬리질문: 왜 ML 정확도 개선보다 먼저인가요?**  
모델 성능은 제품 품질 요소지만 잔액 정합성과 데이터 수집 freshness가 깨지면 서비스 신뢰와 평가 데이터 자체가 무너집니다.

### Q46. 이 프로젝트에서 본인이 가장 자신 있게 설명할 수 있는 설계는 무엇인가요?

**답변 예시:** 정산 복구 설계입니다. official result correction을 overwrite로 처리하지 않고 game lock, settlement revision, original reward/refund reversal, prediction reset, corrected result snapshot, 재정산을 각각 검증 가능한 단계로 분리했습니다. unique constraint와 integration tests로 중복 취소·중복 지급·partial failure를 막았습니다.

**주의:** 실제 개발 과정에서 본인이 가장 깊게 작업한 부분에 맞춰 선택하되, 코드에 없는 장애 규모나 사용자 수를 만들어내지 않는다.

---

## 4. 압박 꼬리질문 대응

### “비관적 잠금을 썼으니 동시성 문제가 완전히 해결된 것 아닌가요?”

아니다. 애플리케이션 경로의 balance/odds/settlement race는 상당 부분 막았지만 same-game throughput, lock timeout/deadlock retry, multi-instance scheduler, direct DB write까지 해결한 것은 아니다. H2 concurrency test와 actual MySQL 차이도 있다.

### “정확도가 55.6%면 좋은 모델인가요?”

그 숫자만으로 결론 내릴 수 없다. 489경기 final test에서 always-home 49.3%, season-win-rate 52.6%, baseline 53.0%와 비교하면 개선은 있지만 draw 12건 recall이 0이고 calibration/live drift 검증이 부족하다. 그래서 shadow 상태가 타당하다.

### “운영 중인 AWS 구조를 직접 만들었다고 해도 되나요?”

repo로 확인 가능한 것은 GitHub OIDC, SSM, EC2의 Compose deploy, Nginx/Certbot 설정이다. 실제 AWS console resource 생성 방식이나 운영 장애 경험은 repository만으로 증명할 수 없으므로 본인이 수행한 범위만 말한다. RDS/ECS/ALB/IaC는 사용했다고 말하면 안 된다.

### “테스트가 371개면 품질이 충분한가요?”

개수보다 위험을 얼마나 검증하는지가 중요하다. 정산·동시성·security·migration은 강점이지만 frontend test, actual MySQL concurrency, full Compose E2E, external contract monitoring은 부족하다.

### “왜 event sourcing을 하지 않았나요?”

현재 규모에서는 current balance와 append-like point history를 같은 transaction에서 갱신하는 방식이 구현·조회 복잡도를 낮춘다. 다만 완전한 event sourcing이 아니어서 source-of-truth와 replay/reconciliation 정책을 더 명확히 해야 한다.

---

## 5. 코드 탐색 체크리스트

면접 전 다음 파일과 method를 직접 열어 설명할 수 있어야 한다.

1. `auth/controller/AuthController.java`: login/signup/session establishment
2. `common/config/SecurityConfig.java`: public/auth/admin/CSRF/CORS
3. `auth/service/SignupService.java`, `DailyLoginBonusService.java`
4. `prediction/service/UserPredictionService.java#createPrediction`
5. `point/service/UserPointLockService.java`, `PointService.java`
6. `prediction/service/GameOddsService.java`
7. `prediction/service/PredictionSettlementService.java#settleGame`
8. `prediction/service/GameSettlementRecoveryService.java`
9. `ranking/repository/RankingQueryRepository.java`
10. `game/collection/GameSyncService.java`, `GameUpsertService.java`
11. `stats/collection/PregameDataSyncScheduler.java`
12. `prediction/feature/PredictionFeatureService.java`
13. `prediction/engine/BaselinePredictionEngine.java`
14. `prediction/engine/LogisticRegressionPredictionEngine.java`
15. `prediction/generation/SystemPredictionGenerationService.java`
16. `prediction/history/SystemPredictionFinalizationService.java`
17. `backend/ml/logistic_pipeline.py`와 artifact/report
18. V14, V23, V24, V25, V26, V27 migration
19. `.github/workflows/deploy.yml`, `compose.yaml`, Nginx config
20. `PointSettlementConcurrencyIntegrationTest`, `SettlementRecoveryIntegrationTest`

---

## 6. 답변할 때 피해야 할 말

- “딥러닝/AI 모델을 운영한다.”
- “모든 데이터가 실시간이다.”
- “동시성을 완벽하게 해결했다.”
- “무중단 배포와 자동 rollback이다.”
- “AWS RDS/ECS/Kubernetes를 썼다.”
- “모든 선수 데이터를 수집한다.”
- “모든 테스트가 통과한다.”라고 로컬 실행 결과 확인 없이 단정한다.
- 코드에 없는 트래픽 수치, 장애, 성능 개선 %, 사용자 수를 만든다.

대신 이렇게 말한다.

- “운영은 규칙 기반 baseline이고 ML logistic model은 shadow 평가 중입니다.”
- “경기 상태는 5분, 선발 missing은 60초 polling입니다.”
- “비관적 잠금과 DB unique로 현재 application 경로의 중복/lost update를 막았고, hot-row와 multi-instance 한계가 있습니다.”
- “rollback image는 보존하지만 자동 전환은 아직 없습니다.”
- “정적 코드로 확인되는 AWS 범위는 EC2/SSM/OIDC입니다.”
