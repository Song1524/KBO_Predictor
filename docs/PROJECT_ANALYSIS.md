# KBO Predictor 코드 기반 프로젝트 분석

> 분석 기준일: 2026-09-21  
> 판단 우선순위: 현재 소스 코드와 Flyway migration → 빌드·테스트 설정 → 배포 설정 → 기존 문서  
> 범위: `backend`, `frontend`, `backend/ml`, `compose*.yaml`, `frontend/nginx*`, `.github/workflows`, `ops`, `backend/src/main/resources/db/migration`

## 0. 분석 전제와 결론 요약

이 문서는 README의 표현을 사실로 간주하지 않고 현재 코드와 DB migration을 따라 작성했다. 선발투수 identity 변경 감지, 변경 시 예측 갱신, `V27__prediction_starting_pitcher_identity.sql`은 commit `ad12ef1`에 함께 반영되어 있으며 현재 `main`과 `origin/main`에서 확인된다.

핵심 결론은 다음과 같다.

- 이 프로젝트는 공식 KBO 웹 데이터를 수집해 경기·팀 성적·선발투수를 동기화하고, 시스템 예측과 사용자 포인트 예측, 경기 종료 후 정산·랭킹, 커뮤니티를 제공하는 풀스택 서비스다.
- **현재 운영 기본 예측은 머신러닝이 아니라 사람이 정한 feature·weight와 sigmoid를 사용하는 통계/규칙 기반 `baseline-v1`이다.** 근거는 `application.yaml`의 `app.prediction.active-model: baseline-v1`과 `BaselinePredictionEngine`이다.
- 실제 scikit-learn 다항 로지스틱 회귀 `logistic-v1`도 학습·artifact 로딩·Java 추론·shadow 평가까지 구현되어 있으나 기본 운영 모델이 아니다. 이를 “운영 ML 예측”이라고 소개하면 과장이다.
- 포인트 차감, 배당 확정, 정산, 정산 취소·재정산은 단일 트랜잭션, 비관적 잠금, 유니크 제약, 원장(`point_histories`)을 조합해 비교적 깊게 구현되어 있다.
- Docker Compose, Nginx, HTTPS 인증서 갱신, GitHub Actions, AWS OIDC·SSM·EC2 배포 설정이 실제 파일로 존재한다. 다만 IaC, 자동 롤백, 다중 인스턴스용 분산 스케줄러 잠금은 없다.
- 분석 환경에서 backend test source 컴파일과 frontend production build는 성공했다. Java 전체 테스트는 371개 중 361개 통과, 10개는 로컬 MySQL 미실행으로 실패했다. 프로젝트의 ML virtual environment에서는 Python 테스트 8개가 모두 통과했다.

---

## 1. 프로젝트 한눈에 보기

### 서비스 설명

KBO Predictor는 야구 팬이 당일·예정 경기를 확인하고, 시스템이 계산한 승·무·패 확률과 팀/선발투수 데이터를 참고해 자신의 포인트를 걸어 결과를 예측하는 서비스다. 경기가 끝나면 공식 결과를 다시 수집해 예측을 자동 정산하고, 누적 포인트 또는 기간 수익으로 랭킹을 계산한다. 게시글·댓글·반응·신고가 있는 커뮤니티와 운영자가 수집·예측·정산을 점검하는 관리 화면도 포함한다.

### 목적과 해결하려는 문제

- 여러 KBO 공식 페이지에 흩어진 일정, 상태, 결과, 순위, 팀 타격/투구 성적, 선발투수 정보를 한 서비스에서 본다.
- 단순 응원 투표가 아니라 제한된 가상 포인트를 사용해 예측 선택에 무게를 부여한다.
- 경기 결과 반영부터 배당 확정, 보상, 이력, 랭킹까지 반복되는 운영 작업을 자동화한다.
- 운영 예측과 실험 모델을 같은 과거 데이터·시점 기준으로 비교할 수 있게 한다.

### 주요 사용자

- 일반 사용자: 경기와 데이터 조회, 회원가입·로그인, 포인트 예측, 내역·랭킹·커뮤니티 이용
- 관리자(`ROLE_ADMIN`): 데이터 수동 동기화, 예측 생성·백필·평가, 정산 상태 확인, 정산 취소·결과 수정·재정산, 신고 처리
- 스케줄러: 일정·상태·팀 성적·선발투수 동기화, 배당 마감, 예측 이력 finalization

### 핵심 기능

1. 공식 KBO 데이터 수집 및 일별 snapshot 저장
2. 규칙/통계 기반 `baseline-v1` 시스템 승부예측
3. 세션 인증과 CSRF 방어
4. 포인트 기반 사용자 승·무·패 예측과 실시간 pari-mutuel 배당
5. 종료/취소 경기 자동 정산, 관리자 rollback·결과 correction·재정산
6. 현재 포인트·주간/월간 손익 랭킹
7. 게시글·댓글·답글·반응·신고·도배 방지 커뮤니티
8. Docker/Nginx/HTTPS와 GitHub Actions→AWS SSM→EC2 배포

### 전체 서비스 흐름

```text
KBO 공식 웹
  ├─ 일정 HTML(JSON 응답 안의 HTML)
  ├─ GameCenter 경기 상태/결과
  ├─ 팀 순위·타격·투구 기록 HTML
  └─ 선발투수·투수 상세 기록
           │ scheduler 또는 관리자 요청
           ▼
Spring Boot collector/parser → MySQL snapshot/upsert
           │
           ├─ feature 생성 → baseline-v1 운영 예측 저장
           │                  └─ logistic-v1 shadow 예측/평가
           │
사용자 → React → Nginx → Spring MVC API
           ├─ 경기/통계/시스템 예측 조회
           ├─ 세션 로그인
           └─ 포인트 예측 → 포인트 차감 + 배당 pool 갱신
                                      │
공식 종료 결과 수집 ──────────────────┘
           ▼
게임 잠금 → 배당 확정 → 예측 판정 → 포인트 지급/환불
           → point history → settlement revision → 랭킹 집계
```

### 포트폴리오 소개 문장

> 공식 KBO 데이터를 주기적으로 수집해 경기·팀/선발투수 통계를 동기화하고, 규칙 기반 승부예측과 사용자 포인트 예측을 제공하는 풀스택 서비스입니다. 비관적 잠금과 원장 기반 포인트 처리, 멱등 정산·rollback/재정산, Flyway migration, Docker/Nginx/AWS 배포까지 경기 데이터의 전체 생명주기를 구현했습니다.

---

## 2. 실제 기술 스택

버전은 선언 범위가 아니라 가능하면 lockfile 또는 Gradle resolved dependency를 기준으로 적었다.

| 분류 | 실제 사용 기술 | 실제 사용 근거 | 상태 |
|---|---|---|---|
| Frontend | React 19.2.7, React DOM 19.2.7, TypeScript 5.9.3, Vite 7.3.6, React Router DOM 7.8.2 | `frontend/package-lock.json`, `frontend/src/main.tsx`, 라우트와 컴포넌트 | 운영 UI에서 사용 |
| UI/CSS | Tailwind CSS 4.3.3, Base UI 1.6.0, shadcn 4.13.1, lucide-react 1.25.0, CVA/clsx/tailwind-merge | `frontend/package.json`, `frontend/src` import, CSS | 사용 |
| Backend | Java 21, Spring Boot 4.1.0, Spring Framework 7.0.8, Spring MVC, Validation, Actuator | `backend/build.gradle`, resolved runtime classpath | 사용 |
| Database | MySQL 8.4 container, Connector/J 9.7.0, HikariCP 7.0.2 | `compose.yaml`, resolved dependencies | 운영 MySQL 사용 |
| ORM / Migration | Spring Data JPA 4.1.0, Hibernate 7.4.1.Final, Flyway 12.4.0 | build/resolved dependencies, entities, `db/migration/V1~V27` | 사용. `ddl-auto=validate` |
| Authentication / Security | Spring Security 7.1.0, server-side `HttpSession`, BCrypt, CSRF cookie/header, CORS, role authorization | `SecurityConfig`, `AuthController`, `KboUserDetailsService` | 사용. JWT 아님 |
| Data Collection | Java `HttpClient`, KBO 공식 웹 endpoint, 자체 HTML/JSON parser, Spring `@Scheduled` | `game/collection`, `stats/collection` | 사용. Jsoup/Selenium 없음 |
| Prediction | `baseline-v1` Java 규칙/통계 엔진; `baseline-v2` 실험/선택 가능; scikit-learn 1.7.2 logistic regression artifact를 Java softmax로 추론 | `prediction/engine`, `backend/ml`, `application.yaml` | baseline-v1 운영, logistic-v1 shadow |
| Python ML tooling | Python script, numpy 2.5.2, scipy 1.18.0, scikit-learn 1.7.2, joblib 1.5.3 | `backend/ml/requirements.txt` | 오프라인 학습·비교용; 서버 런타임에는 불필요 |
| Testing | JUnit 5, Spring Boot Test, Spring Security Test, MockMvc, Mockito, AssertJ, H2 MySQL mode, 실제 MySQL profile, Python unittest | `backend/src/test`, `backend/ml/tests` | backend 다층 테스트. frontend test 없음 |
| Infrastructure | Docker multi-stage build, Docker Compose, Nginx 1.27-alpine, MySQL 8.4, Certbot | Dockerfiles, `compose.yaml`, `frontend/nginx*`, `certbot`, `ops` | 사용 설정 존재 |
| Deployment / CI/CD | GitHub Actions, Java 21, Node 22, GitHub OIDC, AWS SSM, EC2, Docker Compose health checks | `.github/workflows/deploy.yml` | main push 자동 배포 |

### dependency 존재와 실제 사용의 구분

- H2는 단순 잔여 dependency가 아니다. `DailyLoginBonusIntegrationTest`, `PointSettlementConcurrencyIntegrationTest`, `SettlementRecoveryIntegrationTest`, 커뮤니티 통합 테스트 등이 MySQL compatibility mode로 사용한다.
- 테스트 기본 profile은 `application-test.yaml`의 실제 MySQL이다. `BackendApplicationTests`, Flyway migration 및 signup transaction 통합 테스트는 MySQL이 필요하므로 H2만으로 전체 suite가 끝나지 않는다.
- `spring-boot-devtools`는 `developmentOnly`이며 운영 image 역할이 아니다.
- npm과 pnpm lockfile이 둘 다 추적되지만 Docker와 CI의 표준 경로는 `package-lock.json` + `npm ci`다. `pnpm-lock.yaml`은 현재 배포 경로에서는 사용되지 않는다.
- Nginx에 `/ws/` reverse proxy가 있으나 backend에서 WebSocket endpoint/dependency를 찾지 못했다. 현재 기능이 아니라 남아 있는 인프라 설정이다.
- `SystemPrediction.homeScorePoint/awayScorePoint`와 frontend DTO 필드는 남아 있으나 현재 `baseline-v1` 저장 경로에서 값을 계산하지 않는다. 현재 확률 예측의 핵심 값으로 소개하면 안 된다.
- `KBO/KBO 승부예측 쿼리.txt`와 요구사항 spreadsheet는 초기 설계/기획 산출물이지 실행 schema가 아니다. 이 SQL에만 있는 chat, badge, notification, batter stats, lineup, simulation, materialized ranking, admin action log 등의 table은 Flyway migration에 없으므로 구현된 기능으로 세지 않았다.

---

## 3. 전체 아키텍처

```text
┌──────────────────── Client ────────────────────┐
│ Browser: React SPA + TypeScript + Tailwind     │
│ PLAYBALL_SESSION cookie / XSRF-TOKEN header    │
└───────────────────────┬────────────────────────┘
                        │ HTTPS :443
                        ▼
┌──────────── EC2 / Docker Compose host ────────────────┐
│ Nginx frontend container                              │
│  ├─ /, /assets → React static files + SPA fallback    │
│  ├─ /api → backend:8080                               │
│  ├─ /actuator/health → backend                        │
│  └─ ACME challenge / TLS certificate mount            │
│                         │                              │
│                         ▼                              │
│ Spring Boot backend container                         │
│  ├─ Controller → Service → JPA/JDBC Repository        │
│  ├─ Spring Security session + CSRF                    │
│  ├─ collection/prediction/odds/settlement schedulers  │
│  └─ Flyway V1~V27, Hibernate schema validation        │
│                         │                              │
│                         ▼                              │
│ MySQL 8.4 container + named volume                    │
└─────────────────────────┬──────────────────────────────┘
                          │ outbound HTTPS
                          ▼
             KBO official website endpoints
             ├─ Schedule.asmx/GetScheduleList
             ├─ Main.asmx/GetKboGameList
             ├─ TeamRank/TeamRankDaily.aspx
             ├─ Team/Hitter/Basic1.aspx
             ├─ Team/Pitcher/Basic1.aspx
             └─ Player/Pitcher/Basic.aspx

GitHub push(main)
 → GitHub Actions backend test + frontend build/Docker config validation
 → GitHub OIDC AWS credential
 → SSM command to EC2
 → git reset to origin/main
 → docker compose build/up
 → backend then frontend health check
```

중요한 경계는 다음과 같다.

- frontend는 backend host를 직접 알지 않고 same-origin `/api`만 호출한다 (`frontend/src/lib/api-client.ts`, Nginx 설정).
- backend는 외부 KBO 데이터를 동기식으로 가져오되, 대량 수집 중 DB transaction을 오래 잡지 않도록 개별 write service에 `REQUIRES_NEW`를 사용한다.
- 운영 예측과 shadow 예측은 동일 feature snapshot을 참조해 비교 가능성을 확보한다.
- 랭킹은 별도 ranking table이 아니라 현재 user balance, prediction, 활성 settlement, reward ledger를 JDBC CTE로 계산한다.

---

## 4. 핵심 도메인 분석

### 4.1 사용자·인증·포인트

- `users`: email/nickname unique, provider, BCrypt password, favorite team, 현재 point, role, status. `User.createLocal()`, `User.changePoint()`가 핵심 domain method다.
- `point_histories`: 모든 지급·차감·정산 취소를 기록하는 원장. `balance_after`, game, prediction, settlement revision, reversal link를 가진다.
- 관계: `User 1:N PointHistory`, `User N:1 favorite Team`, `User 1:N UserPrediction`.
- 현재 잔액은 빠른 조회를 위해 `users.point`에, 변경 근거는 `point_histories`에 함께 쓴다. event sourcing은 아니며 두 값의 자동 reconciliation job은 없다.

근거: `user/entity/User.java`, `point/entity/PointHistory.java`, `PointService`, V5/V14/V17/V23/V25/V26.

### 4.2 팀·경기·공식 데이터

- `teams`: KBO team code와 표시 정보. 10개 기준정보를 migration에서 넣고 수집 데이터의 team lookup 기준으로 사용한다.
- `games`: 날짜/시각/구장, 홈·원정·승자, 상태, score/result, KBO external id, prediction close time, 수집 시각을 저장한다.
- `team_stats`: 팀·시즌·기준일별 공식 순위/W-L-D/승률/GB/연속/최근10/홈·원정 성적, 팀 타율·ERA, 저장 경기에서 계산한 최근 5/10 경기 득실 지표 snapshot.
- `players`, `pitcher_stats`, `starting_pitchers`: 현재는 선발투수 식별·일별 투수 ERA/WHIP 등을 저장한다. `Player`가 모든 선수 유형을 포괄하는 것처럼 보이지만 실제 collector는 투수만 채운다.
- 관계: `Team 1:N Game(home/away/winner)`, `Team 1:N TeamStat`, `Game 1:N StartingPitcher`, `Player 1:N PitcherStat`, `StartingPitcher N:1 Player/Team/Game`.

근거: `game/entity/Game.java`, `stats/entity/*`, `player/entity/Player.java`, V1/V6/V7/V12/V24~V27.

### 4.3 시스템 예측

- `system_predictions`: 경기당 하나의 최신 운영 예측. 홈/무/원정 확률, predicted outcome/winner, model version, feature coverage, 설명과 feature 기준일을 저장한다.
- `prediction_feature_snapshots`: 예측에 사용한 feature 값을 시점별 불변 snapshot으로 저장한다.
- `system_prediction_histories`: `OPERATIONAL`/`BACKTEST`/`SHADOW`, `PROVISIONAL`/`FINAL`을 구분하는 이력. logistic artifact SHA도 기록한다.
- 관계: `Game 1:1 current SystemPrediction`, `Game 1:N FeatureSnapshot`, `Game 1:N PredictionHistory`, `PredictionHistory N:1 FeatureSnapshot`.

V27은 current prediction과 snapshot에 선발투수 KBO player id를 남겨 선발 변경 시 stale prediction을 재생성할 근거를 추가한다.

### 4.4 사용자 예측·배당·정산

- `user_predictions`: 사용자-경기 unique. 선택 outcome, stake point, settlement status, final odds, reward, settled time, settlement revision 연결을 가진다.
- `game_odds`: outcome별 누적 stake, 현재 odds, 마감 시 final odds를 저장한다. 사용자끼리 형성한 pool을 기반으로 하므로 bookmaker 고정 배당이 아니다.
- `game_settlements`: 경기별 revision의 정산 snapshot과 집계. `SETTLED`/`ROLLED_BACK`, automatic/admin source, 당시 결과와 처리 건수를 보존한다.
- 관계: `Game 1:N UserPrediction`, `Game 1:1 GameOdds`, `Game 1:N GameSettlement`, `GameSettlement 1:N UserPrediction/PointHistory`.

근거: `prediction/entity/{UserPrediction,GameOdds,GameSettlement}.java`, V2/V14/V16/V25/V26.

### 4.5 랭킹

랭킹 entity/table은 없다. `RankingQueryRepository`가 SQL CTE와 window function으로 다음을 계산한다.

- `TOTAL`: 활성 사용자의 현재 `users.point`; 동점 시 적중 수, user id
- `WEEKLY_PROFIT`, `MONTHLY_PROFIT`: 기간 안에 정산된 활성 settlement의 승/패 손익. 승리는 `reward - stake`, 패배는 `-stake`, 환불은 0
- 정확도 분모는 WON+LOST이며 refund는 제외
- 주간 기준은 Asia/Seoul 월요일, 월간은 달력 월

rollback된 settlement reward는 제외하고 현재 활성 revision만 집계한다.

### 4.6 Community

- post/comment/reply soft delete, pagination, 인기글
- post/comment reaction unique(user,target)
- post/comment report unique(reporter,target), 관리자 처리 상태
- 동일 사용자 write cooldown 및 정규화한 중복 content 차단
- 부모 댓글이 삭제되어도 활성 답글이 있으면 익명 placeholder를 유지

근거: `community/entity`, `CommunityService`, `CommunityReactionService`, `CommunityReportService`, V18~V22.

### 4.7 Admin

Admin 전용 entity는 없다. `users.role='ADMIN'`을 Spring Security의 `ROLE_ADMIN`으로 변환하고 `/api/admin/**`를 인가한다. 관리자 API 요청은 `AdminOperationLoggingFilter`가 method/path/status/elapsedMs를 application log에 남기지만, **누가 실행했는지나 request payload를 DB audit log로 남기지는 않는다.**

---

## 5. End-to-End 핵심 흐름

### A. 회원가입 / 로그인

#### 회원가입

```text
POST /api/auth/signup
 → AuthController.signup()
 → SignupService.signup() @Transactional
    1. email lower-case/trim, nickname 검증
    2. UserRepository duplicate precheck
    3. favorite TeamRepository 조회
    4. BCrypt password encode
    5. User.createLocal() → saveAndFlush
    6. PointService.grantSignupBonus() (MANDATORY)
       → users.point +1000
       → point_histories(SIGNUP_BONUS, balance_after)
 → AuthenticationManager로 방금 만든 계정 인증
 → 기존 session invalidate, 새 session 생성
 → SecurityContextRepository.saveContext()
```

`saveAndFlush`의 unique violation도 잡아 precheck와 insert 사이 race를 처리한다. 사용자 생성과 bonus history는 같은 transaction이므로 원장 저장이 실패하면 사용자도 rollback된다 (`SignupRollbackIntegrationTest`).

#### 로그인

```text
POST /api/auth/login + CSRF header
 → AuthController.login()
 → AuthenticationManager
 → DaoAuthenticationProvider
 → KboUserDetailsService.loadUserByUsername()
 → BCryptPasswordEncoder.matches()
 → DailyLoginBonusService.grantIfEligible() @Transactional
    → UserRepository.findByIdForUpdate(PESSIMISTIC_WRITE)
    → 같은 Asia/Seoul 날짜 bonus ledger 존재 확인
    → +50 / point history 저장
 → old session invalidate → new HttpSession
 → SecurityContext를 명시적으로 저장
```

인증은 JWT가 아니라 server-side session이다. cookie 이름은 `PLAYBALL_SESSION`; prod는 HttpOnly, Secure, SameSite=Lax, timeout 2h다. unsafe method는 `CookieCsrfTokenRepository`가 발급한 `XSRF-TOKEN`을 frontend `apiFetch()`가 `X-XSRF-TOKEN` header로 전송한다. 로그인 시 session 교체로 fixation을 방지한다. 근거: `SecurityConfig`, `AuthController`, `frontend/src/lib/api-client.ts`, `application-prod.yaml`.

### B. 경기 데이터 수집

#### 실행 주체

- 자동 일정 수집: `KboGameSyncScheduler`, 매일 06:00, 향후 7일
- 자동 상태 갱신: 같은 scheduler, 5분 fixed delay, 경기 60분 전부터
- 관리자: `POST /api/admin/data/games/sync?date=...`

#### 실제 흐름

```text
KboScheduleHttpClient
  POST /ws/Schedule.asmx/GetScheduleList
  form: leId=1, srIdList=0,9,6, seasonId, gameMonth
 → KboScheduleParser: JSON 내부 HTML을 일정 row로 변환

KboOfficialStartingPitcherHttpClient/OfficialGameResultSource
  POST /ws/Main.asmx/GetKboGameList
 → OfficialFinalScoreParser: 공식 상태·score·result 확인

KboGameDataCollector: schedule + GameCenter 정보 reconcile
 → GameSyncService.syncDate()
 → GameUpsertService.upsert() REQUIRES_NEW
 → games insert/update
 → terminal result이면 GameSettlementCoordinator
 → pending prediction은 PredictionSettlementService 자동 정산
```

#### 중복과 데이터 보호

- 우선 `external_game_id`로 locked lookup, 없으면 날짜/시각/홈/원정 natural key, 마지막으로 legacy candidate를 찾는다.
- DB unique는 `external_game_id`에 있고 natural key는 index만 있다. 정상 collector 경로는 lock/upsert로 중복을 줄이지만 자연키 자체의 DB unique 보장은 아니다.
- confirmed final result를 더 약한/미확정 응답으로 덮지 않는다.
- 상태가 과거 단계로 되돌아가는 update를 막는다.
- live score를 표시할 수는 있지만 공식 final 확인 전에는 승패 result를 만들지 않는다.
- 과거 월 schedule response만 JVM memory cache한다.
- bulk 전체가 하나의 transaction은 아니다. 경기별 `REQUIRES_NEW`라 한 건 실패가 다른 경기 commit을 되돌리지 않는다.

### C. 시스템 승부예측

```text
스케줄러의 pregame sync 또는 관리자 generate
 → SystemPredictionGenerationService.generate(gameId)
    1. Game 조회, SCHEDULED·마감 전인지 확인
    2. 기존 예측이 현재 model/feature보다 stale인지 확인
    3. PredictionFeatureService.build(game)
       - 경기 시작 전 수집된 최신 TeamStat
       - 선발 확정 시 경기 시작 전 PitcherStat
       - future leakage 방지
    4. ActivePredictionEngine
       - app.prediction.active-model에 따라 engine 선택
       - 현재 기본값 baseline-v1
    5. SystemPredictionWriter.write() REQUIRES_NEW
       - Game PESSIMISTIC_WRITE
       - current SystemPrediction upsert
       - FeatureSnapshot 저장
       - OPERATIONAL/PROVISIONAL history 저장
    6. ShadowPredictionService
       - 운영 모델이 logistic-v1이 아니면 같은 snapshot으로 logistic-v1 기록
```

마감 후 `SystemPredictionFinalizationScheduler`가 5초마다 provisional을 `FINAL` history로 확정한다. current row는 경기당 하나지만 history에 source/stage/model/artifact가 남아 운영 시점과 backtest/shadow를 구분한다.

**정확한 표현:** 운영 `baseline-v1`은 머신러닝 모델이 아니다. 사람이 정한 10개 feature weight, scale, 홈 이점, 무승부 범위, sigmoid 변환을 가진 deterministic baseline이다. scikit-learn 모델은 `logistic-v1`이며 현재 shadow다.

### D. 사용자 승부예측

```text
POST /api/user-predictions
body: gameId, predictedOutcome, pointAmount
 → UserPredictionController.create()
    - request의 userId를 받지 않고 authenticated principal 사용
 → UserPredictionService.createPrediction() @Transactional
    1. 100 이상, 100 단위, max/overflow 검증
    2. GameRepository.findByIdForUpdate(PESSIMISTIC_WRITE)
    3. UserPointLockService.lockUser(PESSIMISTIC_WRITE + refresh)
    4. SCHEDULED, predictionCloseAt 이전인지 검증
    5. user-game 기존 prediction 확인
    6. 잔액 확인
    7. UserPrediction saveAndFlush
       - unique(user_id, game_id) race를 conflict로 변환
    8. GameOddsService.placeBet()
       - 마감 여부 재검증, outcome pool 증가, odds 재계산
    9. PointService.useForPrediction() MANDATORY
       - User 잔액 차감
       - point_histories(PREDICTION_BET) 저장
```

이 전체는 하나의 outer transaction이다. prediction insert, odds pool, user balance, ledger 중 하나라도 실패하면 함께 rollback된다. lock 순서는 game → user로 고정되어 정산과 맞춘다. 단, 같은 경기의 모든 예측이 game row 하나에서 직렬화되므로 안전성 대신 높은 트래픽에서 병목이 될 수 있다.

### E. 경기 종료 및 정산

```text
공식 경기 결과 수집
 → GameUpsertService가 FINISHED/CANCELLED와 result 저장
 → GameSettlementCoordinator
 → PredictionSettlementService.settleGame() @Transactional
    1. Game PESSIMISTIC_WRITE
    2. score/result/winner 일관성 검증
    3. 최신 settlement revision/state 확인
    4. GameOdds final odds 확정
    5. PENDING predictions를 userId,id 순서로 조회
    6. GameSettlement revision 생성
    7. 예측별 처리
       - 취소: REFUNDED, user lock, stake 환불 + ledger
       - 적중: WON, user lock, floor(stake × finalOdds) 지급 + ledger
       - 실패: LOST, 지급 없음
    8. 처리 수 집계 후 settlement complete
```

배당은 outcome pool이 받은 전체 stake 비율을 기반으로 하고 max 10.00으로 제한한다. 적중 payout에는 원금 반환이 포함된 값이다. 정산은 한 transaction이므로 중간 사용자에서 실패하면 이전 지급도 함께 rollback된다.

#### 중복 정산과 correction

- game lock이 동시 정산을 직렬화한다.
- `game_settlements UNIQUE(game_id, revision)`과 `point_histories UNIQUE(prediction,type,settlement_revision)`가 DB backstop이다.
- 이미 active `SETTLED` revision이 있으면 재지급하지 않는다.
- 공식 결과가 정산 후 바뀌면 collector가 자동으로 덮어 재정산하지 않고 관리자 review 대상으로 남긴다.
- 관리자는 `rollback → result correction → resettle` 순서를 지켜야 한다.
- rollback은 원 지급 history를 `reversal_of_id`로 연결해 정확히 한 번 반전한다. 사용자가 이미 보상을 소비했다면 음수 balance를 허용한다(V23). 이것은 복구 가능성을 택한 명시적 정책이지만 사용자 경험·채권 정책은 별도 보완이 필요하다.

근거: `PredictionSettlementService`, `GameSettlementRecoveryService`, `PointService`, `SettlementRecoveryIntegrationTest`.

### F. 랭킹

`GET /api/rankings?type=...&limit=...` → `RankingController` → `RankingService` → `RankingQueryRepository` native JDBC SQL이다.

- total은 현재 balance를 사용한다.
- weekly/monthly는 `user_predictions.settled_at`이 기간에 속하고 current active `SETTLED` revision에 연결된 prediction만 사용한다.
- reward는 settlement revision과 연결된 point history에서 가져온다.
- rollback된 과거 revision을 제외하므로 correction 후 현재 정산 결과만 랭킹에 남는다.
- 캐시나 materialized ranking은 없으며 요청 시 계산한다.

### G. 관리자 기능과 상태

| 기능 | API/UI | 실제 상태 |
|---|---|---|
| 운영 dashboard 요약 | API+UI | 완료. 오늘 경기/상태/예측·정산·수집 시각, active model 표시 |
| 경기 수동 동기화 | API+UI | 완료 |
| 팀 통계 수동 동기화 | API+UI | 완료 |
| 선발투수 수동 동기화 | API+UI | 완료 |
| 날짜/경기 예측 생성 | API+UI | 완료 |
| 과거 backfill·baseline-v1 평가 | API+UI | 완료 |
| logistic shadow 기간 평가 | API+UI | 완료 |
| 경기별 모델 비교 | API+UI | 완료 |
| 정산 상태/수동 정산 | API+UI | 완료 |
| 정산 rollback·결과 correction·재정산 | API+UI | 완료. 순서/expected revision 검증 |
| 커뮤니티 신고 조회·처리 | API+UI | 부분 구현. 신고 status 처리까지; 처리와 게시물 자동 삭제/제재는 연결되지 않음 |
| historical dataset JSON/CSV/quality/multi-season evaluation | API only | 구현됐으나 frontend 미사용 |
| baseline-v2 train/global comparison | API only | 구현됐으나 frontend 미사용; 운영 default 아님 |
| 사용자/권한 관리 | 없음 | 미구현 |
| 영속 audit trail | application log only | 부분 구현. actor/payload DB 감사 이력 없음 |

---

## 6. 주요 API와 frontend 사용 여부

인증 표기의 `Public read`는 SecurityConfig가 익명 GET을 허용한다는 뜻이고, 변경 요청은 별도 표기 없으면 CSRF가 필요하다.

### 실제 frontend가 사용하는 API

| Method | URL | 기능 | 인증 | frontend |
|---|---|---|---|---|
| GET | `/api/auth/csrf` | CSRF token 발급 | Public | 사용 (`api-client.ts`) |
| POST | `/api/auth/signup` | 가입+초기 point+session | Public | 사용 |
| POST | `/api/auth/login` | session login+일일 bonus | Public | 사용 |
| GET | `/api/auth/me` | 현재 사용자 | User | 사용 |
| POST | `/api/auth/logout` | session invalidate | User | 사용 |
| GET | `/api/teams` | 팀 기준정보 | Public read | 사용 |
| GET | `/api/games?date=` | 날짜별 경기, 시스템 예측·배당 포함 | Public read | 사용 |
| GET | `/api/games/{id}` | 경기 상세, 시스템 예측·배당 포함 | Public read | 사용 |
| GET | `/api/games/{id}/starting-pitchers` | 선발투수 | Public read | 사용 |
| GET | `/api/teams/{id}/stats/latest` | 팀 최신 통계 | Public read | 사용 |
| GET | `/api/standings` | 최신 완전한 10팀 공식 순위 snapshot | Public read | 사용 |
| POST | `/api/user-predictions` | point 예측 생성 | User | 사용 |
| GET | `/api/user-predictions/me` | 내 예측 이력 | User | 사용 |
| GET | `/api/points/me/history` | 내 point 원장 | User | 사용 |
| GET | `/api/rankings?type=&limit=` | 전체/주간/월간 랭킹 | Public read, 로그인 시 my rank 포함 | 사용 |
| GET | `/api/community/popular-posts` | 인기글 | Public read | 사용 |
| GET | `/api/community/posts` | 게시글 pagination | Public read | 사용 |
| GET | `/api/community/posts/{id}` | 상세/view 증가 | Public read | 사용 |
| POST/PUT/DELETE | `/api/community/posts[/{id}]` | 작성/수정/soft delete | User | 사용 |
| GET/POST | `/api/community/posts/{id}/comments` | 댓글·답글 조회/작성 | GET public, POST User | 사용 |
| PUT/DELETE | `/api/community/comments/{id}` | 댓글 수정/soft delete | User/owner policy | 사용 |
| PUT | `/api/community/posts/{id}/reaction` | post reaction toggle | User | 사용 |
| PUT | `/api/community/comments/{id}/reaction` | comment reaction toggle | User | 사용 |
| POST | `/api/community/posts/{id}/reports` | post 신고 | User | 사용 |
| POST | `/api/community/comments/{id}/reports` | comment 신고 | User | 사용 |
| GET | `/api/admin/dashboard/summary` | 운영 요약 | Admin | 사용 |
| POST | `/api/admin/data/games/sync` | 경기 수동 수집 | Admin | 사용 |
| POST | `/api/admin/data/team-stats/sync` | 팀 성적 수집 | Admin | 사용 |
| POST | `/api/admin/data/starting-pitchers/sync` | 선발 수집 | Admin | 사용 |
| POST | `/api/admin/predictions/generate` | 날짜/경기 예측 생성 | Admin | 사용 |
| POST | `/api/admin/predictions/backfill` | historical backfill | Admin | 사용 |
| GET | `/api/admin/predictions/evaluation` | backtest 평가 | Admin | 사용 |
| GET | `/api/admin/predictions/shadow/evaluation` | shadow paired 평가 | Admin | 사용 |
| GET | `/api/admin/predictions/models/comparison/{gameId}` | 한 경기 모델 비교 | Admin | 사용 |
| GET | `/api/admin/games/{id}/settlement/status` | 정산 상태 | Admin | 사용 |
| POST | `/api/admin/games/{id}/settlement` | 정산/재정산 | Admin | 사용 |
| POST | `/api/admin/games/{id}/settlement/rollback` | 최신 정산 취소 | Admin | 사용 |
| PUT | `/api/admin/games/{id}/result` | rollback 이후 결과 수정 | Admin | 사용 |
| GET/PATCH | `/api/admin/community/reports[...]` | 신고 조회/처리 | Admin | 사용 |

### backend에는 있으나 frontend가 직접 사용하지 않는 API

| Method | URL | 상태/비고 |
|---|---|---|
| GET | `/api/games/{id}/prediction` | 구현·public. frontend는 GameResponse에 내장된 `aiPrediction` 사용 |
| GET | `/api/games/{id}/odds` | 구현·public. frontend는 GameResponse의 `userOdds` 사용. 조회가 row 생성/확정을 할 수 있어 순수 GET도 아님 |
| GET | `/api/admin/predictions/dataset` | historical ML dataset JSON, API only |
| GET | `/api/admin/predictions/dataset/csv` | CSV export, API only |
| GET | `/api/admin/predictions/dataset/quality` | 품질 검사, API only |
| GET | `/api/admin/predictions/dataset/evaluation` | multi-season baseline 평가, API only |
| POST | `/api/admin/predictions/models/baseline-v2/train` | Java random search training, API only |
| GET | `/api/admin/predictions/models/comparison` | global model 비교, API only |

---

## 7. 데이터 수집 구조

| 데이터 | 원천/방식 | parser/service | 저장 위치 | 실행 |
|---|---|---|---|---|
| 경기 일정 | `Schedule.asmx/GetScheduleList` POST, JSON 안 HTML | `KboScheduleHttpClient`, `KboScheduleParser` | `games` | 매일 06:00, 관리자 |
| 경기 상태/score/결과 | `Main.asmx/GetKboGameList` POST | `OfficialFinalScoreParser`, `KboGameDataCollector` | `games` | 5분, 관리자/일정 sync |
| 팀 정보 | migration의 10개 reference team + KBO code mapping | `TeamRepository` | `teams` | migration/bootstrap |
| 공식 순위 | `TeamRankDaily.aspx` HTML | `KboOfficialTeamStatsHttpClient`, `OfficialTeamStatsParser` | `team_stats` | 매일 06:20, 관리자 |
| 팀 성적 | 순위 페이지의 W-L-D/승률/GB/streak/recent/home-away | 같은 parser | `team_stats` | 동일 |
| 팀 타격 | `Team/Hitter/Basic1.aspx` | 같은 parser | `team_stats.batting_average` | 동일 |
| 팀 투구 | `Team/Pitcher/Basic1.aspx` | 같은 parser | `team_stats.era` | 동일 |
| 최근 득실 form | 이미 저장된 이전 FINISHED game | `TeamRecentFormCalculator` | `team_stats` recent5/10 fields | 팀 snapshot write 시 |
| 선발투수 | `Main.asmx/GetKboGameList` | `OfficialStartingPitcherParser` | `starting_pitchers`, `players` | 당일 60초 missing poll, 10분 verify, 미래 1시간 |
| 선발 투수 기록 | `Player/Pitcher/Basic.aspx?playerId=` | pitcher detail parser | `pitcher_stats` | 선발 수집 시 |
| 개별 타자 기록 | collector 없음 | 없음 | 없음 | 미구현 |

수집기는 Java 정규식/문자열 parser로 공식 HTML 구조에 의존한다. 팀 통계는 10팀 순위·타격·투구 자료가 모두 모일 때만 write해 incomplete snapshot 노출을 막는다. 선발투수는 아직 발표되지 않은 게임만 60초 polling하고, 완성된 게임도 10분마다 재검증해 변경을 잡는다. 선발 identity가 바뀌면 시스템 예측도 refresh한다.

Scheduler의 `AtomicBoolean`은 한 JVM 안의 중복 실행만 막는다. backend를 여러 인스턴스로 늘리면 각 인스턴스가 같은 수집을 실행하므로 ShedLock/DB lease 같은 분산 잠금이 필요하다. DB upsert와 unique constraint가 일부 중복을 막지만 외부 호출·로그·부분 작업까지 단일 실행을 보장하지는 않는다.

---

## 8. 승부예측 로직 상세

### 8.1 운영 모델 판정

`application.yaml`:

```yaml
app:
  prediction:
    active-model: baseline-v1
```

`ActivePredictionEngine`은 이 값으로 engine을 고른다. 따라서 현 운영 설명은 다음이어야 한다.

> 팀 시즌/최근 성적, 득실, 구장별 성적, 팀 타율·ERA, 선발 ERA·WHIP을 가중 결합해 sigmoid로 확률화하는 규칙 기반 통계 baseline을 운영하고, 별도로 학습한 logistic regression을 shadow mode로 비교 중이다.

### 8.2 baseline-v1 입력 feature와 weight

| feature | 방향 | weight | 정규화 scale |
|---|---|---:|---:|
| 시즌 승률 차 | 높을수록 유리 | 0.18 | 0.25 |
| 최근 5경기 승률 차 | 높을수록 유리 | 0.10 | 0.25 |
| 최근 10경기 승률 차 | 높을수록 유리 | 0.07 | 0.25 |
| 최근 5경기 평균 득실 차 | 높을수록 유리 | 0.12 | 4.0 |
| 최근 10경기 평균 득실 차 | 높을수록 유리 | 0.08 | 4.0 |
| 홈/원정 venue 승률 차 | 높을수록 유리 | 0.10 | 0.25 |
| 팀 타율 차 | 높을수록 유리 | 0.10 | 0.040 |
| 팀 ERA 차 | 낮을수록 유리 | 0.10 | 2.50 |
| 선발 ERA 차 | 낮을수록 유리 | 0.10 | 2.50 |
| 선발 WHIP 차 | 낮을수록 유리 | 0.05 | 0.50 |

총 weight는 1.0이다. 각 차이를 scale로 나누고 `[-1,1]`로 clamp한다. 낮을수록 좋은 ERA/WHIP은 부호를 반대로 적용한다. feature가 없으면 0으로 넣는 대신 해당 factor를 available weight에서 제외한다.

### 8.3 strength와 확률

개념적으로 다음과 같다.

```text
raw = Σ(weight_i × normalized_difference_i) / Σ(available_weight_i)
coverageReliability = min(1, availableWeight / 0.50)
strength = clamp(raw × coverageReliability + homeAdvantage(0.04))

drawP = 0.05 + (0.12 - 0.05) × (1 - abs(strength))
decisiveP = 1 - drawP
homeShare = sigmoid(1.80 × strength)
homeP = decisiveP × homeShare
awayP = 1 - drawP - homeP
```

최종 percentage rounding의 잔차는 away에 둔다. predicted outcome은 세 확률의 최댓값이며, 설명은 절대 기여도가 큰 factor 최대 4개로 만든다. `featureCoverage`는 사용 가능한 weight 합이다. 근거: `BaselinePredictionEngine`, `BaselineV1ModelProperties`.

이 방식은 sigmoid를 사용하지만 parameter를 데이터로 학습하지 않았으므로 logistic regression/ML이라고 부를 수 없다.

### 8.4 feature 시점과 leakage 방지

`PredictionFeatureService`는 경기 시작 전 `collectedAt`인 최신 snapshot만 고르고, pitcher stat도 해당 일자·수집 시각을 제한한다. 과거 backfill은 `HistoricalPredictionFeatureBuilder`가 대상 경기 이전 경기만 사용해 최근 form을 다시 만든다. current operational feature와 historical backtest feature를 별도 경로로 둔 이유는 미래 데이터 누출을 막기 위해서다.

### 8.5 model/version/history 관리

- current: `system_predictions.model_version`
- immutable input: `prediction_feature_snapshots`
- 평가/비교 history: `system_prediction_histories`의 source, stage, model version, artifact SHA
- `PROVISIONAL`은 마감 전 갱신 가능, `FINAL`은 당시 비교 기준
- current model 변경, feature date 개선, coverage 증가, 선발 player identity 변경 시 stale refresh
- 동일 game/snapshot/generation method/history key에 unique constraint가 있어 재실행 중복을 제한

### 8.6 baseline-v2

`baseline-v2`는 6개 팀 form feature의 weight·home advantage·scale 등을 seeded random search로 최적화한다. `BaselineV2TrainingService`와 `BaselineV2ParameterOptimizer`가 있고 admin train/comparison API도 있다. 그러나 scikit-learn estimator가 아니라 **학습 기간에 맞춘 parameter search 기반 baseline**이며, `active-model` 기본값도 아니다. 운영 extra feature option은 false다.

### 8.7 logistic-v1: 실제 ML, 현재 shadow

오프라인 `backend/ml/logistic_pipeline.py`는 다음을 수행한다.

- estimator: scikit-learn `LogisticRegression`, multinomial softmax, `lbfgs`
- feature 6개: 시즌 승률 차, 최근 5/10 승률 차, 최근 5/10 득실 차, 홈/원정 승률 차
- preprocessing: median imputer + standard scaler
- model selection: 2023~2024 train, 2025 validation; Log Loss → Brier → Accuracy 순
- selected: `C=0.1`, class weight 없음
- final training: 2023~2025, 2,145경기
- untouched final test: 2026-03-29~2026-08-01, 489경기
- JSON artifact에 imputer/scaler/coefficients/intercepts/class 순서를 기록
- build가 artifact를 classpath로 복사하고, `LogisticModelArtifactLoader`가 SHA-256을 검증
- Java `LogisticRegressionPredictionEngine`이 같은 preprocessing과 softmax 추론 수행

현재 baseline-v1이 운영이면 `ShadowPredictionService`가 동일 feature snapshot으로 logistic-v1을 기록한다. `ShadowEvaluationService`는 동일 경기·FINAL·artifact hash·경기 전 생성 조건을 맞춘 paired sample만 비교한다.

### 8.8 기록된 2026 final test 결과

| 모델 | Accuracy | Log Loss | Brier | Macro F1 | Draw recall |
|---|---:|---:|---:|---:|---:|
| always-home | 49.28% | 0.7921 | 0.5238 | 0.2201 | 0 |
| season-win-rate | 52.56% | 0.8239 | 0.5416 | 0.3547 | 0 |
| baseline-v1 | 52.97% | 0.8904 | 0.5731 | 0.3572 | 0 |
| logistic-v1 | 55.62% | 0.7883 | 0.5196 | 0.3640 | 0 |

이 수치는 `backend/ml/artifacts/logistic-v1-report.json`의 고정 평가 artifact이며 live 운영 정확도가 아니다. final test의 draw는 12/489에 불과하고 모든 모델 draw recall이 0이므로 “무승부까지 잘 맞추는 모델”로 설명할 수 없다.

### 8.9 평가 방식

Java `PredictionEvaluationService`는 FINISHED game과 `BACKTEST/FINAL` history를 결합해 다음을 계산한다.

- 대상/feature 생성/evaluable/누락 수
- accuracy와 outcome별 accuracy
- 평균 feature coverage, starter 포함/팀-only 수
- multiclass log loss와 Brier score
- always-home, higher-season-win-rate benchmark

Python report는 여기에 macro F1, per-class precision/recall/F1, confusion matrix까지 제공한다. 운영 shadow 평가는 artifact hash가 같은 paired sample로 제한한다.

### 8.10 현재 한계와 ML 발전 구조

현재 한계:

- baseline-v1 weight는 hand-tuned라 데이터 기반 최적이라는 보장이 없다.
- KBO 웹 수집 snapshot 품질과 선발 발표 시점에 feature availability가 좌우된다.
- logistic-v1은 팀 form 6개만 사용하며 선발/팀 타격·ERA feature를 쓰지 않는다.
- 무승부 class가 매우 적어 세 모델 모두 draw를 한 번도 예측하지 못했다.
- 시즌·구장·상대전적·lineup·불펜 workload·부상·weather 등은 없다.
- offline artifact의 성능과 live shadow drift를 자동 모니터링·승격하는 pipeline은 없다.

발전 방향:

```text
immutable point-in-time feature store
 → season-aware train/validation/test split 또는 walk-forward validation
 → class imbalance 및 calibration 평가
 → candidate artifact registry(version, SHA, dataset window, metrics)
 → shadow deployment
 → paired live evaluation + minimum sample gate
 → 관리자 승인/canary
 → active-model 승격과 즉시 rollback
```

새 feature는 “경기 시작 전에 실제로 알 수 있었는가”를 snapshot에 증명해야 한다. 모델 종류보다 먼저 point-in-time correctness, calibration, draw 정책, artifact reproducibility를 강화하는 것이 적절하다.

---

## 9. 트랜잭션 / 동시성 / 데이터 정합성

### 9.1 항목별 현재 처리

| 항목 | 코드상 처리 | DB 방어 | 판단 |
|---|---|---|---|
| 포인트 차감 | game lock → user `PESSIMISTIC_WRITE` → balance/overflow 검증 → prediction/odds/ledger를 한 transaction | prediction unique, point amount CHECK, history FK/CHECK | 해결 수준 높음 |
| 포인트 지급/환불 | 정산 transaction 안에서 user lock 후 `User.changePoint`와 history 동시 저장 | settlement revision unique, history unique | 해결 수준 높음 |
| 경기 정산 | game `PESSIMISTIC_WRITE`, 결과 일관성 확인, 모든 prediction 일괄 transaction | settlement `(game,revision)` unique | 해결 수준 높음 |
| 중복 정산 | latest active settlement 거부, game lock | settlement/history unique | 해결됨 |
| 중복 예측 | application 사전 확인 + `saveAndFlush` race handling | `UNIQUE(user_id,game_id)` | 해결됨 |
| 동시 여러 경기/같은 user | 각 작업에서 user lock으로 balance update 직렬화 | transaction | integration test 존재 |
| 같은 경기 동시 예측 | game row lock으로 odds pool과 prediction 직렬화 | unique/check | 안전하나 병목 가능 |
| 정산 rollback | latest revision만, original history를 reversal link로 1회 반전 | `UNIQUE(reversal_of_id)` | 해결 수준 높음 |
| 일일 login bonus | user lock, 날짜별 존재 확인 | `UNIQUE(user,bonus_date,type)` | 해결됨 |
| optimistic lock | `@Version` 사용 없음 | 없음 | 현재는 pessimistic 전략 |
| FK | V25에서 핵심 domain FK 추가 | MySQL FK | 대부분 존재 |
| CHECK | V5/V23/V26에서 amount, score, odds, ledger 정책 | MySQL CHECK | 핵심 일부 존재 |
| NOT NULL | V24에서 core columns 강화 | DB NOT NULL | 핵심 다수 존재 |
| migration | Flyway V1~V27, validate, clean disabled, Hibernate validate | schema history | 사용 |

### 9.2 transaction 경계

- `SignupService.signup`: user + signup bonus atomic
- `UserPredictionService.createPrediction`: prediction + odds + point deduction + ledger atomic
- `PredictionSettlementService.settleGame`: 한 경기 전체 정산 atomic
- `GameSettlementRecoveryService`: rollback 또는 correction 각각 atomic
- 수집: network fetch와 bulk parsing은 transaction 밖, individual game/stat write는 `REQUIRES_NEW`
- `PointService`의 변경 method는 `MANDATORY`여서 독립 호출로 balance만 바뀌는 것을 막는다.

### 9.3 잠금 순서와 deadlock 관점

사용자 예측과 정산 모두 game을 먼저 잠그고 필요한 user를 다음에 잠근다. 정산은 pending prediction을 user id 순으로 처리한다. 이 고정 순서는 교착 가능성을 낮춘다. 서로 다른 경기에서 같은 user를 동시에 처리하면 user lock에서 순차화되며 `PointSettlementConcurrencyIntegrationTest`가 다음을 검증한다.

- 예측 차감과 다른 경기 정산 보상 동시 반영
- 서로 다른 경기의 동시 정산 보상 모두 반영
- 취소 환불과 다른 경기 예측 차감 동시 반영

단, 테스트는 H2 MySQL mode다. 실제 MySQL의 lock wait/deadlock/격리수준 특성을 동일하게 보장한다고 단정할 수 없으므로 MySQL Testcontainers 또는 CI MySQL 기반 동시성 test가 추가로 필요하다.

### 9.4 해결된 문제

- application check만 믿지 않고 unique constraint를 backstop으로 사용
- user balance의 lost update를 pessimistic lock으로 방지
- 이미 persistence context에 들어온 User를 lock 뒤 refresh해 stale balance 사용 방지
- 정산 revision과 reversal link로 correction history 보존
- rollback 중 일부 사용자만 반영되는 partial commit 방지
- official final과 score/result/winner 일관성 검증
- Flyway + `ddl-auto=validate`로 운영 schema drift 조기 감지

### 9.5 아직 위험하거나 정책 결정이 필요한 부분

- `users.point`와 원장 마지막 `balance_after`의 불일치를 탐지·복구하는 reconciliation job이 없다.
- V23 정책상 rollback 후 음수 balance가 가능하다. 데이터 복구에는 유리하지만 이후 betting 차단, 사용자 안내, 상환 정책을 명확히 해야 한다.
- `games` natural key가 unique가 아니므로 external id가 누락·변경되는 예외에서 중복 경기 가능성을 DB가 완전히 막지 못한다.
- system probability 범위/합계, settlement aggregate 합, game status-result 조합 등은 DB CHECK가 없다.
- `game_settlements`의 admin actor id는 FK가 아닌 scalar라 삭제/오입력에 대한 referential integrity가 없다.
- multi-instance scheduler lock이 없고, `AtomicBoolean`은 JVM-local이다.
- 모든 same-game betting이 game row 하나에서 직렬화되어 규모가 커지면 throughput이 제한된다.
- lock timeout/deadlock retry 정책이 명시적으로 구현되지 않았다.

---

## 10. 배포 구조

### Docker / Compose

- backend Dockerfile: `eclipse-temurin:21-jdk-alpine` build → `21-jre-alpine`, non-root `playball`, port 8080
- frontend Dockerfile: `node:22-alpine`에서 `npm ci && npm run build` → `nginx:1.27-alpine`
- DB: `mysql:8.4`, named volume, health check
- backend는 host port를 publish하지 않고 Compose network에 expose만 한다.
- frontend만 80/443을 publish한다.
- `compose.local.yaml`은 local backend profile/host port override다.

### Nginx / HTTPS

- `/api/`와 health는 backend로 proxy
- `/assets`는 1년 immutable cache
- SPA route는 `/index.html` fallback
- 인증서가 없을 때 HTTP bootstrap config, 인증서가 생기면 HTTPS config 선택
- domain과 certificate path는 `playball.ai.kr` 기준으로 hard-coded
- Certbot snap/systemd timer와 `ops/reload-frontend-nginx.sh` deploy hook으로 갱신 후 Nginx reload

### Spring profile / 환경 변수

- default `local`: local MySQL, SQL debug, session 8h, HTTP cookie
- `prod`: `DB_URL/DB_USERNAME/DB_PASSWORD`, explicit CORS origin, secure session cookie, 2h, forwarded headers
- `test`: test MySQL default, scheduler disabled, legacy test user 제거
- Compose `.env`: MySQL root/app password, DB URL, frontend origin, certificate host path 등
- secret 값 자체는 repo의 `.env.example`에 들어 있지 않다.

### Git push 이후 운영 반영

```text
push main
 → backend-test job
    MySQL 8.4 service → Java 21 → ./gradlew test
 → frontend-build job
    Node 22 → npm ci → npm build
    Compose config + frontend Docker/Nginx bootstrap validation
 → 두 job 성공 시 deploy
 → GitHub OIDC로 AWS IAM role assume
 → SSM SendCommand로 지정 EC2 실행
 → /home/ubuntu/KBO_Predictor
 → 2GiB free space 확인
 → 현재 backend/frontend image를 :rollback tag로 보존
 → git fetch + git reset --hard origin/main
 → docker compose build backend frontend
 → db/backend up → backend actuator health
 → frontend recreate → /healthz
 → builder cache/dangling image 정리
 → SSM 결과 polling 후 workflow 성공/실패
```

### 정확히 말할 수 있는 범위

- AWS 사용은 설정으로 확인된다: GitHub OIDC, IAM role, SSM, EC2.
- ECS/EKS, RDS, ALB, Route53, Terraform/CloudFormation은 코드에서 확인되지 않는다. MySQL은 Compose container다.
- rollback image를 보존하지만 실패 시 자동으로 그 image로 되돌리는 command는 없다. “자동 롤백”이라고 하면 안 된다.
- 서버에서 source를 다시 받아 image를 build한다. registry에 CI 산출물을 push해 동일 artifact를 배포하는 구조는 아니다.

---

## 11. 테스트 분석

### 11.1 구성

현재 `backend/src/test/java`에는 `@Test`를 가진 84개 class, 346개의 직접 `@Test` 선언이 있다. parameterized/dynamic execution을 포함해 실제 Gradle report는 371 tests다. Python은 3개 test file, 8개 test method다. frontend test file은 없다.

| 종류 | 대표 검증 |
|---|---|
| Unit Test | parser, calculator, prediction engines, odds, service validation, scheduler branch, ranking period |
| Controller/Web | auth/session/CSRF, public/admin authorization, request validation, API response |
| Repository | pitcher stat 시점 조회, ranking native query |
| Integration | signup atomicity, daily login bonus concurrency, community CRUD/reaction/report/rate limit |
| Settlement | win/loss/draw/cancel, duplicate settlement, rollback/correction/resettle, multiple revisions, ranking linkage |
| Concurrency | concurrent bet/reward/refund, duplicate rollback, concurrent bonus/write/reaction |
| Collection | KBO fixture parsing, status reconciliation, schedule cache, team snapshot, starter polling/update |
| Prediction | feature leakage, baseline-v1/v2, logistic artifact SHA/Java inference, generation/finalization/backfill/evaluation/shadow |
| Migration/application | Flyway pending/idempotency, legacy test account removal, security/CORS/actuator |
| Python model | preprocessing, artifact inference parity, model comparison utilities |

### 11.2 DB 전략

- 작은 integration/concurrency test 일부는 H2 `MODE=MySQL`을 test class에서 명시한다.
- application/Flyway/signup 일부는 `application-test.yaml`의 MySQL을 사용한다.
- CI는 MySQL 8.4 service를 띄우므로 전체 suite의 의도된 실행 환경은 MySQL이다.
- H2와 MySQL의 DDL, lock, isolation 차이를 감안하면 핵심 concurrency test도 실제 MySQL로 한 번 더 실행하는 편이 좋다.

### 11.3 이번 분석에서 수행한 검증

| 명령/대상 | 결과 |
|---|---|
| `gradlew testClasses` | 성공 |
| frontend `tsc --noEmit && vite build`에 해당하는 production build | 성공, 1,875 modules |
| `gradlew test` | 371개 중 361 통과, 10 실패 |
| 실패 원인 | localhost:3306 MySQL 미실행으로 context/Flyway connection 실패 |
| H2 정산/동시성/커뮤니티 tests | 통과 |
| Python unittest | `backend/ml/.venv`의 scikit-learn 1.7.2 환경에서 8개 통과 |

Java 실패 10개는 `BackendApplicationTests` 7개, `PredictionCloseMigrationIntegrationTest` 1개, signup transaction integration 2개다. 실행 환경 문제와 test assertion failure를 구분해야 한다.

### 11.4 아직 부족한 테스트

- React component/unit/E2E/accessibility/browser test 없음
- 실제 운영 KBO HTML 변경을 조기에 알리는 scheduled contract test 없음
- Docker Compose 전체 E2E와 HTTPS smoke test 없음
- actual MySQL에서 same-game 고부하/lock timeout/deadlock test 부족
- GitHub deploy 실패 후 rollback procedure test 없음
- 사용자 잔액-원장 reconciliation property test 없음
- 실제 live shadow 성능 drift/최소 sample promotion gate test 없음

---

## 12. 구현 상태

### 완료

- session 기반 회원가입/로그인/로그아웃, CSRF/CORS/role security
- signup bonus, 일일 login bonus와 point ledger
- KBO 일정·상태·공식 결과 수집과 game upsert
- 10팀 공식 standings/팀 타율·ERA/최근 form snapshot
- 선발투수와 투수 stats 수집·표시
- 선발 identity 변경 감지와 경기 시작 전 stale prediction refresh
- baseline-v1 current prediction, feature snapshot, provisional/final history
- 사용자 prediction, odds pool/final odds, point 차감
- 종료·취소 자동 정산과 admin 수동 정산
- rollback→correction→resettlement와 revision history
- total/weekly/monthly ranking
- community post/comment/reply/reaction/report/rate-limit/popular
- admin dashboard와 주요 운영 action UI
- Flyway, Docker Compose, Nginx HTTPS, GitHub Actions AWS EC2 배포

### 부분 구현

- 실제 ML 운영 전환: logistic-v1 학습·artifact·Java inference·shadow 평가는 완료됐으나 active default로 승격되지 않음
- baseline-v2: train/comparison/API는 있으나 운영 default/일반 UI 노출 아님
- 신고 moderation: status 처리만 있고 content 삭제/사용자 제재 workflow와 자동 연결 없음
- admin audit: request 완료 log만 있고 actor/변경 전후/DB audit 없음
- deployment rollback: 이전 image tag 보존은 하지만 자동 복구 실행 없음
- 다중 인스턴스: DB lock은 business transaction을 보호하지만 scheduler distributed lock 없음

### 미구현

- 개별 타자/lineup 기록 수집과 feature
- 초기 설계 문서에만 있는 경기 chat/message 신고, badge, notification, simulation, bad-word 사전, head-to-head stats, 별도 data-collection log
- OAuth/social login, email verification, password reset
- 사용자·권한·제재 관리 admin 기능
- WebSocket/실시간 push backend
- frontend automated test/E2E
- point ledger reconciliation/관리자 조정 workflow
- IaC, 중앙 로그/metric alert, DB backup/restore 자동화 코드

### 코드에는 있지만 현재 frontend/운영에서 사용되지 않음

- `/api/games/{id}/prediction`, `/api/games/{id}/odds` standalone GET
- dataset JSON/CSV/quality/multi-season evaluation admin API
- baseline-v2 train과 global model comparison API
- `BaselineV2ModelProperties.operationalExtraFeaturesEnabled`는 configuration response에 노출되지만 engine 계산 분기에서 읽히지 않는 현재 비활성/잔여 option
- Nginx `/ws/` proxy
- `SystemPrediction.homeScorePoint/awayScorePoint` legacy field
- `pnpm-lock.yaml`은 존재하지만 CI/Docker는 npm lockfile 사용
- `KBO/KBO 승부예측 쿼리.txt`의 SQL과 요구사항 spreadsheet는 현재 Flyway/JPA에서 사용하지 않는 과거 기획 자료
- `.gitignore`된 root zip 2개는 local archive이며 runtime/GitHub source가 아님

---

## 13. 하드코딩 / 기술부채 / 위험

`Critical`은 정적 분석에서 확인되지 않았다. 아래 심각도는 현재 단일 EC2·포트폴리오 규모를 고려한 상대 평가다.

| 심각도 | 항목 | 근거/영향 | 개선 |
|---|---|---|---|
| High | 인증 brute-force/rate limit 부재 | login에 cooldown/lockout가 없음. community write만 rate limit | IP+account throttle, audit/alert, optional CAPTCHA |
| High | KBO HTML/비공개 웹 endpoint 결합 | regex/string parser가 화면 구조 변경에 깨질 수 있음 | fixture contract, parser isolation, monitoring, retry/backoff |
| High | point 이중 저장 reconciliation 없음 | `users.point`와 ledger 불일치 시 자동 탐지/복구 불가 | consistency query/job, admin repair, invariant metric |
| Medium | scheduler가 JVM-local | `AtomicBoolean`은 multi-instance 중복 방지 못함 | ShedLock/DB lease/leader election |
| Medium | session이 container memory | 재배포·재시작 시 logout, scale-out 공유 불가 | Spring Session + Redis 또는 sticky session |
| Medium | 조회 API의 상태 변경 | `GameOddsService.getOddsByGameId()`가 lock/create/finalize 가능 | write를 scheduler/command로 분리, GET read-only |
| Medium | same-game global row lock | 정확하지만 인기 경기 betting throughput 병목 | atomic counter/upsert, shorter critical section, load test |
| Medium | rollback 후 음수 balance | correction 정합성은 지키지만 사용자 정책이 불명확 | negative account state와 betting block/repayment UX |
| Medium | 배포 artifact 재현성 | EC2에서 source build; CI가 검증한 image 그대로 배포하지 않음 | registry에 immutable image digest push/deploy |
| Medium | 자동 rollback 부재 | `:rollback` 보존만 하고 실패 시 복원하지 않음 | health failure rollback command/runbook test |
| Medium | 운영 관측성 제한 | actuator health와 log만 확인, alert/metric/APM 설정 없음 | Micrometer/Prometheus, collection freshness/settlement alerts |
| Medium | MySQL backup/IaC 없음 | Compose volume 외 backup/restore 증거 없음 | scheduled backup, restore drill, Terraform |
| Low | hard-coded domain/path | `playball.ai.kr`, cert path, EC2 project path | environment/config parameter화 |
| Low | 두 JS lockfile | npm/pnpm 해석 차이·보안 정책 혼선 | 하나의 package manager로 통일 |
| Low | 과거 설계 SQL과 실제 schema 불일치 | `KBO` 폴더에는 미구현 table이 다수 있어 독자가 구현으로 오해 가능 | `docs/archive` 이동, planning 표시, 실제 ERD는 migration에서 생성 |
| Low | known local test account migration | V3에 계정이 있으나 V11이 prod/test에서 삭제, local은 유지 | local fixture 문서화 또는 test-only seed로 이동 |
| Low | enum/status DB CHECK 부족 | 잘못된 direct DB value를 DB가 막지 못함 | enum CHECK/reference table |
| Low | list API pagination 불균형 | point/prediction 내역은 전량 조회 | cursor/page 적용 |
| Low | `LocalDateTime.now()` 직접 사용 | `User.changePoint` 시간 test 제어가 어려움 | Clock 주입 또는 service가 now 전달 |
| Low | admin log에 actor 없음 | 누가 실행했는지 감사 불가 | principal/correlation id/대상 revision 기록 |

source/config에서 유효한 `TODO`/`FIXME` marker는 찾지 못했다(lockfile hash와 이 문서의 문구 제외). marker가 없다는 뜻이 완성됐다는 뜻은 아니다. 특히 `/ws`, legacy score point, API-only model tooling은 “기능이 많아 보이게” 나열하지 말고 현재 사용 상태를 분리해야 한다.

---

## 14. 포트폴리오에서 기술적으로 강조할 부분

1. **외부 데이터의 신뢰도 단계와 동기화**  
   schedule과 GameCenter를 reconcile하고 공식 final 확인 전 result를 만들지 않으며, 상태 후퇴·확정 결과 덮어쓰기를 막았다. 단순 crawler가 아니라 변화하는 외부 상태를 domain state로 안전하게 반영한 사례다.

2. **point-in-time feature snapshot과 leakage 방지**  
   `collectedAt < gameStart` 조건, historical 전용 builder, immutable snapshot/history로 “당시 알 수 있던 정보”를 보존한다. 예측 정확도보다 평가 신뢰성을 먼저 설계했다는 설명 가치가 크다.

3. **규칙 기반 baseline과 실제 ML shadow의 정직한 분리**  
   운영 안정성을 위해 baseline-v1을 유지하면서 logistic artifact를 SHA 검증하고 동일 sample shadow 평가한다. 모델 승격을 성능 숫자 하나가 아닌 artifact/version/paired comparison 문제로 다룬다.

4. **포인트 원장과 잔액의 transaction 일관성**  
   balance 변경마다 history와 `balance_after`를 남기고 `MANDATORY` transaction으로 단독 변경을 막는다. 금융 시스템은 아니지만 ledger 사고방식을 보여준다.

5. **비관적 잠금 기반 동시 예측·정산**  
   game→user 잠금 순서, unique constraint, flush race handling으로 duplicate/lost update를 방지하고 실제 concurrency integration test를 두었다.

6. **revision 기반 정산 복구**  
   오정산을 row overwrite하지 않고 rollback history, reversal link, corrected snapshot, 새 revision으로 남긴다. 실패 시 전부 rollback하고 랭킹도 active revision만 본다.

7. **Flyway로 점진 강화한 DB 무결성**  
   초기 schema 뒤 V24~V26에서 NOT NULL/FK/CHECK를 보강했고 Hibernate는 validate만 한다. 코드 validation과 DB invariant의 역할을 면접에서 설명하기 좋다.

8. **스케줄러와 짧은 write transaction**  
   network I/O와 DB write를 분리하고 경기/팀별 `REQUIRES_NEW`로 부분 실패를 격리한다. 장점과 전체 batch atomicity가 없다는 trade-off까지 설명할 수 있다.

9. **운영까지 이어지는 배포 구성**  
   Nginx reverse proxy, TLS bootstrap/renewal, container health, GitHub OIDC와 SSM의 keyless deploy가 실제 설정으로 존재한다.

10. **테스트가 business failure mode를 겨냥함**  
    duplicate 정산, concurrent rollback, correction 3 revisions, partial failure atomicity, 미래 데이터 누출, 수집 불완전 데이터를 구체적으로 검증한다.

---

## 15. 프로젝트 개발자가 반드시 알고 있어야 할 내용

상세 면접 답변은 `docs/INTERVIEW_GUIDE.md`에 정리한다. 여기서는 반드시 숙지할 주제를 요약한다.

| 주제 | 현재 구현 | 선택 이유 | 대안 | 장단점 |
|---|---|---|---|---|
| 인증 | HttpSession+BCrypt+CSRF | same-origin 웹 SPA, server revocation 단순 | JWT/OAuth | 보안 기본기 좋음; stateful/scale-out session store 필요 |
| 잔액+원장 | `users.point` + `point_histories` | 빠른 balance와 감사 history 동시 확보 | 원장 합산 only, event sourcing | 조회 빠름; 이중 값 reconciliation 필요 |
| pessimistic lock | game→user row lock | betting pool/잔액/정산 충돌을 단순하게 직렬화 | optimistic version, atomic SQL, queue | 정확성 설명 쉬움; hot row 병목 |
| settlement revision | SETTLED/ROLLED_BACK와 새 revision | 공식 결과 correction 이력 보존 | 기존 row overwrite | audit/recovery 강함; domain 복잡도 증가 |
| 배당 | pari-mutuel pool, max 10 | 사용자 선택에 따라 동적 배당 | fixed odds | 구현 일관성; 초기/작은 pool 변동 큼 |
| baseline-v1 | hand-weighted + sigmoid | 데이터 부족에서도 설명 가능·결정론적 | trained ML | 해석 쉬움; 성능/weight 근거 제한 |
| logistic shadow | offline artifact + Java inference | Python server 없이 동일 runtime 추론 | Python model server/ONNX | 배포 단순; Java parity 관리 필요 |
| snapshot/history | current + immutable history | 운영 화면과 재현/평가 모두 지원 | current overwrite only | 추적성; 저장·schema 복잡도 |
| external collection | scheduler + official pages | 별도 공식 public API가 없는 조건에서 자동화 | manual/third-party API | 직접 통제; HTML 변화에 취약 |
| Flyway validate | migration만 schema 변경 | 재현 가능한 운영 schema | Hibernate auto-DDL | 안전; migration 작성 부담 |
| ranking query | native CTE on demand | correction된 current settlement를 즉시 반영 | ranking table/materialization | 정합성 단순; 규모 증가 시 비용 |
| EC2 deploy | Actions OIDC→SSM→Compose | SSH key 없이 단일 host 운영 | registry+ECS/K8s | 이해/운영 단순; immutable deploy/HA 부족 |

---

## 16. 예상 면접 질문

30개 이상의 코드 기반 질문과 현재 구현 범위를 넘지 않는 모범 답변은 문서가 과도하게 길어지는 것을 피하기 위해 `docs/INTERVIEW_GUIDE.md`에 별도로 작성한다.

---

## 17. 취업 포트폴리오 README 개편안

### 편집 원칙

- 첫 화면 30초 안에 서비스와 본인 구현 범위가 보여야 한다.
- “AI/ML 예측”을 headline으로 과장하지 말고 “규칙 기반 운영 baseline + ML shadow 실험”이라고 쓴다.
- 3~5분 독자를 위해 클래스별 설명은 이 분석 문서로 보내고 README에는 흐름·핵심 결정·검증 결과를 남긴다.
- 완료/부분 구현/향후 계획을 분리한다.
- 운영 URL이 실제 접근 가능할 때만 링크하고, sample account를 production에 만들지 않는다.

### 추천 목차와 실제 내용 초안

#### 1. 제목 / 한 줄 소개

```markdown
# PlayBall — KBO 승부예측·포인트 정산 서비스

공식 KBO 데이터를 수집해 경기·팀/선발투수 통계를 동기화하고,
규칙 기반 시스템 예측과 사용자 포인트 예측을 자동 정산하는 풀스택 서비스입니다.
```

바로 아래에 기술 badge를 과도하게 늘어놓기보다 서비스 screenshot 1장, 운영/시연 링크, backend 핵심 3개를 둔다.

#### 2. 개발 동기

```markdown
KBO 경기 정보와 팀·선발 데이터가 여러 공식 페이지에 흩어져 있고,
단순 승리팀 투표만으로는 예측 과정과 결과가 축적되지 않는 문제에서 시작했습니다.
수집 시점부터 예측, 포인트 거래, 공식 결과 정산, 랭킹까지 하나의 일관된 데이터 흐름으로 만들었습니다.
```

#### 3. 주요 기능

- KBO 일정·상태·결과, 팀 순위/성적, 선발투수 자동 수집
- 10개 통계 feature 기반 설명 가능한 운영 baseline 예측
- session 인증, 포인트 예측, pari-mutuel 배당
- 비관적 잠금과 원장 기반 자동 정산/취소/재정산
- 주간·월간 손익 및 전체 포인트 랭킹
- 커뮤니티와 운영 admin console

각 항목 옆에 완료 여부와 1개의 screenshot 또는 짧은 GIF를 연결한다.

#### 4. 서비스 흐름

```text
KBO 공식 웹 → 수집/검증 → MySQL snapshot → 시스템 예측
사용자 예측 → 포인트 차감/배당 pool → 공식 결과 수집
→ 정산 revision → 원장/잔액 → 랭킹
```

#### 5. 기술 스택

README에는 다음 정도만 남긴다.

- Frontend: React 19, TypeScript, Vite, Tailwind CSS
- Backend: Java 21, Spring Boot 4.1, Spring MVC/Security/Data JPA
- Data: MySQL 8.4, Flyway, Hibernate
- Prediction: Java baseline-v1, scikit-learn logistic-v1 shadow artifact
- Infra: Docker Compose, Nginx, HTTPS/Certbot, GitHub Actions, AWS EC2/SSM
- Test: JUnit 5, MockMvc, H2/MySQL integration, Python unittest

#### 6. 시스템 아키텍처

이 문서 3장의 다이어그램을 더 작은 이미지 또는 Mermaid로 옮긴다. User→Nginx→React/API→Spring→MySQL과 Spring→KBO, Actions→SSM→EC2만 보여준다.

#### 7. 핵심 기술적 고민

세 가지를 깊게, 나머지는 링크로 처리한다.

1. **동시 예측과 정산의 정합성**: game→user lock order, transaction, unique constraint, concurrency test
2. **오정산 복구**: overwrite 대신 revision+reversal ledger, ranking의 active revision filter
3. **예측 재현성**: point-in-time snapshot, provisional/final, baseline 운영과 logistic shadow 분리

각 항목은 `문제 → 선택 → 구현 → 검증 → 한계` 5줄 형식이 좋다.

#### 8. 데이터베이스

모든 20개 table을 한 ERD에 빽빽하게 넣지 말고 core flow를 표시한다.

```text
User ─< UserPrediction >─ Game ── GameOdds
  │          │              │
  └─< PointHistory >────────└─< GameSettlement(revision)

Team ─< TeamStat
Team/Game ─< StartingPitcher >─ Player ─< PitcherStat
Game ─ SystemPrediction ─< PredictionHistory >─ FeatureSnapshot
```

Community는 별도 작은 ERD 또는 문장으로 분리한다.

#### 9. 주요 API

README에는 auth, games, prediction, ranking, admin settlement 각 1~2개만 싣고 전체 표는 이 분석 문서로 링크한다.

#### 10. 트러블슈팅

코드 근거가 강한 세 사례를 추천한다.

- 동시 포인트 변경 lost update → user pessimistic lock + refresh + concurrency integration test
- 공식 결과 correction 시 중복 보상 → settlement revision + reversal history + unique constraint
- 과거 평가 미래 데이터 누출 → 수집 시각 제한 + historical feature builder + immutable snapshot

실제 장애 경험을 주장하려면 issue/commit/log 근거를 추가해야 한다. 현재 코드에서 해결된 설계 문제는 “설계/구현 과정의 문제”라고 표현한다.

#### 11. 배포 구조

```markdown
main push → MySQL 기반 backend test + frontend build
→ GitHub OIDC로 AWS role 획득 → SSM으로 EC2 배포
→ Docker Compose build/up → backend/frontend health check
```

HTTPS와 Certbot renewal을 덧붙이고, RDS/ECS/무중단 배포/자동 롤백은 사용하지 않았다고 정확히 쓴다.

#### 12. 테스트

- Java test report 기준 371개 실행 대상
- 정산·동시성·migration·security·collector 중심
- CI는 MySQL 8.4 service 사용
- frontend automated test는 향후 과제로 공개

README를 제출하기 전 깨끗한 환경에서 CI green badge를 확인하고 그때의 실제 수치로 갱신한다. 이번 로컬 10개 실패를 숨기지 말되, README의 대표 결과는 CI 성공 run에 근거해야 한다.

#### 13. 프로젝트 현황

```markdown
- 운영 기본 예측: baseline-v1 (규칙/통계 기반)
- 실험 모델: logistic-v1 (shadow 평가), baseline-v2
- 배포: 단일 EC2 + Docker Compose + container MySQL
- 완료: 수집/예측/포인트/정산/랭킹/커뮤니티/admin
- 부분 구현: ML 운영 승격, 신고 후 제재, 분산 scheduler, 자동 rollback
```

#### 14. 향후 개선사항

우선순위 순으로 적는다.

1. MySQL 기반 concurrency test 확대와 Testcontainers 도입
2. point ledger reconciliation 및 운영 alert
3. KBO parser contract monitoring/retry
4. logistic shadow sample gate·calibration·draw 개선 후 승격 검토
5. Redis session/ShedLock으로 scale-out 준비
6. immutable image registry와 자동 rollback
7. frontend E2E/accessibility test

#### 15. 상세 문서 링크

```markdown
- [코드 기반 전체 분석](docs/PROJECT_ANALYSIS.md)
- [면접 대비 가이드](docs/INTERVIEW_GUIDE.md)
- [HTTPS 배포](docs/HTTPS_DEPLOYMENT.md)
- [모델 비교](docs/MODEL_COMPARISON_BASELINE_V1_VS_LOGISTIC_V1_2026-08-14.md)
```

### README에서 피해야 할 표현

- “AI가 KBO 승부를 예측한다” → 운영은 hand-weighted baseline임
- “실시간 데이터” → 경기 상태는 기본 5분 polling, 선발 missing은 60초 polling
- “무중단 배포/자동 롤백” → health check와 이전 image 보존만 확인됨
- “AWS 클라우드 아키텍처”를 RDS/ECS까지 확장 표현 → 확인되는 것은 EC2/SSM/OIDC
- “완벽한 동시성 제어” → 현재 lock 전략과 test 범위, hot-row 한계를 함께 설명
- “선수 데이터 분석” → 현재 개별 선수는 선발투수·투수 기록만 수집

---

## 부록 A. 주요 근거 파일

- build/version: `backend/build.gradle`, `backend/gradle/wrapper/gradle-wrapper.properties`, `frontend/package.json`, `frontend/package-lock.json`
- profile/security: `backend/src/main/resources/application*.yaml`, `common/config/SecurityConfig.java`, `auth/controller/AuthController.java`
- schema: `backend/src/main/resources/db/migration/V1__initial_schema.sql` ~ `V27__prediction_starting_pitcher_identity.sql`
- collection: `game/collection/*`, `stats/collection/*`
- prediction: `prediction/engine/*`, `prediction/feature/*`, `prediction/generation/*`, `prediction/history/*`
- ML: `backend/ml/logistic_pipeline.py`, `backend/ml/artifacts/logistic-v1.json`, `backend/ml/artifacts/logistic-v1-report.json`
- betting/settlement: `UserPredictionService`, `GameOddsService`, `PredictionSettlementService`, `GameSettlementRecoveryService`, `PointService`
- ranking: `ranking/repository/RankingQueryRepository.java`
- frontend API call sites: `frontend/src/lib`, `frontend/src/components`
- deployment: `compose.yaml`, `backend/Dockerfile`, `frontend/Dockerfile`, `frontend/nginx*`, `.github/workflows/deploy.yml`, `ops/reload-frontend-nginx.sh`

## 부록 B. 분석 신뢰도와 제한

- 정적 코드·migration·configuration과 test 결과를 함께 확인했다.
- 외부 KBO endpoint에 실제 network request를 보내지는 않았다. 수집 성공 여부는 fixture test와 코드 계약 기준이다.
- Docker daemon과 local MySQL이 분석 환경에서 실행 중이지 않아 Compose full E2E는 수행하지 못했다.
- 선발투수 identity 기반 refresh와 V27은 commit `ad12ef1`의 공개 HEAD에 포함된 상태로 검증했다.
